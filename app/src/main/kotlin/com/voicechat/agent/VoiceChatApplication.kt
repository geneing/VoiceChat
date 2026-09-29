package com.voicechat.agent

import android.app.Application
import com.voicechat.agent.log.AndroidLogging

/**
 * Application entry point.
 *
 * Its only job is to install developer logging at the app boundary before any
 * activity or component runs ([AndroidLogging.install]). Enablement comes from
 * `BuildConfig.DEBUG`, so this is a no-op in release (`docs/logging.md`).
 */
class VoiceChatApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidLogging.install()
    }
}
