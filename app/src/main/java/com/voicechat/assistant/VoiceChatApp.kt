package com.voicechat.assistant

import android.app.Application
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.PromptCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class VoiceChatApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        // Read the prompt asset before any screen asks for a prompt; it is a
        // single small file and the defaults of AppSettings come from it.
        PromptCatalog.attach(this)
        container = AppContainer(this)
        appScope.launch {
            container.settings.applyPromptMigration()
            container.settings.applySearchDefaultMigration()
            container.settings.applyHandoffLogMigration()
            container.settings.applyMemoryLogMigration()
            container.settings.applyConversationDefaultsMigration()
        }
    }
}
