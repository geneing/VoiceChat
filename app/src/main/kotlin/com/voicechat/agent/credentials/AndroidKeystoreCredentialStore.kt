package com.voicechat.agent.credentials

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * On-device [CredentialStore] backed by the AndroidKeyStore.
 *
 * This is the only file in the `credentials` package that touches `android.*`;
 * the store logic is the platform-free [EncryptedCredentialStore]. An AES-256
 * key lives in the AndroidKeyStore (`AndroidKeystoreCipher`) and encrypts the
 * credential with AES/GCM; the ciphertext blob lives in an app-private
 * `SharedPreferences` file (`SharedPreferencesCredentialBlobStore`). The
 * plaintext secret is therefore never written to disk, and the key is not
 * exportable.
 *
 * This follows the M00 decision (`docs/decisions.md` §1): use AndroidKeyStore
 * directly via `javax.crypto` and do **not** use the deprecated
 * `androidx.security:security-crypto` helpers. Details and the backup/recovery
 * policy are in `docs/credentials.md`.
 */
object AndroidKeystoreCredentialStore {
    /** App-private preferences file that holds the encrypted blobs. */
    const val PREFERENCES_NAME: String = "voicechat-credentials"

    /** Alias of the AndroidKeyStore AES key that protects every credential. */
    const val KEY_ALIAS: String = "voicechat.credentials.v1"

    /**
     * Builds the store for [context]. Safe to call repeatedly: the key and the
     * blobs live in the KeyStore and preferences, so a new instance reads the
     * same credential after a process restart.
     */
    fun create(context: Context): CredentialStore =
        EncryptedCredentialStore(
            cipher = AndroidKeystoreCipher(),
            blobs = SharedPreferencesCredentialBlobStore(context),
        )
}

/** AES/GCM cipher whose key is generated in and never leaves the AndroidKeyStore. */
internal class AndroidKeystoreCipher(
    private val alias: String = AndroidKeystoreCredentialStore.KEY_ALIAS,
) : CredentialCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray =
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, secretKey())
            val iv = cipher.iv
            val body = cipher.doFinal(plaintext)
            // Layout: [1-byte IV length][IV][ciphertext+tag]. The IV is random per
            // call (setRandomizedEncryptionRequired default), so equal secrets
            // produce different blobs.
            ByteArray(1 + iv.size + body.size).also { out ->
                out[0] = iv.size.toByte()
                iv.copyInto(out, destinationOffset = 1)
                body.copyInto(out, destinationOffset = 1 + iv.size)
            }
        } catch (failure: Exception) {
            throw CredentialCipherException("credential encryption failed", failure)
        }

    override fun decrypt(ciphertext: ByteArray): ByteArray =
        try {
            require(ciphertext.size > 1) { "credential blob is too short" }
            val ivLength = ciphertext[0].toInt() and 0xFF
            require(ivLength > 0 && 1 + ivLength <= ciphertext.size) { "credential blob has an invalid IV" }
            val iv = ciphertext.copyOfRange(1, 1 + ivLength)
            val body = ciphertext.copyOfRange(1 + ivLength, ciphertext.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(body)
        } catch (failure: Exception) {
            throw CredentialCipherException("credential decryption failed", failure)
        }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_BITS)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val AES_KEY_BITS = 256
    }
}

/**
 * [CredentialBlobStore] over app-private `SharedPreferences`.
 *
 * Values are Base64 of ciphertext only. `commit()` (not `apply()`) is used so a
 * write is durable before [CredentialStore.store] returns, which the restart
 * check depends on. The file is excluded from backup by `allowBackup="false"`
 * and `res/xml/data_extraction_rules.xml`.
 */
internal class SharedPreferencesCredentialBlobStore(
    context: Context,
) : CredentialBlobStore {
    private val preferences =
        context.applicationContext.getSharedPreferences(
            AndroidKeystoreCredentialStore.PREFERENCES_NAME,
            Context.MODE_PRIVATE,
        )

    override fun read(key: String): ByteArray? = preferences.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    override fun write(
        key: String,
        value: ByteArray,
    ) {
        preferences
            .edit()
            .putString(key, Base64.encodeToString(value, Base64.NO_WRAP))
            .commit()
    }

    override fun delete(key: String) {
        preferences.edit().remove(key).commit()
    }

    override fun contains(key: String): Boolean = preferences.contains(key)
}
