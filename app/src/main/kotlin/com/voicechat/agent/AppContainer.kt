package com.voicechat.agent

import android.content.Context
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.credentials.AndroidKeystoreCredentialStore
import com.voicechat.agent.diagnostics.AppDiagnosticsRuntime
import com.voicechat.agent.diagnostics.InMemoryTraceStore
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.local.AndroidLocalModelFileStore
import com.voicechat.agent.local.CatalogLocalLanguageModelFactory
import com.voicechat.agent.local.LocalModelInstaller
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.persistence.ConversationPersistence
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.RegisteredProviderLanguageModelFactory
import com.voicechat.agent.providers.opencodego.OpenCodeGoModelCatalog
import com.voicechat.agent.remote.OkHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.settings.PreferencesSettingsStore
import com.voicechat.agent.tts.OnDeviceTts
import com.voicechat.agent.turn.OnnxSmartTurnEngine
import com.voicechat.agent.turn.SmartTurnDetectorFactory
import com.voicechat.agent.turn.SmartTurnEngineFactory
import com.voicechat.agent.turn.SmartTurnModelStore
import com.voicechat.agent.ui.AndroidSettingsCapabilityProvider
import com.voicechat.agent.ui.ConversationDefaults
import com.voicechat.agent.ui.DefaultSmartTurnModelInstaller
import com.voicechat.agent.ui.ProviderTurnResolver
import com.voicechat.agent.ui.settingsViewModelFactory
import com.voicechat.agent.voice.OrchestratingVoiceAssistant
import com.voicechat.agent.voice.VoiceAssistant
import com.voicechat.agent.voice.VoiceRuntimeSelection
import com.voicechat.agent.voice.VoiceSessionAssembly
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The app-scoped composition root (M26).
 *
 * Previously the Android side built the Room repository, DataStore store, OkHttp
 * transport, provider factory, and voice factory inside `MainActivity`'s
 * composition using `remember`. That created a fresh database client and HTTP
 * engine on every activity recreation, and the long-lived OkHttp executor had no
 * explicit owner to close (CODE_REVIEW P2, R-0164).
 *
 * This container is owned by the [Application], so there is exactly one of each
 * per process, and [close] is the single shutdown point. Everything is `lazy`:
 * a component is only built when first used, so tests and the debug credential
 * importer never construct resources they do not need.
 *
 * No provider SDK, secret, or credential value is built here — only app-private
 * storage, the shared transport, and the registry-driven factories.
 */
class AppContainer(
    context: Context,
    /**
     * Debug-only override for the per-session microphone source. Production passes
     * `null` (the microphone is used); the debug application injects a file-backed
     * source so the full voice loop can be exercised from a WAV. See
     * `docs/android-speech-recognition.md`.
     */
    private val voiceAudioInputFactory: (() -> AudioInput)? = null,
) {
    private val appContext: Context = context.applicationContext

    val repository by lazy { ConversationPersistence.create(appContext) }

    val settingsStore by lazy { PreferencesSettingsStore.create(appContext) }

    val registry by lazy { ProviderCapabilityRegistry.verifiedDefaults() }

    val credentials by lazy { AndroidKeystoreCredentialStore.create(appContext) }

    /** The one shared HTTP engine/transport; closed in [close]. */
    val transport by lazy { RemoteTransport(OkHttpStreamingEngine()) }

    val providerFactory by lazy { RegisteredProviderLanguageModelFactory(registry, credentials, transport) }

    val localModelFactory by lazy {
        CatalogLocalLanguageModelFactory(
            installer = LocalModelInstaller(AndroidLocalModelFileStore(appContext)),
        )
    }

    val languageModel by lazy { ConversationDefaults.languageModel() }

    private val diagnosticsRuntimeDelegate = lazy { AppDiagnosticsRuntime() }

    /** Recent content-free traces retained in memory for developer inspection. */
    val traceStore: InMemoryTraceStore get() = diagnosticsRuntimeDelegate.value.traceStore

    internal val diagnosticsSink get() = diagnosticsRuntimeDelegate.value.sink

    val settingsFactory by lazy {
        settingsViewModelFactory(
            store = settingsStore,
            registry = registry,
            credentials = credentials,
            capabilityProvider = AndroidSettingsCapabilityProvider(appContext),
            // Per-model reasoning for OpenCode Go (R-0065): the picker intersects
            // this with the provider-level row, so only a level the selected model
            // was measured to take is offered. Every other provider answers null,
            // exactly as the empty catalog did.
            modelCatalog = OpenCodeGoModelCatalog.INSTANCE,
            smartTurnInstaller = DefaultSmartTurnModelInstaller(File(appContext.filesDir, SmartTurnModelStore.DIRECTORY_NAME)),
        )
    }

    val settingsFlow by lazy { settingsStore.observe() }

    /** Smart Turn is opt-in and default-off; the flag is read at session start. */
    val smartTurnDetectorProvider by lazy {
        SmartTurnDetectorFactory(
            store = SmartTurnModelStore(File(appContext.filesDir, SmartTurnModelStore.DIRECTORY_NAME)),
            enabled = { settingsStore.observe().first().smartTurnEnabled },
            engineFactory = SmartTurnEngineFactory { file -> OnnxSmartTurnEngine.load(file) },
        )
    }

    /** One voice session factory; resolves the persisted STT and LLM selection per session. */
    val voiceFactory by lazy {
        VoiceSessionAssembly.platformFactory(
            context = appContext,
            repository = repository,
            diagnostics = diagnosticsSink,
            turnCompletion = smartTurnDetectorProvider,
            audioInputFactory = voiceAudioInputFactory,
            selectionProvider = {
                val settings = settingsStore.observe().first()
                VoiceRuntimeSelection(
                    sttMode = settings.sttMode,
                    locale = settings.locale(),
                )
            },
            assistantProvider = { conversation -> assistantFor(conversation) },
        )
    }

    /**
     * Builds the LLM + TTS reply path for one voice session, or `null` when no
     * provider/model is configured.
     *
     * The selection is resolved from the persisted M22 settings through the same
     * [ProviderTurnResolver] the text path uses, so a voice reply and a typed
     * reply run the same provider, model, and reasoning level — and a voice reply
     * is never silently generated by a different model than the one on screen.
     * The TTS engine is created per session so it follows the current locale and
     * embedded voice, and the assistant closes it when the session ends.
     */
    private suspend fun assistantFor(conversation: Conversation): VoiceAssistant? {
        val settings = settingsStore.observe().first()
        val active =
            ProviderTurnResolver.resolve(
                settings = settings,
                conversationId = conversation.id,
                registry = registry,
                factory = providerFactory,
                localFactory = localModelFactory,
            )
        if (!active.configured) {
            AppLog.w { "voice: no provider/model configured; the session stays capture-only" }
            return null
        }
        val speech =
            OnDeviceTts.create(
                context = appContext,
                locale = settings.locale(),
                voiceId = settings.ttsVoiceId,
                engineId = settings.ttsEngineId,
                diagnostics = diagnosticsSink,
            )
        return OrchestratingVoiceAssistant(
            orchestrator =
                TurnOrchestrator(
                    repository = repository,
                    languageModel = active.languageModel,
                    textToSpeech = speech,
                    diagnostics = diagnosticsSink,
                ),
            selection = active.selection,
            reasoning = active.reasoning,
            languageModel = active.languageModel,
            textToSpeech = speech,
        )
    }

    /**
     * Releases process-scoped resources. Idempotent and best-effort; called from
     * [VoiceChatApplication.onTerminate] (emulator only) and from tests. A real
     * process death releases everything without running this.
     */
    fun close() {
        runBlocking {
            if (diagnosticsRuntimeDelegate.isInitialized()) diagnosticsRuntimeDelegate.value.close()
            transport.close()
        }
    }
}
