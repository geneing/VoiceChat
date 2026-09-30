package com.voicechat.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voicechat.agent.ui.VoiceAgentRoot
import com.voicechat.agent.ui.theme.VoiceAgentTheme

/**
 * Single activity entry point.
 *
 * Android lifecycle and permission handling live at the app boundary
 * ([AGENTS.md](AGENTS.md)); the Compose tree and its state holders live in
 * [VoiceAgentRoot] and the M22 settings ViewModel. The app-scoped dependencies —
 * the conversation repository, the DataStore settings store, the provider
 * capability registry, the Keystore-backed credential store, the shared remote
 * transport, the provider/local-model factories, the Smart Turn provider, and the
 * voice factory — are built and owned by [AppContainer] on the
 * [VoiceChatApplication], not by the activity (M26). Activity recreation
 * therefore reuses one repository/database/HTTP engine instead of creating new
 * ones, and [VoiceChatApplication.onTerminate] is the single shutdown point.
 *
 * No provider SDK is constructed here; the persisted selection drives which
 * adapter a sent turn uses (R-0103) and the destination/retention disclosure is
 * the same in Settings and the dialog (R-0097, R-0139).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as VoiceChatApplication).container
        setContent {
            VoiceAgentTheme {
                VoiceAgentRoot(
                    repository = container.repository,
                    languageModel = container.languageModel,
                    settingsFactory = container.settingsFactory,
                    settingsFlow = container.settingsFlow,
                    providerRegistry = container.registry,
                    providerFactory = container.providerFactory,
                    localModelFactory = container.localModelFactory,
                    voiceSessionFactory = container.voiceFactory,
                )
            }
        }
    }
}
