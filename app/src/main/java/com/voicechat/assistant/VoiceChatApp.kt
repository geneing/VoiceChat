package com.voicechat.assistant

import android.app.Application
import com.voicechat.assistant.core.AppLog

class VoiceChatApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.attach(this)
        container = AppContainer(this)
    }
}
