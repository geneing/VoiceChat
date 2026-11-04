package com.voicechat.assistant

import android.app.Application
import com.voicechat.assistant.core.AppLog
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
        container = AppContainer(this)
        appScope.launch { container.settings.applySearchDefaultMigration() }
    }
}
