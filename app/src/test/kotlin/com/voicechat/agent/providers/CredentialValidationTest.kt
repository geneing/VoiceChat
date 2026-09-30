package com.voicechat.agent.providers

import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.log.RecordingLogSink
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M13 acceptance for optional minimal credential validation: it runs only where
 * the provider documents a check, an authentication failure is a typed result,
 * and neither the result nor the log ever contains the credential.
 */
class CredentialValidationTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val openAi = registry.capabilities(KnownProviders.OPENAI)!!
    private val go = registry.capabilities(KnownProviders.OPENCODE_GO)!!
    private val providerId = ProviderId("openai")
    private val secret = "sk-live-DO-NOT-LEAK-0123456789"
    private val destination =
        (ServerDestinationValidator.validate("https://api.openai.com/v1") as EndpointValidation.Valid).destination

    @After
    fun tearDown() {
        AppLog.reset()
    }

    private class ScriptedValidator(
        private val result: CredentialValidationResult,
    ) : CredentialValidator {
        var calls = 0

        override suspend fun validate(
            provider: ProviderCapabilities,
            destination: ServerDestination,
            credential: Credential,
        ): CredentialValidationResult {
            calls++
            return result
        }
    }

    @Test
    fun aProviderWithoutADocumentedCheckIsNeverValidated() =
        runBlocking {
            val validator = ScriptedValidator(CredentialValidationResult.Valid)
            val coordinator = CredentialValidationCoordinator(validator)

            val result = coordinator.validate(go, destination, Credential(providerId, CredentialKind.API_KEY, secret))

            assertTrue(result is CredentialValidationResult.Unsupported)
            assertEquals("the unsupported provider must not be validated", 0, validator.calls)
        }

    @Test
    fun anAuthenticationFailureIsATypedRejection() =
        runBlocking {
            val coordinator = CredentialValidationCoordinator(ScriptedValidator(CredentialValidationResult.authenticationRejected()))

            val result = coordinator.validate(openAi, destination, Credential(providerId, CredentialKind.API_KEY, secret))

            assertEquals(CredentialValidationResult.Invalid(VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED)), result)
        }

    @Test
    fun aTransientCheckFailureKeepsItsTypedCode() =
        runBlocking {
            val failure = CredentialValidationResult.Failed(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED))
            val coordinator = CredentialValidationCoordinator(ScriptedValidator(failure))

            assertEquals(failure, coordinator.validate(openAi, destination, Credential(providerId, CredentialKind.API_KEY, secret)))
        }

    @Test
    fun aSuccessfulCheckIsReportedAsValid() =
        runBlocking {
            val coordinator = CredentialValidationCoordinator(ScriptedValidator(CredentialValidationResult.Valid))

            assertEquals(
                CredentialValidationResult.Valid,
                coordinator.validate(openAi, destination, Credential(providerId, CredentialKind.API_KEY, secret)),
            )
        }

    @Test
    fun theValidationResultNeverCarriesTheCredential() =
        runBlocking {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            val coordinator = CredentialValidationCoordinator(ScriptedValidator(CredentialValidationResult.authenticationRejected()))

            val result = coordinator.validate(openAi, destination, Credential(providerId, CredentialKind.API_KEY, secret))

            assertFalse(result.toString().contains(secret))
            assertFalse(sink.messages.any { it.contains(secret) })
        }

    @Test
    fun theDefaultValidatorAndThePolicyAgree() {
        assertTrue(CredentialValidationPolicy.supports(openAi))
        assertFalse(CredentialValidationPolicy.supports(go))
    }
}
