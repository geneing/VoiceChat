package com.voicechat.agent.providers.openai

import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.log.RecordingLogSink
import com.voicechat.agent.providers.CredentialValidationResult
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.remote.scriptedResponse
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M14 acceptance for the minimal OpenAI credential check: it uses `GET /v1/models`,
 * maps 401/403 to an authentication rejection, keeps a transient failure typed,
 * and never echoes the credential (R-0073).
 */
class OpenAiCredentialValidatorTest {
    private val secret = "sk-live-DO-NOT-LEAK-0123456789"
    private val provider = ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENAI)!!
    private val destination = OpenAiLanguageModel.defaultDestination(provider)
    private val credential = Credential(KnownProviders.OPENAI, CredentialKind.API_KEY, secret)

    @After
    fun tearDown() {
        AppLog.reset()
    }

    @Test
    fun aSuccessfulModelsCheckIsValidAndUsesTheDocumentedEndpoint() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 200, body = "{\"data\":[]}") }

            val result = OpenAiCredentialValidator(RemoteTransport(engine)).validate(provider, destination, credential)

            assertEquals(CredentialValidationResult.Valid, result)
            val sent = engine.requests.single()
            assertEquals("https://api.openai.com/v1/models", sent.url)
            assertEquals("Bearer $secret", sent.headers.first { it.name == "Authorization" }.value)
        }

    @Test
    fun a401Or403IsAnAuthenticationRejection() =
        runTest {
            listOf(401, 403).forEach { status ->
                val engine = FakeHttpStreamingEngine { scriptedResponse(status = status, body = "{}") }

                val result = OpenAiCredentialValidator(RemoteTransport(engine)).validate(provider, destination, credential)

                assertEquals(CredentialValidationResult.authenticationRejected(), result)
            }
        }

    @Test
    fun aServerErrorKeepsItsTypedCode() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 500, body = "{}") }

            val result = OpenAiCredentialValidator(RemoteTransport(engine)).validate(provider, destination, credential)

            assertTrue(result is CredentialValidationResult.Invalid)
            assertEquals(ErrorCode.LLM_UNAVAILABLE, (result as CredentialValidationResult.Invalid).error.code)
        }

    @Test
    fun aTransportFailureIsATypedFailureNotARejection() =
        runTest {
            val engine =
                FakeHttpStreamingEngine {
                    flow { throw VoiceAgentException(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED)) }
                }

            val result = OpenAiCredentialValidator(RemoteTransport(engine)).validate(provider, destination, credential)

            assertTrue(result is CredentialValidationResult.Failed)
            assertEquals(ErrorCode.LLM_NETWORK_FAILED, (result as CredentialValidationResult.Failed).error.code)
        }

    @Test
    fun theCredentialNeverAppearsInTheResultOrTheLog() =
        runTest {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 401, body = "{}") }

            val result = OpenAiCredentialValidator(RemoteTransport(engine)).validate(provider, destination, credential)

            assertFalse(result.toString().contains(secret))
            assertFalse(sink.messages.any { it.contains(secret) })
        }
}
