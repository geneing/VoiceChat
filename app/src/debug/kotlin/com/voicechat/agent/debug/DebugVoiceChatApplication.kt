package com.voicechat.agent.debug

import com.voicechat.agent.VoiceChatApplication
import com.voicechat.agent.credentials.AndroidKeystoreCredentialStore
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
        val store = AndroidKeystoreCredentialStore.create(this)
        debugScope.launch { DebugCredentialImport.importIfPresent(this@DebugVoiceChatApplication, store) }
    }
}
