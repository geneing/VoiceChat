package com.voicechat.agent

import android.content.Context
import com.voicechat.agent.credentials.AndroidKeystoreCredentialStore
import com.voicechat.agent.local.AndroidLocalModelFileStore
import com.voicechat.agent.local.CatalogLocalLanguageModelFactory
import com.voicechat.agent.local.LocalModelInstaller
import com.voicechat.agent.persistence.ConversationPersistence
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.RegisteredProviderLanguageModelFactory
import com.voicechat.agent.remote.OkHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.settings.PreferencesSettingsStore
import com.voicechat.agent.turn.OnnxSmartTurnEngine
import com.voicechat.agent.turn.SmartTurnDetectorFactory
import com.voicechat.agent.turn.SmartTurnEngineFactory
import com.voicechat.agent.turn.SmartTurnModelStore
import com.voicechat.agent.ui.AndroidSettingsCapabilityProvider
import com.voicechat.agent.ui.ConversationDefaults
import com.voicechat.agent.ui.settingsViewModelFactory
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

    val settingsFactory by lazy {
        settingsViewModelFactory(
            store = settingsStore,
            registry = registry,
            credentials = credentials,
            capabilityProvider = AndroidSettingsCapabilityProvider(appContext),
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

    /** One voice session factory; resolves the persisted STT/TTS selection per session. */
    val voiceFactory by lazy {
        VoiceSessionAssembly.platformFactory(
            context = appContext,
            repository = repository,
            fallbackLanguageModel = languageModel,
            turnCompletion = smartTurnDetectorProvider,
            selectionProvider = {
                val settings = settingsStore.observe().first()
                VoiceRuntimeSelection(
                    sttMode = settings.sttMode,
                    locale = settings.locale(),
                    ttsVoiceId = settings.ttsVoiceId,
                )
            },
        )
    }

    /**
     * Releases process-scoped resources. Idempotent and best-effort; called from
     * [VoiceChatApplication.onTerminate] (emulator only) and from tests. A real
     * process death releases everything without running this.
     */
    fun close() {
        runBlocking { transport.close() }
    }
}
