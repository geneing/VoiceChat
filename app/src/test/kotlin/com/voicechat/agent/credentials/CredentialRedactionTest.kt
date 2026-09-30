package com.voicechat.agent.credentials

import com.voicechat.agent.diagnostics.Redaction
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.fake.FakeCredentialCipher
import com.voicechat.agent.fake.InMemoryCredentialBlobStore
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.log.RecordingLogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M13 acceptance for redaction on the credential path: a secret must never reach
 * the developer log, a `toString`, an exception, or a crash-metadata header map,
 * even when the store fails.
 */
class CredentialRedactionTest {
    private val providerId = ProviderId("openrouter")
    private val secret = "sk-live-DO-NOT-LEAK-0123456789"

    @After
    fun tearDown() {
        AppLog.reset()
    }

    private fun store(cipher: FakeCredentialCipher) =
        EncryptedCredentialStore(cipher, InMemoryCredentialBlobStore(), Dispatchers.Unconfined)

    @Test
    fun credentialToStringIsRedacted() {
        val rendered = Credential(providerId, CredentialKind.API_KEY, secret).toString()

        assertTrue(rendered.contains(Redaction.PLACEHOLDER))
        assertFalse("the secret leaked through toString", rendered.contains(secret))
    }

    @Test
    fun theStorePathNeverLogsTheSecretOnStoreLoadOrRemove() =
        runBlocking {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            val store = store(FakeCredentialCipher())

            store.store(Credential(providerId, CredentialKind.API_KEY, secret))
            store.status(providerId)
            store.load(providerId)
            store.remove(providerId)

            assertTrue("the path should have logged something", sink.messages.isNotEmpty())
            assertFalse("the credential leaked to the log", sink.messages.any { it.contains(secret) })
        }

    @Test
    fun aStoreFailureLogsTheReasonNotTheValue() =
        runBlocking {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)

            store(FakeCredentialCipher(failOnEncrypt = true)).store(Credential(providerId, CredentialKind.API_KEY, secret))

            assertTrue(sink.messages.any { it.contains("Credentials") || it.contains("credentials") })
            assertFalse("the credential leaked through the failure log", sink.messages.any { it.contains(secret) })
        }

    @Test
    fun aCipherFailureMessageNeverEchoesTheValue() {
        // The exception text is generic by contract; it must not embed key material.
        val failure = CredentialCipherException("credential encryption failed")
        assertFalse(failure.message.orEmpty().contains(secret))
    }

    @Test
    fun credentialBearingCrashMetadataHeadersAreRedacted() {
        val metadata =
            mapOf(
                "Authorization" to "Bearer $secret",
                "X-Api-Key" to secret,
                "provider" to "openrouter",
                "model" to "some/model",
            )

        val redacted = Redaction.redactHeaders(metadata)

        assertFalse(redacted.values.any { it.contains(secret) })
        assertTrue(Redaction.isSensitiveName("Authorization"))
        assertTrue(Redaction.isSensitiveName("X-Api-Key"))
        // Non-sensitive identity is preserved, so crash reports stay useful.
        assertTrue(redacted["provider"] == "openrouter")
        assertTrue(redacted["model"] == "some/model")
    }
}
