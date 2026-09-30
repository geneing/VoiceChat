package com.voicechat.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.voicechat.agent.credentials.AndroidKeystoreCredentialStore
import com.voicechat.agent.persistence.ConversationPersistence
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.settings.PreferencesSettingsStore
import com.voicechat.agent.ui.AndroidSettingsCapabilityProvider
import com.voicechat.agent.ui.ConversationDefaults
import com.voicechat.agent.ui.VoiceAgentRoot
import com.voicechat.agent.ui.settingsViewModelFactory
import com.voicechat.agent.ui.theme.VoiceAgentTheme

/**
 * Single activity entry point.
 *
 * Android lifecycle and permission handling live at the app boundary
 * ([AGENTS.md](AGENTS.md)); the Compose tree and its state holders live in
 * [VoiceAgentRoot] and the M22 settings ViewModel. This builds only the
 * app-private conversation repository, the (currently unconfigured) language
 * model, the DataStore-backed settings store, the provider capability registry,
 * the Keystore-backed credential store, and the runtime capability reader — no
 * provider SDK is constructed here.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAgentTheme {
                val repository = remember { ConversationPersistence.create(applicationContext) }
                val languageModel = remember { ConversationDefaults.languageModel() }
                val settingsFactory =
                    remember {
                        settingsViewModelFactory(
                            store = PreferencesSettingsStore.create(applicationContext),
                            registry = ProviderCapabilityRegistry.verifiedDefaults(),
                            credentials = AndroidKeystoreCredentialStore.create(applicationContext),
                            capabilityProvider = AndroidSettingsCapabilityProvider(applicationContext),
                        )
                    }
                VoiceAgentRoot(
                    repository = repository,
                    languageModel = languageModel,
                    settingsFactory = settingsFactory,
                )
            }
        }
    }
}
