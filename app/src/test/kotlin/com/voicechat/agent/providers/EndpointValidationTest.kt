package com.voicechat.agent.providers

import com.voicechat.agent.domain.ErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M13 acceptance for destination validation: TLS is required for non-local
 * hosts, loopback may use `http`, credentials in a URL and QR-sourced endpoints
 * are refused, and a fixed provider's host cannot be substituted.
 */
class EndpointValidationTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val hermes = registry.capabilities(KnownProviders.HERMES)!!
    private val openAi = registry.capabilities(KnownProviders.OPENAI)!!

    private fun valid(
        raw: String,
        source: EndpointSource = EndpointSource.USER_ENTERED,
    ): ServerDestination = (ServerDestinationValidator.validate(raw, source) as EndpointValidation.Valid).destination

    private fun invalidCode(
        raw: String,
        source: EndpointSource = EndpointSource.USER_ENTERED,
    ): ErrorCode = (ServerDestinationValidator.validate(raw, source) as EndpointValidation.Invalid).error.code

    @Test
    fun aRemoteHttpsDestinationIsValidAndDisclosesItsHost() {
        val destination = valid("https://hermes.example.com/v1")

        assertEquals("https", destination.scheme)
        assertEquals("hermes.example.com", destination.host)
        assertEquals(443, destination.port)
        assertFalse(destination.isLoopback)
        assertEquals("https://hermes.example.com/v1", destination.disclosure())
    }

    @Test
    fun aRemoteHttpDestinationIsRefusedForBeingInsecure() {
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INSECURE, invalidCode("http://hermes.example.com"))
    }

    @Test
    fun loopbackMayUseHttpButNothingElseMay() {
        assertTrue(valid("http://127.0.0.1:8642").isLoopback)
        assertTrue(valid("http://localhost:8642").isLoopback)
        assertTrue(valid("http://[::1]:8642").isLoopback)
        // A private LAN address is still a network destination and needs TLS.
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INSECURE, invalidCode("http://192.168.1.10:8642"))
    }

    @Test
    fun aQrSourcedDestinationIsNeverTrusted() {
        assertEquals(
            ErrorCode.PROVIDER_ENDPOINT_INVALID,
            invalidCode("https://hermes.example.com", EndpointSource.QR_PAYLOAD),
        )
    }

    @Test
    fun malformedOrUnsupportedDestinationsAreInvalid() {
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, invalidCode(""))
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, invalidCode("   "))
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, invalidCode("not a url"))
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, invalidCode("ftp://hermes.example.com"))
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, invalidCode("https://"))
    }

    @Test
    fun credentialsInTheUrlAreRefused() {
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, invalidCode("https://user:pass@hermes.example.com"))
    }

    @Test
    fun theDefaultPortIsElidedInTheDisclosureAndACustomPortIsShown() {
        assertEquals("https://hermes.example.com", valid("https://hermes.example.com").disclosure())
        assertEquals("https://hermes.example.com:8443", valid("https://hermes.example.com:8443").disclosure())
        assertEquals("http://127.0.0.1:8642", valid("http://127.0.0.1:8642").disclosure())
    }

    @Test
    fun aConfigurableProviderRequiresAConfiguredAddress() {
        assertTrue(ProviderEndpointPolicy.destinationFor(hermes) is EndpointValidation.Invalid)
        assertTrue(ProviderEndpointPolicy.destinationFor(hermes, "https://hermes.example.com") is EndpointValidation.Valid)
        assertEquals(
            ErrorCode.PROVIDER_ENDPOINT_INSECURE,
            (ProviderEndpointPolicy.validateForProvider(hermes, "http://hermes.example.com") as EndpointValidation.Invalid).error.code,
        )
    }

    @Test
    fun aFixedProviderRefusesASubstitutedHost() {
        assertTrue(ProviderEndpointPolicy.validateForProvider(openAi, "https://api.openai.com/v1") is EndpointValidation.Valid)
        assertEquals(
            ErrorCode.PROVIDER_ENDPOINT_INVALID,
            (
                ProviderEndpointPolicy.validateForProvider(
                    openAi,
                    "https://attacker.example.com/v1",
                ) as EndpointValidation.Invalid
            ).error.code,
        )
    }

    @Test
    fun theDefaultDestinationOfAFixedProviderIsDisclosed() {
        val destination = ProviderEndpointPolicy.destinationFor(openAi)

        assertTrue(destination is EndpointValidation.Valid)
        assertEquals("https://api.openai.com/v1", (destination as EndpointValidation.Valid).destination.disclosure())
    }
}
