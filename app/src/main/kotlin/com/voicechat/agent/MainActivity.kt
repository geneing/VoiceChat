package com.voicechat.agent

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.remember
import com.voicechat.agent.persistence.ConversationPersistence
import com.voicechat.agent.ui.ConversationDefaults
import com.voicechat.agent.ui.VoiceAgentRoot
import com.voicechat.agent.ui.theme.VoiceAgentTheme

/**
 * Single activity entry point.
 *
 * Android lifecycle and permission handling live at the app boundary
 * ([AGENTS.md](AGENTS.md)); the Compose tree and its state holder live in
 * [VoiceAgentRoot]. This builds only the app-private conversation repository and
 * the (currently unconfigured) language model, with no provider or speech SDK.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAgentTheme {
                val repository = remember { ConversationPersistence.create(applicationContext) }
                val languageModel = remember { ConversationDefaults.languageModel() }
                VoiceAgentRoot(
                    repository = repository,
                    languageModel = languageModel,
                )
            }
        }
    }
}
