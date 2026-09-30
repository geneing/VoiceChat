package com.voicechat.agent.credentials

/**
 * A tiny keyed byte store for already-encrypted credential blobs.
 *
 * This is the "disk" half of [EncryptedCredentialStore]. On device it is backed
 * by app-private `SharedPreferences` (`SharedPreferencesCredentialBlobStore`),
 * which is excluded from backup by `android:allowBackup="false"` and the M05
 * data-extraction rules. Values written here are always ciphertext; the store
 * never asks this interface to hold a plaintext secret.
 *
 * The interface is platform-free so the store can be exercised on the JVM with
 * an in-memory or file-backed fake, including a "new instance, same disk" run
 * that models a process restart.
 */
interface CredentialBlobStore {
    /** Returns the stored bytes for [key], or `null` when absent. */
    fun read(key: String): ByteArray?

    /** Writes (replacing) [value] under [key]. Must be durable when it returns. */
    fun write(
        key: String,
        value: ByteArray,
    )

    /** Removes [key] if present. */
    fun delete(key: String)

    /** True when a value is stored under [key]. */
    fun contains(key: String): Boolean
}
