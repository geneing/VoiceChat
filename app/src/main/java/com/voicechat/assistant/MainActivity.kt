package com.voicechat.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.voicechat.assistant.ui.AppRoot
import com.voicechat.assistant.ui.theme.VoiceChatTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoiceChatTheme {
                AppRoot()
            }
        }
    }
}
