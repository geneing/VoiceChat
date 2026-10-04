package com.voicechat.agent

import android.app.Application
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.log.AndroidLogging

/**
 * Application entry point.
 *
 * Its only job is to install developer logging at the app boundary before any
 * activity or component runs ([AndroidLogging.install]). Enablement comes from
 * `BuildConfig.DEBUG`, so this is a no-op in release (`docs/logging.md`).
 *
 * It is `open` so the debug source set can extend it with
 * `DebugVoiceChatApplication`, which additionally imports an adb-pushed
 * credential file into the Keystore-backed store. Release builds use this class
 * unchanged.
 */
open class VoiceChatApplication : Application() {
    private val containerDelegate = lazy { AppContainer(this, voiceAudioInputFactory()) }

    /**
     * The app-scoped dependency container (M26). Lazy so nothing is built until a
     * screen needs it; one instance per process.
     */
    val container: AppContainer by containerDelegate

    /**
     * Debug-only per-session audio-source override, read once when the container is
     * first built. The release build returns `null` (real microphone capture); the
     * debug [com.voicechat.agent.debug.DebugVoiceChatApplication] may return a
     * file-backed source when one has been staged.
     */
    protected open fun voiceAudioInputFactory(): (() -> AudioInput)? = null

    override fun onCreate() {
        super.onCreate()
        AndroidLogging.install()
    }

    override fun onTerminate() {
        if (containerDelegate.isInitialized()) containerDelegate.value.close()
        super.onTerminate()
    }
}
