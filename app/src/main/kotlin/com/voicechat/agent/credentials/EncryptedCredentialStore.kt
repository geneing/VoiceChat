package com.voicechat.agent.credentials

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Platform-free [CredentialStore] over an encrypted blob store.
 *
 * The plaintext secret is serialized with its [CredentialKind] and encrypted by
 * [cipher] before it ever reaches [blobs], so the "disk" never sees a plaintext
 * credential. The kind is kept outside the ciphertext (it is not secret) so
 * [status] can read it directly; [status] still decrypts once to confirm the
 * value is usable, so a blob whose key was lost is not reported as stored.
 *
 * Blocking KeyStore/preferences work happens on [dispatcher] (IO by default), so
 * no call runs on the main thread. All methods are safe to call on a fresh
 * instance: state lives only in [blobs] and in the cipher's key, which is why a
 * new instance reads the same credential after a process restart.
 */
class EncryptedCredentialStore(
    private val cipher: CredentialCipher,
    private val blobs: CredentialBlobStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CredentialStore {
    override suspend fun status(providerId: ProviderId): CredentialStatus =
        withContext(dispatcher) {
            val record = readRecord(providerId) ?: return@withContext CredentialStatus.NotStored
            val kind = CredentialRecord.kindOf(record) ?: return@withContext notStored("unreadable", providerId)
            // Decrypt to confirm the stored value is actually usable (the KeyStore
            // key can be lost after a restore); the plaintext is discarded.
            when (decodeSecret(record)) {
                null -> notStored("unusable", providerId)
                else -> CredentialStatus.Stored(providerId, kind)
            }
        }

    override suspend fun store(credential: Credential): CredentialStoreOutcome =
        withContext(dispatcher) {
            try {
                val plaintext = credential.secret.toByteArray(Charsets.UTF_8)
                val record = CredentialRecord.encode(credential.kind, cipher.encrypt(plaintext))
                blobs.write(key(credential.providerId), record)
                AppLog.i { "credentials: stored provider=${credential.providerId} kind=${credential.kind}" }
                CredentialStoreOutcome.Success
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // Only the exception type is logged; never the value or a raw message.
                AppLog.e { "credentials: store failed provider=${credential.providerId} reason=${failure.javaClass.simpleName}" }
                CredentialStoreOutcome.Failed(VoiceAgentError(ErrorCode.CREDENTIAL_STORAGE_FAILED))
            }
        }

    override suspend fun remove(providerId: ProviderId): CredentialStoreOutcome =
        withContext(dispatcher) {
            try {
                if (!blobs.contains(key(providerId))) {
                    CredentialStoreOutcome.NotStored
                } else {
                    blobs.delete(key(providerId))
                    AppLog.i { "credentials: removed provider=$providerId" }
                    CredentialStoreOutcome.Success
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                AppLog.e { "credentials: remove failed provider=$providerId reason=${failure.javaClass.simpleName}" }
                CredentialStoreOutcome.Failed(VoiceAgentError(ErrorCode.CREDENTIAL_STORAGE_FAILED))
            }
        }

    override suspend fun load(providerId: ProviderId): Credential? =
        withContext(dispatcher) {
            val record = readRecord(providerId) ?: return@withContext null
            val kind = CredentialRecord.kindOf(record) ?: return@withContext null
            val secret = decodeSecret(record) ?: return@withContext null
            Credential(providerId = providerId, kind = kind, secret = secret)
        }

    private fun decodeSecret(record: ByteArray): String? =
        try {
            val secret = cipher.decrypt(CredentialRecord.ciphertextOf(record)).toString(Charsets.UTF_8)
            secret.takeIf { it.isNotBlank() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // A lost/invalidated KeyStore key leaves an undecryptable blob; the
            // honest result is "no usable credential", never a wrong value.
            null
        }

    private fun notStored(
        reason: String,
        providerId: ProviderId,
    ): CredentialStatus {
        AppLog.w { "credentials: stored record for provider=$providerId is $reason; reporting not stored" }
        return CredentialStatus.NotStored
    }

    private fun readRecord(providerId: ProviderId): ByteArray? =
        try {
            blobs.read(key(providerId))?.takeIf { it.isNotEmpty() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            AppLog.w { "credentials: read failed provider=$providerId reason=${failure.javaClass.simpleName}" }
            null
        }

    private fun key(providerId: ProviderId): String = "credential:${providerId.value}"
}

/**
 * On-blob layout: one lead byte holding the [CredentialKind] ordinal (not
 * secret), followed by the ciphertext of the UTF-8 secret. Keeping the kind in
 * the clear lets [EncryptedCredentialStore.status] read it directly; the store
 * decrypts separately to confirm the value is usable.
 */
private object CredentialRecord {
    fun encode(
        kind: CredentialKind,
        ciphertext: ByteArray,
    ): ByteArray {
        val header = byteArrayOf(kind.ordinal.toByte())
        return header + ciphertext
    }

    fun kindOf(record: ByteArray): CredentialKind? {
        val ordinal = record.firstOrNull()?.toInt() ?: return null
        return CredentialKind.entries.getOrNull(ordinal)
    }

    fun ciphertextOf(record: ByteArray): ByteArray = if (record.size <= 1) ByteArray(0) else record.copyOfRange(1, record.size)
}
