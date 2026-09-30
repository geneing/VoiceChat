package com.voicechat.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.voicechat.agent.credentials.AndroidKeystoreCredentialStore
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
import com.voicechat.agent.ui.VoiceAgentRoot
import com.voicechat.agent.ui.settingsViewModelFactory
import com.voicechat.agent.ui.theme.VoiceAgentTheme
import com.voicechat.agent.voice.VoiceSessionAssembly
import kotlinx.coroutines.flow.first
import java.io.File

/**
 * Single activity entry point.
 *
 * Android lifecycle and permission handling live at the app boundary
 * ([AGENTS.md](AGENTS.md)); the Compose tree and its state holders live in
 * [VoiceAgentRoot] and the M22 settings ViewModel. This builds only the
 * app-private conversation repository, the DataStore-backed settings store, the
 * provider capability registry, the Keystore-backed credential store, the shared
 * remote transport, the registry-driven provider factory, and the runtime
 * capability reader — no provider SDK is constructed here.
 *
 * M23 shares one settings store, registry, credential store, and transport
 * between the settings screen and the conversation turn path, so the persisted
 * selection drives which adapter a sent turn uses (R-0103) and the destination/
 * retention disclosure is the same in both places (R-0097, R-0139). The
 * placeholder `NotConfiguredLanguageModel` is still built, but the turn path uses
 * it only when no provider/model is selected or credentialed.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAgentTheme {
                val repository = remember { ConversationPersistence.create(applicationContext) }
                val settingsStore = remember { PreferencesSettingsStore.create(applicationContext) }
                val registry = remember { ProviderCapabilityRegistry.verifiedDefaults() }
                val credentials = remember { AndroidKeystoreCredentialStore.create(applicationContext) }
                val transport = remember { RemoteTransport(OkHttpStreamingEngine()) }
                val providerFactory =
                    remember { RegisteredProviderLanguageModelFactory(registry, credentials, transport) }
                val languageModel = remember { ConversationDefaults.languageModel() }
                val settingsFactory =
                    remember {
                        settingsViewModelFactory(
                            store = settingsStore,
                            registry = registry,
                            credentials = credentials,
                            capabilityProvider = AndroidSettingsCapabilityProvider(applicationContext),
                        )
                    }
                val settingsFlow = remember { settingsStore.observe() }
                // M10: Smart Turn is opt-in and default-off. The factory reads the
                // persisted flag at session start and only constructs the detector
                // when it is enabled *and* the app-private model is verified.
                val smartTurnDetectorProvider =
                    remember {
                        SmartTurnDetectorFactory(
                            store =
                                SmartTurnModelStore(
                                    File(applicationContext.filesDir, SmartTurnModelStore.DIRECTORY_NAME),
                                ),
                            enabled = { settingsStore.observe().first().smartTurnEnabled },
                            engineFactory = SmartTurnEngineFactory { file -> OnnxSmartTurnEngine.load(file) },
                        )
                    }
                val voiceFactory =
                    remember {
                        VoiceSessionAssembly.platformFactory(
                            context = applicationContext,
                            repository = repository,
                            fallbackLanguageModel = languageModel,
                            turnCompletion = smartTurnDetectorProvider,
                        )
                    }
                VoiceAgentRoot(
                    repository = repository,
                    languageModel = languageModel,
                    settingsFactory = settingsFactory,
                    settingsFlow = settingsFlow,
                    providerRegistry = registry,
                    providerFactory = providerFactory,
                    voiceSessionFactory = voiceFactory,
                )
            }
        }
    }
}
