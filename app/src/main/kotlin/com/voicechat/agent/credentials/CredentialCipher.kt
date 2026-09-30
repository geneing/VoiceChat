package com.voicechat.agent.credentials

/**
 * Encrypts and decrypts a credential's serialized bytes.
 *
 * The production implementation is Android-Keystore-backed
 * (`AndroidKeystoreCipher` in `AndroidKeystoreCredentialStore.kt`); the
 * interface keeps the [EncryptedCredentialStore] logic platform-free and JVM
 * testable with a deterministic fake.
 *
 * An implementation must never return or accept plaintext through any other
 * channel, and must use a fresh random nonce per encryption so the same value
 * encrypts differently every time.
 */
interface CredentialCipher {
    /** Encrypts [plaintext]; the result includes everything needed to decrypt it. */
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Decrypts a value produced by [encrypt]. */
    @Throws(CredentialCipherException::class)
    fun decrypt(ciphertext: ByteArray): ByteArray
}

/**
 * Signals that a credential could not be encrypted or decrypted.
 *
 * The message must stay generic: it must not embed the plaintext, the
 * ciphertext, or a raw platform exception message that could echo key material.
 * Callers treat a decrypt failure as "the stored value is unusable, re-enter it"
 * rather than as a hard crash.
 */
class CredentialCipherException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
