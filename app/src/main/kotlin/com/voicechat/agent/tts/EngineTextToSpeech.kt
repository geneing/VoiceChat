package com.voicechat.agent.tts

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The M02 [TextToSpeech] contract implemented over a replaceable [TtsEngine].
 *
 * This class is deliberately platform-free: it maps the engine seam onto the
 * domain [TtsEvent] contract, enforces the on-device-only policy, and keeps
 * playback accounting truthful. All `android.speech.tts` types stay in the
 * Android engine (`AndroidTtsEngine`).
 *
 * **On-device only.** The first [speak] initializes the engine; if no embedded
 * voice is available it emits a single typed [TtsEvent.Failed]
 * (`TTS_NO_ON_DEVICE_VOICE`) and never falls back to a network voice
 * (`docs/decisions.md` §2.2). Empty input completes without playback.
 *
 * **Queued chunking.** [speak] returns a cold per-utterance flow. Each call maps
 * one text chunk to one engine utterance, so orchestration can enqueue
 * incremental chunks in order while earlier chunks are still playing, and
 * collect each chunk's terminal event.
 *
 * **Accounting.** Every non-empty utterance emits `Queued` first and ends in
 * exactly one terminal (`Delivered`, `Interrupted`, or `Failed`). [stop] is
 * forwarded to the engine, whose in-flight flows end in `Interrupted` with the
 * prefix it could confirm was audible, so an interruption never leaves a queued
 * chunk unaccounted.
 *
 * **Diagnostics.** Progress is recorded through the M04 [DiagnosticsSink] with
 * only counts, identities, route kind, and stable codes — never transcript or
 * assistant text. Turn correlation is bound by orchestration (M21).
 */
class EngineTextToSpeech(
    private val engine: TtsEngine,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
) : TextToSpeech {
    private val initMutex = Mutex()

    @Volatile
    private var availability: TtsEngineAvailability? = null

    @Volatile
    private var closed: Boolean = false

    override fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEvent> =
        flow {
            check(!closed) { "EngineTextToSpeech has been closed" }
            // Empty input completes without playback (M02 contract).
            if (text.isEmpty()) return@flow

            val ready = ensureAvailability()
            val voice = (ready as? TtsEngineAvailability.Ready)?.voice
            if (voice == null) {
                val error = ready.toNoVoiceError()
                AppLog.w { "tts: refusing to speak (${error.code.name})" }
                record(
                    stage = DiagnosticStage.TTS_SYNTHESIS,
                    outcome = DiagnosticOutcome.FAILED,
                    attributes =
                        mapOf(
                            DiagnosticAttribute.ENGINE_ID to engine.engineId.value,
                            DiagnosticAttribute.ERROR_CODE to error.code.name,
                        ),
                )
                emit(TtsEvent.Failed(error))
                return@flow
            }

            AppLog.d { "tts: synthesis start chars=${text.length} voice=${voice.id}" }
            record(
                stage = DiagnosticStage.TTS_SYNTHESIS,
                outcome = DiagnosticOutcome.STARTED,
                attributes =
                    mapOf(
                        DiagnosticAttribute.ENGINE_ID to engine.engineId.value,
                        DiagnosticAttribute.MODEL_ID to voice.id,
                        DiagnosticAttribute.CHARACTER_COUNT to text.length.toString(),
                    ),
            )
            emit(TtsEvent.Queued(utteranceId, text))

            var terminal: DiagnosticOutcome? = null
            try {
                engine.speak(text, utteranceId).collect { event ->
                    when (event) {
                        is TtsEngineEvent.Started -> {
                            record(
                                stage = DiagnosticStage.TTS_PLAYBACK,
                                outcome = DiagnosticOutcome.PROGRESS,
                                attributes =
                                    buildMap {
                                        put(DiagnosticAttribute.STREAM_STATE, "first-audible")
                                        event.route?.let { put(DiagnosticAttribute.AUDIO_ROUTE, it.label) }
                                    },
                            )
                            emit(TtsEvent.Started(utteranceId, text))
                        }

                        is TtsEngineEvent.Completed -> {
                            terminal = DiagnosticOutcome.COMPLETED
                            record(
                                stage = DiagnosticStage.TTS_PLAYBACK,
                                outcome = DiagnosticOutcome.COMPLETED,
                                attributes = playbackAttributes(delivered = text.length, total = text.length, interrupted = false),
                            )
                            emit(TtsEvent.Delivered(utteranceId, text))
                        }

                        is TtsEngineEvent.Interrupted -> {
                            val delivered = event.deliveredText.take(text.length)
                            terminal = DiagnosticOutcome.CANCELLED
                            record(
                                stage = DiagnosticStage.TTS_PLAYBACK,
                                outcome = DiagnosticOutcome.CANCELLED,
                                attributes = playbackAttributes(delivered = delivered.length, total = text.length, interrupted = true),
                            )
                            emit(TtsEvent.Interrupted(utteranceId, delivered))
                        }

                        is TtsEngineEvent.Failed -> {
                            val error = event.kind.toError()
                            terminal = DiagnosticOutcome.FAILED
                            AppLog.w { "tts: playback failed (${error.code.name})" }
                            record(
                                stage = DiagnosticStage.TTS_SYNTHESIS,
                                outcome = DiagnosticOutcome.FAILED,
                                attributes = mapOf(DiagnosticAttribute.ERROR_CODE to error.code.name),
                            )
                            emit(TtsEvent.Failed(error))
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                if (terminal == null) {
                    record(
                        stage = DiagnosticStage.TTS_SYNTHESIS,
                        outcome = DiagnosticOutcome.CANCELLED,
                        attributes = mapOf(DiagnosticAttribute.CHARACTER_COUNT to text.length.toString()),
                    )
                }
                throw cancellation
            }

            val finalOutcome = terminal
            if (finalOutcome == null) {
                // The engine flow ended without a terminal event. Report a typed
                // failure rather than silently fabricating success.
                val error = VoiceAgentError(ErrorCode.TTS_SYNTHESIS_FAILED)
                record(
                    stage = DiagnosticStage.TTS_SYNTHESIS,
                    outcome = DiagnosticOutcome.FAILED,
                    attributes = mapOf(DiagnosticAttribute.ERROR_CODE to error.code.name),
                )
                emit(TtsEvent.Failed(error))
            } else {
                record(
                    stage = DiagnosticStage.TTS_SYNTHESIS,
                    outcome = finalOutcome,
                    attributes = mapOf(DiagnosticAttribute.CHARACTER_COUNT to text.length.toString()),
                )
            }
        }

    override suspend fun stop() {
        if (closed) return
        AppLog.d { "tts: stop requested" }
        engine.stop()
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        engine.close()
    }

    private suspend fun ensureAvailability(): TtsEngineAvailability {
        availability?.let { return it }
        return initMutex.withLock {
            availability ?: engine.initialize().also { availability = it }
        }
    }

    private fun TtsEngineAvailability.toNoVoiceError(): VoiceAgentError =
        when (this) {
            is TtsEngineAvailability.Ready -> {
                VoiceAgentError(ErrorCode.TTS_SYNTHESIS_FAILED)
            }

            is TtsEngineAvailability.NoOnDeviceVoice -> {
                VoiceAgentError(
                    code = ErrorCode.TTS_NO_ON_DEVICE_VOICE,
                    detail = "no embedded voice installed for locale ${locale.toLanguageTag()}",
                )
            }

            is TtsEngineAvailability.Unavailable -> {
                error
            }
        }

    private fun TtsEngineFailureKind.toError(): VoiceAgentError =
        VoiceAgentError(
            code =
                when (this) {
                    TtsEngineFailureKind.SYNTHESIS_FAILED -> ErrorCode.TTS_SYNTHESIS_FAILED
                    TtsEngineFailureKind.PLAYBACK_FAILED -> ErrorCode.TTS_PLAYBACK_FAILED
                },
        )

    private fun playbackAttributes(
        delivered: Int,
        total: Int,
        interrupted: Boolean,
    ): Map<DiagnosticAttribute, String> =
        mapOf(
            DiagnosticAttribute.STREAM_STATE to if (interrupted) "interrupted" else "completed",
            DiagnosticAttribute.DELIVERED_CHARACTER_COUNT to delivered.toString(),
            DiagnosticAttribute.TOTAL_CHARACTER_COUNT to total.toString(),
        )

    private fun record(
        stage: DiagnosticStage,
        outcome: DiagnosticOutcome,
        attributes: Map<DiagnosticAttribute, String>,
    ) {
        diagnostics.record(
            DiagnosticEvent(
                stage = stage,
                outcome = outcome,
                monotonicTimeNanos = clock.nanoTime(),
                attributes = attributes,
            ),
        )
    }
}
