package com.voicechat.agent.providers.hermes

import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.providers.CredentialValidationResult
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ServerDestination
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.remote.scriptedResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M19 acceptance for the Hermes minimal credential check (`GET /v1/models`): the
 * documented auth mapping and that the credential is never echoed. No network.
 */
class HermesCredentialValidatorTest {
    private val secret = "hermes-secret-DO-NOT-LEAK"
    private val provider = ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.HERMES)!!
    private val destination = destination("https://hermes.example.com/v1")
    private val credential = Credential(KnownProviders.HERMES, CredentialKind.API_KEY, secret)

    @Test
    fun a200ResponseMeansTheCredentialIsAccepted() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 200, body = "{\"data\":[]}") }
            val validator = HermesCredentialValidator(RemoteTransport(engine))

            val result = validator.validate(provider, destination, credential)

            assertEquals(CredentialValidationResult.Valid, result)
            val sent = engine.requests.single()
            assertEquals("https://hermes.example.com/v1/models", sent.url)
            assertEquals("Bearer $secret", sent.headers.first { it.name == "Authorization" }.value)
        }

    @Test
    fun a401Or403IsAnAuthenticationRejection() =
        runTest {
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, rejectionCode(401))
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, rejectionCode(403))
        }

    @Test
    fun anotherStatusKeepsItsTypedCode() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 503, body = "") }
            val validator = HermesCredentialValidator(RemoteTransport(engine))

            val result = validator.validate(provider, destination, credential)

            assertEquals(ErrorCode.LLM_UNAVAILABLE, (result as CredentialValidationResult.Invalid).error.code)
        }

    @Test
    fun aTransportFailureIsATypedFailedResultNotARejection() =
        runTest {
            val engine =
                FakeHttpStreamingEngine {
                    throw VoiceAgentException(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED))
                }
            val validator = HermesCredentialValidator(RemoteTransport(engine))

            val result = validator.validate(provider, destination, credential)

            assertEquals(ErrorCode.LLM_NETWORK_FAILED, (result as CredentialValidationResult.Failed).error.code)
        }

    private suspend fun rejectionCode(status: Int): ErrorCode? {
        val engine = FakeHttpStreamingEngine { scriptedResponse(status = status, body = "{\"error\":{}}") }
        val result = HermesCredentialValidator(RemoteTransport(engine)).validate(provider, destination, credential)
        assertTrue(result is CredentialValidationResult.Invalid)
        return (result as CredentialValidationResult.Invalid).error.code
    }

    private companion object {
        fun destination(raw: String): ServerDestination =
            when (val result = HermesServerAddress.config(raw)) {
                is HermesServerConfigResult.Valid -> result.config.destination
                is HermesServerConfigResult.Invalid -> error("test destination was invalid: ${result.error.code}")
            }
    }
}
