package com.voicechat.agent.debug

import android.content.Context
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.CredentialStore
import com.voicechat.agent.credentials.CredentialStoreOutcome
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.providers.KnownProviders
import java.io.File

/**
 * Debug-only import of a developer-supplied credential file into the
 * AndroidKeyStore-backed [CredentialStore].
 *
 * `scripts/push-credentials.sh` writes a small `NAME=value` file into
 * app-private storage at `files/debug-credentials/opencode-go.key`. On launch the
 * debug application reads it, stores the value through the **same** [CredentialStore]
 * path the Settings UI uses (so it is AES/GCM-encrypted by the AndroidKeyStore
 * key), and deletes the plaintext file. The value is never logged, and this file
 * is never compiled into a release build.
 *
 * The importer is deliberately forgiving: a missing file is a no-op, and an
 * unparseable file is removed rather than left as plaintext at rest.
 */
internal object DebugCredentialImport {
    /** App-private subdirectory the push script writes into (keep in sync). */
    const val DIRECTORY_NAME: String = "debug-credentials"

    /** File name the push script writes (keep in sync). */
    const val FILE_NAME: String = "opencode-go.key"

    /** The entry that maps to the OpenCode Go provider. */
    const val OPENCODE_API_KEY_ENTRY: String = "OPENCODE_API_KEY"

    /** Reads the pushed file (if present) and imports its known entries. */
    suspend fun importIfPresent(
        context: Context,
        store: CredentialStore,
    ) {
        val file = File(File(context.filesDir, DIRECTORY_NAME), FILE_NAME)
        if (!file.isFile) return

        val text = runCatching { file.readText() }.getOrNull()
        if (text == null) {
            AppLog.w { "debug-credentials: could not read $FILE_NAME" }
            return
        }

        val secret = parseEntry(text, OPENCODE_API_KEY_ENTRY)
        // Remove the plaintext as soon as it has been read, whatever the outcome.
        file.delete()

        if (secret == null) {
            AppLog.w { "debug-credentials: $FILE_NAME had no $OPENCODE_API_KEY_ENTRY entry; nothing imported" }
            return
        }

        when (store.store(Credential(KnownProviders.OPENCODE_GO, CredentialKind.API_KEY, secret))) {
            CredentialStoreOutcome.Success -> {
                AppLog.i { "debug-credentials: imported OpenCode Go credential from $FILE_NAME" }
            }

            else -> {
                AppLog.w { "debug-credentials: OpenCode Go import failed; re-run scripts/push-credentials.sh" }
            }
        }
    }

    /**
     * Returns the value of the `NAME=value` entry [name] (first match), or `null`
     * when it is absent or blank. Blank lines and `#` comments are ignored; a
     * value may contain `=`.
     */
    fun parseEntry(
        text: String,
        name: String,
    ): String? =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    line.substring(0, separator).trim() to line.substring(separator + 1).trim()
                }
            }.firstOrNull { (key, value) -> key == name && value.isNotEmpty() }
            ?.second
}
