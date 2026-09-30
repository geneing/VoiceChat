package com.voicechat.agent.fake

import com.voicechat.agent.credentials.CredentialBlobStore
import com.voicechat.agent.credentials.CredentialCipher
import com.voicechat.agent.credentials.CredentialCipherException
import java.io.File

/**
 * Deterministic, reversible [CredentialCipher] for JVM tests.
 *
 * The output is a marker byte followed by an XOR of the plaintext, so it is not
 * the plaintext (the at-rest leak test is meaningful) but is reproducible. It is
 * emphatically **not** secure; production uses the AndroidKeyStore AES/GCM
 * cipher.
 */
class FakeCredentialCipher(
    private val key: Byte = 0x5A,
    var failOnEncrypt: Boolean = false,
    var failOnDecrypt: Boolean = false,
) : CredentialCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        if (failOnEncrypt) throw CredentialCipherException("encrypt disabled for the test")
        return ByteArray(plaintext.size + 1) { index ->
            if (index == 0) MARKER else (plaintext[index - 1].toInt() xor key.toInt()).toByte()
        }
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (failOnDecrypt) throw CredentialCipherException("decrypt disabled for the test")
        require(ciphertext.isNotEmpty() && ciphertext[0] == MARKER) { "not a fake ciphertext" }
        return ByteArray(ciphertext.size - 1) { index -> (ciphertext[index + 1].toInt() xor key.toInt()).toByte() }
    }

    private companion object {
        const val MARKER: Byte = 0x7E
    }
}

/** In-memory [CredentialBlobStore] that can be shared to model one "disk". */
class InMemoryCredentialBlobStore(
    initial: Map<String, ByteArray> = emptyMap(),
) : CredentialBlobStore {
    private val entries = LinkedHashMap<String, ByteArray>()

    init {
        initial.forEach { (key, value) -> entries[key] = value.copyOf() }
    }

    override fun read(key: String): ByteArray? = entries[key]?.copyOf()

    override fun write(
        key: String,
        value: ByteArray,
    ) {
        entries[key] = value.copyOf()
    }

    override fun delete(key: String) {
        entries.remove(key)
    }

    override fun contains(key: String): Boolean = entries.containsKey(key)
}

/**
 * File-backed [CredentialBlobStore] that persists across store instances on the
 * JVM, so a test can model a process restart over real disk I/O.
 */
class FileCredentialBlobStore(
    private val directory: File,
) : CredentialBlobStore {
    override fun read(key: String): ByteArray? = fileFor(key).takeIf { it.isFile }?.readBytes()

    override fun write(
        key: String,
        value: ByteArray,
    ) {
        directory.mkdirs()
        fileFor(key).writeBytes(value)
    }

    override fun delete(key: String) {
        fileFor(key).delete()
    }

    override fun contains(key: String): Boolean = fileFor(key).isFile

    private fun fileFor(key: String): File = File(directory, key.replace(NON_FILENAME_CHARS, "_") + ".bin")

    private companion object {
        val NON_FILENAME_CHARS = Regex("[^A-Za-z0-9._-]")
    }
}
