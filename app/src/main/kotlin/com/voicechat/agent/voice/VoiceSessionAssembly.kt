package com.voicechat.agent.voice

import android.content.Context
import com.voicechat.agent.audio.MicrophoneAudioCapture
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechToText
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
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.orchestration.TurnResult
import com.voicechat.agent.stt.MlKitSpeechToText
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.ParakeetEouSttCatalog
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttEngineFamily
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.stt.parakeet.ParakeetEouModelStore
import com.voicechat.agent.tts.OnDeviceTts
import com.voicechat.agent.turn.NoSmartTurnDetectorProvider
import com.voicechat.agent.turn.SmartTurnDetectorProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
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
         * Builds the Parakeet-EOU adapter pair when that engine is selected
         * (stream 4 seam). Defaults to honest-unavailable until stream 3 wires
         * the ONNX adapter.
         */
        parakeetSessionFactory: ParakeetEouSessionFactory = UnavailableParakeetEouSessionFactory,
        /**
         * Resolves the persisted STT mode/locale/voice for one session (R-0181).
         * Called once per session on the caller's coroutine; a selection that is
         * unavailable is reported, never substituted.
         */
        selectionProvider: suspend () -> VoiceRuntimeSelection = { VoiceRuntimeSelection(locale = locale) },
        /**
         * Overrides the microphone capture source for one session. Production
         * leaves this `null` and capture comes from [MicrophoneAudioCapture]; the
         * debug build injects a file-backed source so the whole voice loop can be
         * exercised from a staged WAV without a live microphone. It is never a
         * silent substitution: a supplied factory is used exactly as given.
         */
        audioInputFactory: (() -> AudioInput)? = null,
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
                parakeetModelStore = ParakeetEouModelStore(File(context.applicationContext.filesDir, ParakeetEouModelStore.DIRECTORY_NAME)),
                parakeetSessionFactory = parakeetSessionFactory,
                selectionProvider = selectionProvider,
                audioInputFactory = audioInputFactory,
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
    private val parakeetModelStore: ParakeetEouModelStore,
    private val parakeetSessionFactory: ParakeetEouSessionFactory,
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
        // The engine family decides the adapter: ML Kit's system-managed modes, or
        // the app-managed Parakeet-EOU ONNX streaming recognizer. A selection the
        // app cannot honor is reported here, never faked.
        val parakeetSession: ParakeetEouSession?
        val speechToText: SpeechToText
        if (engine.family == SttEngineFamily.PARAKEET_EOU) {
            val built =
                try {
                    parakeetModelStore.state()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    reportSttUnavailable(
                        VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, "the Parakeet-EOU model could not be verified"),
                    )
                    return
                }
            if (built !is com.voicechat.agent.stt.parakeet.ParakeetEouModelState.Installed) {
                AppLog.voice(LogLevel.WARN) { "voice: parakeet model not installed state=${built::class.simpleName}" }
                reportSttUnavailable(
                    VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, "the Parakeet-EOU model is not installed"),
                )
                return
            }
            parakeetSession =
                try {
                    parakeetSessionFactory.create(engine, built.directory)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    AppLog.voice(LogLevel.ERROR, failure) { "voice: parakeet adapter failed to load" }
                    reportSttUnavailable(
                        VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, "the Parakeet-EOU model failed to load"),
                    )
                    return
                }
            speechToText = parakeetSession.speechToText
        } else {
            parakeetSession = null
            speechToText = MlKitSpeechToText(engine = engine, diagnostics = diagnostics, clock = clock)
        }
        val audioInput =
            audioInputFactory?.invoke()
                ?: MicrophoneAudioCapture.create(context = context, diagnostics = diagnostics, clock = clock)
        val textToSpeech: TextToSpeech? =
            try {
                OnDeviceTts.create(
                    context = context,
                    locale = selection.locale,
                    voiceId = selection.ttsVoiceId,
                    diagnostics = diagnostics,
                    clock = clock,
                )
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
        // M10/M-Parakeet: resolve the optional semantic detector once per session.
        // For Parakeet-EOU the model's own EOU head is the corroborator; otherwise
        // the M10 Smart Turn detector applies. A missing/failed detector returns
        // null and the bounded VAD-only policy applies; it is never fabricated,
        // and it is closed with the session.
        val semanticDetector = parakeetSession?.turnDetector ?: resolveSemanticDetector()
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
                // The session owns its STT adapter and runtime; close it once here.
                parakeetSession?.close()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                // Release best-effort; the session already ended.
            }
            if (parakeetSession == null) {
                try {
                    speechToText.close()
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (ignored: Throwable) {
                    // Release best-effort; the session already ended.
                }
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

    /**
     * The runtime availability for every catalog mode, both families, with the
     * default engine (Parakeet-EOU) first. Only the ML Kit modes go through
     * [MlKitSttStatus.check]; Parakeet-EOU is app-managed and comes from its
     * verified model store.
     */
    private suspend fun availableStt(locale: Locale): List<SttAvailability> =
        listOf(ParakeetEouSttCatalog.availability(parakeetModelStore.state(), locale)) +
            SttEngines.mlKitCatalog(locale).map { MlKitSttStatus.check(it) }

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
