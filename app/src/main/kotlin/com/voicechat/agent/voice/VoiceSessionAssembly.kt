package com.voicechat.agent.voice

import android.content.Context
import com.voicechat.agent.audio.MicrophoneAudioCapture
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.orchestration.TurnResult
import com.voicechat.agent.stt.MlKitSpeechToText
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.tts.OnDeviceTts
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
    ): VoiceSessionFactory =
        VoiceSessionFactory { listener, providerSource ->
            PlatformVoiceSession(
                context = context.applicationContext,
                repository = repository,
                fallbackLanguageModel = fallbackLanguageModel,
                diagnostics = diagnostics,
                clock = clock,
                locale = locale,
                downstream = listener,
                providerSource = providerSource,
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
    private val locale: Locale,
    private val downstream: VoiceSessionListener,
    private val providerSource: VoiceTurnProviderSource,
) : VoiceSessionController,
    VoiceSessionListener {
    private val _state = MutableStateFlow(VoiceSessionState.IDLE)

    override val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    @Volatile
    private var coordinator: VoiceSessionCoordinator? = null

    override suspend fun run(conversation: Conversation) {
        val engine = readyEngine()
        if (engine == null) {
            val error = VoiceAgentError(ErrorCode.STT_UNAVAILABLE, "no ready on-device STT engine")
            _state.value = VoiceSessionState.FAILED
            downstream.onSessionState(VoiceSessionState.FAILED)
            downstream.onError(error)
            return
        }
        val speechToText = MlKitSpeechToText(engine = engine, diagnostics = diagnostics, clock = clock)
        val audioInput = MicrophoneAudioCapture.create(context = context, diagnostics = diagnostics, clock = clock)
        val textToSpeech: TextToSpeech? =
            try {
                OnDeviceTts.create(context = context, locale = locale, diagnostics = diagnostics, clock = clock)
            } catch (failure: Throwable) {
                null
            }
        val session =
            VoiceSessionCoordinator(
                audioInput = audioInput,
                speechToText = speechToText,
                textToSpeech = textToSpeech,
                turnDetector = PolicyVoiceTurnDetector.forRoute(diagnostics = diagnostics, clock = clock),
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

    private suspend fun readyEngine(): SttEngine? =
        SttEngines.catalog(locale).firstOrNull { MlKitSttStatus.check(it) is SttAvailability.Ready }

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

    // endregion
}
