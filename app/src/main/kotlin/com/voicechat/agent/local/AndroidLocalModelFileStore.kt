package com.voicechat.agent.local

import android.content.Context
import java.io.File

/**
 * App-private [LocalModelFileStore] over the app's private files directory (M20).
 *
 * Models live under `filesDir/local-models`, which needs no storage permission
 * and is removed with the app. Writes go to a `.partial` file first and are
 * renamed only after verification (see [LocalModelInstaller]); a rename that
 * cannot be a true atomic move falls back to a copy + delete so a partial file
 * is never left where a load could mistake it for installed.
 */
class AndroidLocalModelFileStore(
    context: Context,
) : LocalModelFileStore {
    private val directory: File = File(context.applicationContext.filesDir, DIRECTORY_NAME)

    override fun names(): List<String> = directory.list()?.toList().orEmpty()

    override fun size(name: String): Long? = safeFile(name)?.takeIf { it.isFile }?.length()

    override fun read(name: String): ByteArray? =
        safeFile(name)?.takeIf { it.isFile }?.let { file ->
            runCatching { file.readBytes() }.getOrNull()
        }

    override fun pathFor(name: String): String? = safeFile(name)?.takeIf { it.isFile }?.absolutePath

    override fun write(
        name: String,
        bytes: ByteArray,
    ) {
        directory.mkdirs()
        safeFile(name)?.writeBytes(bytes)
    }

    override fun rename(
        from: String,
        to: String,
    ): Boolean {
        val source = safeFile(from) ?: return false
        if (!source.isFile) return false
        val target = safeFile(to) ?: return false
        if (source.renameTo(target)) return true
        return runCatching {
            target.writeBytes(source.readBytes())
            source.delete()
            true
        }.getOrDefault(false)
    }

    override fun delete(name: String): Boolean = safeFile(name)?.delete() ?: false

    /** Resolves [name] inside the model directory, refusing any path traversal. */
    private fun safeFile(name: String): File? {
        if (name.isBlank() || name.contains('/') || name.contains('\\') || name.contains("..")) return null
        return File(directory, name)
    }

    companion object {
        /** App-private subdirectory for downloaded model bundles. */
        const val DIRECTORY_NAME: String = "local-models"
    }
}
