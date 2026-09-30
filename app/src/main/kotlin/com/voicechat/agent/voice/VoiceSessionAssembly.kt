package com.voicechat.agent.voice

import android.content.Context
import com.voicechat.agent.audio.MicrophoneAudioCapture
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.orchestration.TurnResult
import com.voicechat.agent.stt.MlKitSpeechToText
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.tts.OnDeviceTts
import com.voicechat.agent.turn.NoSmartTurnDetectorProvider
import com.voicechat.agent.turn.SmartTurnDetectorProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * App-boundary factory for the production M24 voice session.
 *
 * This is deliberately the *only* file in the `voice` package that touches
 * platform types (`Context`, `MlKitSpeechToText`, `MicrophoneAudioCapture`,
 * `OnDeviceTts`); the coordinator and its seams stay `android.*`-free and
 * JVM-testable (`VoiceSourcePurityTest`).
 *
 * It builds the M07 capture, M08 recognizer, and M11 TTS behind the M02
 * contracts, the M09 bounded endpoint policy, and a fresh M21 orchestrator per
 * session. STT availability is read from the runtime (`MlKitSttStatus.check`), so
 * a device with no provisioned model reports a typed `STT_UNAVAILABLE` instead of
 * a fabricated session.
 *
 * Device testing is deferred: this path is compiled and wired but has not been
 * run on hardware (see `Tests.md`).
 */
object VoiceSessionAssembly {
    /** @return a [VoiceSessionFactory] that builds one platform session per call. */
    fun platformFactory(
        context: Context,
        repository: ConversationRepository,
        fallbackLanguageModel: LanguageModel,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = SystemMonotonicClock,
        locale: Locale = Locale.getDefault(),
        turnCompletion: SmartTurnDetectorProvider = NoSmartTurnDetectorProvider,
        /**
         * Resolves the persisted STT mode/locale/voice for one session (R-0181).
         * Called once per session on the caller's coroutine; a selection that is
         * unavailable is reported, never substituted.
         */
        selectionProvider: suspend () -> VoiceRuntimeSelection = { VoiceRuntimeSelection(locale = locale) },
    ): VoiceSessionFactory =
        VoiceSessionFactory { listener, providerSource ->
            PlatformVoiceSession(
                context = context.applicationContext,
                repository = repository,
                fallbackLanguageModel = fallbackLanguageModel,
                diagnostics = diagnostics,
                clock = clock,
                downstream = listener,
                providerSource = providerSource,
                turnCompletion = turnCompletion,
                selectionProvider = selectionProvider,
            )
        }
}

/**
 * One platform-backed voice session. It owns the capture/STT/TTS lifetime for a
 * single [VoiceSessionController.run] and mirrors the coordinator's state to the
 * UI listener and flow.
 */
private class PlatformVoiceSession(
    private val context: Context,
    private val repository: ConversationRepository,
    private val fallbackLanguageModel: LanguageModel,
    private val diagnostics: DiagnosticsSink,
    private val clock: MonotonicClock,
    private val downstream: VoiceSessionListener,
    private val providerSource: VoiceTurnProviderSource,
    private val turnCompletion: SmartTurnDetectorProvider,
    private val selectionProvider: suspend () -> VoiceRuntimeSelection,
) : VoiceSessionController,
    VoiceSessionListener {
    private val _state = MutableStateFlow(VoiceSessionState.IDLE)

    override val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    @Volatile
    private var coordinator: VoiceSessionCoordinator? = null

    override suspend fun run(conversation: Conversation) {
        val selection = selectionProvider()
        val engine = readyEngine(selection)
        if (engine == null) {
            val error = VoiceAgentError(ErrorCode.STT_UNAVAILABLE, "no ready on-device STT engine for the selected mode")
            _state.value = VoiceSessionState.FAILED
            downstream.onSessionState(VoiceSessionState.FAILED)
            downstream.onError(error)
            return
        }
        val speechToText = MlKitSpeechToText(engine = engine, diagnostics = diagnostics, clock = clock)
        val audioInput = MicrophoneAudioCapture.create(context = context, diagnostics = diagnostics, clock = clock)
        val textToSpeech: TextToSpeech? =
            try {
                OnDeviceTts.create(context = context, locale = selection.locale, diagnostics = diagnostics, clock = clock)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                // Do not silently degrade: report the typed no-voice/init failure so
                // the dialog can explain that this session is text-only (R-0180).
                val error =
                    (failure as? VoiceAgentException)?.error
                        ?: VoiceAgentError(ErrorCode.TTS_PLAYBACK_FAILED, "the on-device TTS engine could not be initialized")
                downstream.onTextToSpeechUnavailable(error)
                null
            }
        // M10: resolve the optional semantic detector once per session. A disabled
        // or unavailable Smart Turn returns null and the bounded VAD-only policy
        // applies; it is never fabricated, and it is closed with the session.
        val semanticDetector = resolveSemanticDetector()
        val session =
            VoiceSessionCoordinator(
                audioInput = audioInput,
                speechToText = speechToText,
                textToSpeech = textToSpeech,
                turnDetector =
                    PolicyVoiceTurnDetector.forRoute(
                        diagnostics = diagnostics,
                        clock = clock,
                        semanticDetector = semanticDetector,
                    ),
                orchestratorFactory = {
                    TurnOrchestrator(
                        repository = repository,
                        languageModel = fallbackLanguageModel,
                        textToSpeech = textToSpeech,
                        diagnostics = diagnostics,
                        clock = clock,
                    )
                },
                providerSource = providerSource,
                listener = this,
                diagnostics = diagnostics,
                clock = clock,
            )
        coordinator = session
        try {
            session.run(conversation)
        } finally {
            coordinator = null
            try {
                semanticDetector?.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // Release best-effort; the session already ended.
            }
            try {
                speechToText.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // Release best-effort; the session already ended.
            }
            try {
                textToSpeech?.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // Release best-effort.
            }
            try {
                audioInput.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // Release best-effort.
            }
        }
    }

    override fun stop() {
        coordinator?.stop()
    }

    private suspend fun readyEngine(selection: VoiceRuntimeSelection): SttEngine? {
        val statuses = SttEngines.catalog(selection.locale).map { MlKitSttStatus.check(it) }
        return SttEngines.select(statuses, selection.sttMode)
    }

    /** Resolves the optional semantic detector; a failure degrades to the VAD-only policy. */
    private suspend fun resolveSemanticDetector(): TurnCompletionDetector? =
        try {
            turnCompletion.detector()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            // The detector already records its own typed unavailable reason; the
            // loop must still run with the bounded VAD-only endpoint.
            null
        }

    // region VoiceSessionListener passthrough (coordinator -> UI listener)

    override fun onSessionState(state: VoiceSessionState) {
        _state.value = state
        downstream.onSessionState(state)
    }

    override fun onListeningStarted(turnId: TurnId) = downstream.onListeningStarted(turnId)

    override fun onProvisionalTranscript(
        turnId: TurnId,
        text: String,
    ) = downstream.onProvisionalTranscript(turnId, text)

    override fun onUtteranceCommitted(
        turnId: TurnId,
        transcript: Transcript,
    ) = downstream.onUtteranceCommitted(turnId, transcript)

    override fun onAssistantText(
        turnId: TurnId,
        text: String,
    ) = downstream.onAssistantText(turnId, text)

    override fun onConversationChanged(conversation: Conversation) = downstream.onConversationChanged(conversation)

    override fun onTurnFinished(result: TurnResult) = downstream.onTurnFinished(result)

    override fun onBargeIn(timing: BargeInTiming) = downstream.onBargeIn(timing)

    override fun onInterruptionRecovered(recovery: VoiceInterruptionRecovery) = downstream.onInterruptionRecovered(recovery)

    override fun onNoSpeech() = downstream.onNoSpeech()

    override fun onError(error: VoiceAgentError) = downstream.onError(error)

    override fun onTextToSpeechUnavailable(error: VoiceAgentError) = downstream.onTextToSpeechUnavailable(error)

    // endregion
}
