package com.voicechat.agent.credentials

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.fake.FakeCredentialCipher
import com.voicechat.agent.fake.FileCredentialBlobStore
import com.voicechat.agent.fake.InMemoryCredentialBlobStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M13 acceptance for the credential store: store / replace / remove, a
 * process-restart read through a fresh instance, at-rest encryption, and honest
 * failure states. No Android and no network are involved.
 */
class CredentialStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val providerId = ProviderId("openai")
    private val secret = "sk-live-DO-NOT-LEAK-0123456789"

    private fun store(
        cipher: CredentialCipher = FakeCredentialCipher(),
        blobs: CredentialBlobStore = InMemoryCredentialBlobStore(),
    ) = EncryptedCredentialStore(cipher, blobs, Dispatchers.Unconfined)

    @Test
    fun storeThenStatusAndLoad() =
        runBlocking {
            val store = store()
            val credential = Credential(providerId, CredentialKind.API_KEY, secret)

            assertEquals(CredentialStoreOutcome.Success, store.store(credential))
            assertEquals(CredentialStatus.Stored(providerId, CredentialKind.API_KEY), store.status(providerId))
            assertEquals(credential, store.load(providerId))
        }

    @Test
    fun replaceOverwritesTheStoredValue() =
        runBlocking {
            val store = store()
            store.store(Credential(providerId, CredentialKind.API_KEY, secret))
            val replacement = "sk-live-REPLACED-0987654321"

            assertEquals(CredentialStoreOutcome.Success, store.store(Credential(providerId, CredentialKind.API_KEY, replacement)))

            assertEquals(replacement, store.load(providerId)?.secret)
        }

    @Test
    fun removeDeletesTheCredential() =
        runBlocking {
            val store = store()
            store.store(Credential(providerId, CredentialKind.API_KEY, secret))

            assertEquals(CredentialStoreOutcome.Success, store.remove(providerId))
            assertEquals(CredentialStatus.NotStored, store.status(providerId))
            assertNull(store.load(providerId))
        }

    @Test
    fun removingWhenNothingIsStoredIsNotAnError() =
        runBlocking {
            assertEquals(CredentialStoreOutcome.NotStored, store().remove(providerId))
        }

    @Test
    fun aFreshStoreInstanceOverTheSameBlobsReadsTheStoredValue() =
        runBlocking {
            val blobs = InMemoryCredentialBlobStore()
            store(blobs = blobs).store(Credential(providerId, CredentialKind.API_KEY, secret))

            // A new store object over the same "disk" models a new process.
            val restarted = store(blobs = blobs)

            assertEquals(CredentialStatus.Stored(providerId, CredentialKind.API_KEY), restarted.status(providerId))
            assertEquals(secret, restarted.load(providerId)?.secret)
        }

    @Test
    fun storedValueSurvivesAProcessRestartOnDisk() =
        runBlocking {
            val directory: File = temporaryFolder.newFolder("credentials")
            store(blobs = FileCredentialBlobStore(directory))
                .store(Credential(providerId, CredentialKind.API_KEY, secret))

            // A new blob store and a new cipher over the same directory is the JVM
            // equivalent of relaunching the process with the same KeyStore key.
            val restarted = store(cipher = FakeCredentialCipher(), blobs = FileCredentialBlobStore(directory))

            assertEquals(CredentialStatus.Stored(providerId, CredentialKind.API_KEY), restarted.status(providerId))
            assertEquals(secret, restarted.load(providerId)?.secret)
        }

    @Test
    fun plaintextIsNeverWrittenToTheBlobStore() =
        runBlocking {
            val blobs = InMemoryCredentialBlobStore()
            store(blobs = blobs).store(Credential(providerId, CredentialKind.API_KEY, secret))

            val onDisk = blobs.read("credential:${providerId.value}")
            assertTrue("a record was expected", onDisk != null && onDisk.isNotEmpty())
            assertFalse(
                "the plaintext secret must never appear at rest",
                String(onDisk!!, Charsets.ISO_8859_1).contains(secret),
            )
            assertFalse(onDisk.contentEquals(secret.toByteArray()))
        }

    @Test
    fun anEncryptFailureIsATypedStoreFailure() =
        runBlocking {
            val store = store(cipher = FakeCredentialCipher(failOnEncrypt = true))

            val outcome = store.store(Credential(providerId, CredentialKind.API_KEY, secret))

            assertEquals(
                CredentialStoreOutcome.Failed(
                    com.voicechat.agent.domain
                        .VoiceAgentError(ErrorCode.CREDENTIAL_STORAGE_FAILED),
                ),
                outcome,
            )
            assertEquals(CredentialStatus.NotStored, store.status(providerId))
        }

    @Test
    fun aLostKeystoreKeyIsReportedAsNotStoredAndLoadReturnsNull() =
        runBlocking {
            val blobs = InMemoryCredentialBlobStore()
            store(blobs = blobs).store(Credential(providerId, CredentialKind.API_KEY, secret))

            // A new process whose KeyStore key is gone cannot decrypt the blob.
            val afterRestore = store(cipher = FakeCredentialCipher(failOnDecrypt = true), blobs = blobs)

            assertEquals(CredentialStatus.NotStored, afterRestore.status(providerId))
            assertNull(afterRestore.load(providerId))
            // The undecryptable blob is left in place rather than silently deleted.
            assertTrue(blobs.contains("credential:${providerId.value}"))
        }

    @Test
    fun thereIsNoPasswordCredentialKind() {
        assertTrue(
            "the app must never collect or persist a provider password",
            CredentialKind.entries.none { it.name.contains("PASSWORD", ignoreCase = true) },
        )
    }
}
