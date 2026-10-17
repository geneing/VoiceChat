package com.voicechat.agent.voice

import android.content.Context
import com.voicechat.agent.audio.MicrophoneAudioCapture
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.stt.AndroidSpeechRecognizerSpeechToText
import com.voicechat.agent.stt.AndroidSpeechSttStatus
import com.voicechat.agent.stt.MlKitSpeechToText
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttEngineFamily
import com.voicechat.agent.stt.SttEngines
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
 * platform types (`Context`, `AndroidSpeechRecognizerSpeechToText`,
 * `MlKitSpeechToText`, `MicrophoneAudioCapture`); the coordinator and its seams
 * stay `android.*`-free and JVM-testable (`VoiceSourcePurityTest`). TTS is not
 * created or connected here.
 *
 * It builds M07 capture and the selected STT adapter behind the M02 contracts,
 * plus the M09 bounded endpoint policy and optional M10 Smart Turn detector. The
 * voice path does not resolve an LLM provider or initialize TTS. STT availability
 * is read from the runtime (`AndroidSpeechSttStatus.check`, `MlKitSttStatus.check`),
 * so a device with no on-device recognizer reports a typed `STT_UNAVAILABLE`
 * instead of a fabricated session.
 *
 * Device testing is deferred: this path is compiled and wired but has not been
 * run on hardware (see `Tests.md`).
 */
object VoiceSessionAssembly {
    /** @return a [VoiceSessionFactory] that builds one platform session per call. */
    fun platformFactory(
        context: Context,
        repository: ConversationRepository,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = SystemMonotonicClock,
        locale: Locale = Locale.getDefault(),
        turnCompletion: SmartTurnDetectorProvider = NoSmartTurnDetectorProvider,
        /**
         * Resolves the persisted STT mode and locale for one session (R-0181).
         * Called once per session on the caller's coroutine; a selection that is
         * unavailable is reported, never substituted.
         */
        selectionProvider: suspend () -> VoiceRuntimeSelection = { VoiceRuntimeSelection(locale = locale) },
        /**
         * Overrides the microphone capture source for one session. Production
         * leaves this `null` and capture comes from [MicrophoneAudioCapture]; the
         * debug build injects a file-backed source so the whole voice loop can be
         * exercised from a staged file without a live microphone. It is never a
         * silent substitution: a supplied factory is used exactly as given.
         */
        audioInputFactory: (() -> AudioInput)? = null,
    ): VoiceSessionFactory =
        VoiceSessionFactory { listener ->
            PlatformVoiceSession(
                context = context.applicationContext,
                repository = repository,
                diagnostics = diagnostics,
                clock = clock,
                downstream = listener,
                turnCompletion = turnCompletion,
                selectionProvider = selectionProvider,
                audioInputFactory = audioInputFactory,
            )
        }
}

/**
 * One platform-backed STT session. It owns the capture/STT lifetime for a
 * single [VoiceSessionController.run] and mirrors the coordinator's state to the
 * UI listener and flow.
 */
private class PlatformVoiceSession(
    private val context: Context,
    private val repository: ConversationRepository,
    private val diagnostics: DiagnosticsSink,
    private val clock: MonotonicClock,
    private val downstream: VoiceSessionListener,
    private val turnCompletion: SmartTurnDetectorProvider,
    private val selectionProvider: suspend () -> VoiceRuntimeSelection,
    private val audioInputFactory: (() -> AudioInput)? = null,
) : VoiceSessionController,
    VoiceSessionListener {
    private val _state = MutableStateFlow(VoiceSessionState.IDLE)

    override val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    @Volatile
    private var coordinator: VoiceSessionCoordinator? = null

    override suspend fun run(conversation: Conversation) {
        val selection = selectionProvider()
        val stt = selection.stt ?: VoiceSttResolution.resolve(availableStt(selection.locale), selection.sttMode)
        if (stt !is VoiceSttSelection.Ready) {
            val error = (stt as VoiceSttSelection.Unavailable).error
            AppLog.voice(LogLevel.WARN) {
                "voice: session start refused sttMode=${selection.sttMode} code=${error.code} detail=${error.detail}"
            }
            reportSttUnavailable(error)
            return
        }
        val engine = stt.engine
        AppLog.voice(LogLevel.INFO) {
            "voice: session start sttMode=${engine.mode} family=${engine.family} engineId=${engine.engineId.value}"
        }
        // The engine family decides the adapter: the platform SpeechRecognizer or
        // ML Kit GenAI. A selection the app cannot honor is reported, never faked.
        val speechToText: SpeechToText =
            when (engine.family) {
                SttEngineFamily.ANDROID_SPEECH -> {
                    AndroidSpeechRecognizerSpeechToText(
                        context = context,
                        engine = engine,
                        diagnostics = diagnostics,
                        clock = clock,
                    )
                }

                SttEngineFamily.ML_KIT_GENAI -> {
                    MlKitSpeechToText(engine = engine, diagnostics = diagnostics, clock = clock)
                }
            }
        val audioInput =
            audioInputFactory?.invoke()
                ?: MicrophoneAudioCapture.create(context = context, diagnostics = diagnostics, clock = clock)
        // Smart Turn only corroborates the VAD candidate pause for engines that do
        // not provide their own endpoint hint.
        val semanticDetector = resolveSemanticDetector()
        val session =
            VoiceSessionCoordinator(
                audioInput = audioInput,
                repository = repository,
                speechToText = speechToText,
                turnDetector =
                    PolicyVoiceTurnDetector.forRoute(
                        diagnostics = diagnostics,
                        clock = clock,
                        semanticDetector = semanticDetector,
                    ),
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
                // The session owns its STT adapter; close it once here.
                speechToText.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // Release best-effort; the session already ended.
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

    /**
     * The runtime availability for every catalog mode, both families, with the
     * platform recognizer first. The platform engine is checked through
     * [AndroidSpeechSttStatus] and the ML Kit modes through [MlKitSttStatus].
     */
    private suspend fun availableStt(locale: Locale): List<SttAvailability> =
        listOf(
            AndroidSpeechSttStatus.check(context, SttEngine(com.voicechat.agent.stt.SttMode.ANDROID_SPEECH, locale)),
        ) + SttEngines.mlKitCatalog(locale).map { MlKitSttStatus.check(it) }

    private fun reportSttUnavailable(error: VoiceAgentError) {
        _state.value = VoiceSessionState.FAILED
        downstream.onSessionState(VoiceSessionState.FAILED)
        downstream.onError(error)
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

    override fun onSttEndOfSpeech(turnId: TurnId) = downstream.onSttEndOfSpeech(turnId)

    override fun onUtteranceCommitted(
        turnId: TurnId,
        transcript: Transcript,
    ) = downstream.onUtteranceCommitted(turnId, transcript)

    override fun onConversationChanged(conversation: Conversation) = downstream.onConversationChanged(conversation)

    override fun onNoSpeech() = downstream.onNoSpeech()

    override fun onError(error: VoiceAgentError) = downstream.onError(error)

    // endregion
}
