package com.voicechat.agent.debug

import com.voicechat.agent.VoiceChatApplication
import com.voicechat.agent.credentials.AndroidKeystoreCredentialStore
import com.voicechat.agent.log.AndroidLogging
import com.voicechat.agent.log.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Debug-only [VoiceChatApplication] (registered by `app/src/debug/AndroidManifest.xml`).
 *
 * It behaves exactly like the main application — developer logging is installed
 * by `super.onCreate()` — and additionally imports a developer-supplied
 * credential file from app-private storage into the AndroidKeyStore-backed store
 * on launch. The import runs off the main thread and deletes the plaintext file
 * once read, so no secret is left on disk. This class is not compiled into a
 * release build.
 */
class DebugVoiceChatApplication : VoiceChatApplication() {
    private val debugScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        // Debug builds log everything, including the per-chunk STT/decoder trace.
        AndroidLogging.install(minLevel = LogLevel.VERBOSE)
        val store = AndroidKeystoreCredentialStore.create(this)
        debugScope.launch { DebugCredentialImport.importIfPresent(this@DebugVoiceChatApplication, store) }
    }

    /**
     * Debug-only: when a WAV has been staged at
     * `files/debug-audio/input.wav`, run the voice loop from it instead of the
     * microphone so the full `VAD → Parakeet-EOU STT → LLM → TTS` path can be
     * driven without speaking. With nothing staged this returns `null` and the
     * real microphone is used, exactly like release.
     */
    override fun voiceAudioInputFactory(): (() -> com.voicechat.agent.contracts.AudioInput)? =
        DebugFileAudioInputFactory.factoryIfPresent(this)
}
