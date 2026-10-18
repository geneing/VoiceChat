package com.voicechat.agent.turn

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Computes a content hash for model integrity verification.
 *
 * It is an interface so the JVM tests can supply a deterministic fake and never
 * read a real 11 MB artifact, and so a streaming/digest implementation can be
 * swapped without touching the installer logic.
 */
fun interface ContentHasher {
    /** @return the lowercase hex SHA-256 of [file], or `null` if it cannot be read. */
    fun sha256(file: File): String?

    /**
     * @return the lowercase hex SHA-256 of [bytes], or `null` when [bytes] is empty.
     *
     * A bundled model lives in the APK's assets rather than in a file, so the
     * same identity check has to work on bytes. The default implementation is
     * the streaming one, so an implementation only has to provide the file form.
     */
    fun sha256(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(bytes)
        return digest.digest().toHex()
    }
}

/** Lowercase hex, the form every pinned checksum in this project is written in. */
internal fun ByteArray.toHex(): String = joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

/** [ContentHasher] backed by `MessageDigest`, streamed so memory stays bounded. */
object Sha256ContentHasher : ContentHasher {
    private const val BUFFER_BYTES = 64 * 1024

    override fun sha256(file: File): String? =
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_BYTES)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().toHex()
        } catch (failure: IOException) {
            null
        }
}
