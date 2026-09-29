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
 * This stays deliberately thin: Android lifecycle and permission handling live at
 * the app boundary ([AGENTS.md](AGENTS.md)), and the Compose tree lives in
 * [VoiceAgentRoot] so later milestones can attach a state holder without touching
 * the activity.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceAgentTheme {
                VoiceAgentRoot()
            }
        }
    }
}
