package com.voicechat.agent.turn

import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.ErrorCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Supplies the optional semantic detector for one voice session.
 *
 * Returning `null` means "use the M09 bounded VAD-only policy"; the voice loop
 * never fabricates a detector. This is the seam the M24 coordinator's
 * `VoiceTurnDetector` is built from, so tests can replace it with a deterministic
 * script and the production app resolves it from settings + the installed model.
 */
fun interface SmartTurnDetectorProvider {
    /** @return a ready detector, or `null` when Smart Turn is disabled/unavailable. */
    suspend fun detector(): TurnCompletionDetector?
}

/** The honest default: Smart Turn is off, so the VAD-only bounded policy is used. */
object NoSmartTurnDetectorProvider : SmartTurnDetectorProvider {
    override suspend fun detector(): TurnCompletionDetector? = null
}

/** Loads a [SmartTurnInferenceEngine] from a verified model file. */
fun interface SmartTurnEngineFactory {
    /** Opens the model; throws if the graph cannot be loaded. */
    fun open(modelFile: File): SmartTurnInferenceEngine
}

/**
 * The production [SmartTurnDetectorProvider] (M10).
 *
 * Behavior is exactly the opt-in contract in [docs/decisions.md](../docs/decisions.md)
 * §3.3 and [docs/smart-turn.md](../docs/smart-turn.md):
 *
 * - **disabled (default)** → `null`; the detector is **never constructed**, the
 *   engine is not opened, and the VAD-only policy applies;
 * - **enabled but missing** → `null`, recorded as `MODEL_UNAVAILABLE`, and the
 *   settings surface reports a download is required;
 * - **enabled but corrupt** → `null`, recorded as `MODEL_CORRUPT`, with a
 *   user-visible reason;
 * - **enabled and verified** → a `SmartTurnCompletionDetector`; a graph that
 *   fails to load is another typed unavailable, never a success-shaped fallback.
 *
 * The engine is opened on [ioDispatcher] and the load duration is recorded, so a
 * slow native load never blocks the main thread.
 */
class SmartTurnDetectorFactory(
    private val store: SmartTurnModelStore,
    private val enabled: suspend () -> Boolean,
    private val engineFactory: SmartTurnEngineFactory,
    private val config: SmartTurnConfig = SmartTurnConfig.default(),
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SmartTurnDetectorProvider {
    private val reporter = SmartTurnDiagnostics(diagnostics, clock)

    override suspend fun detector(): TurnCompletionDetector? {
        if (!enabled()) return null

        return when (val state = store.state()) {
            is SmartTurnModelState.Installed -> {
                openDetector(state)
            }

            SmartTurnModelState.Missing -> {
                reporter.unavailable(
                    ErrorCode.MODEL_UNAVAILABLE,
                    "Smart Turn is enabled but the model is not installed",
                    integrity = INTEGRITY_MISSING,
                )
                null
            }

            is SmartTurnModelState.Corrupt -> {
                reporter.unavailable(ErrorCode.MODEL_CORRUPT, state.reason, integrity = INTEGRITY_CORRUPT)
                null
            }
        }
    }

    private suspend fun openDetector(state: SmartTurnModelState.Installed): TurnCompletionDetector? {
        val startedAt = clock.nanoTime()
        return try {
            val engine = withContext(ioDispatcher) { engineFactory.open(state.file) }
            reporter.loaded(state.file, state.sizeBytes, clock.nanoTime() - startedAt)
            SmartTurnCompletionDetector(
                engine = engine,
                config = config,
                sink = diagnostics,
                clock = clock,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            reporter.unavailable(
                ErrorCode.MODEL_UNAVAILABLE,
                "the Smart Turn model failed to load",
                integrity = INTEGRITY_LOAD_FAILED,
            )
            null
        }
    }

    private companion object {
        const val INTEGRITY_MISSING = "MISSING"
        const val INTEGRITY_CORRUPT = "CORRUPT"
        const val INTEGRITY_LOAD_FAILED = "LOAD_FAILED"
    }
}
