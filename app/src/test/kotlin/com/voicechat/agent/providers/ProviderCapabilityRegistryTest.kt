package com.voicechat.agent.providers

import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * M13 acceptance for the provider capability registry: the right providers exist,
 * each exposes only its documented auth methods (unsupported/undocumented methods
 * are hidden), endpoints are the documented public hosts, and unknown provider
 * behavior is marked rather than claimed.
 */
class ProviderCapabilityRegistryTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()

    @Test
    fun everyPlannedProviderIsRegisteredExactlyOnce() {
        val ids = registry.all().map { it.providerId }

        assertEquals(ids.size, ids.distinct().size)
        assertEquals(
            setOf(
                KnownProviders.OPENAI,
                KnownProviders.OPENROUTER,
                KnownProviders.OPENCODE_GO,
                KnownProviders.OPENCODE_ZEN,
                KnownProviders.DEEPSEEK,
                KnownProviders.HERMES,
            ),
            ids.toSet(),
        )
        assertTrue(registry.isKnown(KnownProviders.OPENAI))
        assertNull(registry.capabilities(ProviderId("not-a-provider")))
    }

    @Test
    fun onlyDocumentedAuthMethodsAreExposed() {
        // An unknown/undocumented method cannot be shown because the enum is closed
        // and has no QR value.
        assertTrue(AuthMethod.entries.none { it.name.contains("QR") })

        assertEquals(setOf(AuthMethod.API_KEY), registry.capabilities(KnownProviders.OPENAI)!!.availableAuthMethods)
        assertEquals(
            setOf(AuthMethod.API_KEY, AuthMethod.OAUTH_PKCE),
            registry.capabilities(KnownProviders.OPENROUTER)!!.availableAuthMethods,
        )
        // Every other provider is API-key only; no invented sign-in option.
        listOf(KnownProviders.OPENCODE_GO, KnownProviders.OPENCODE_ZEN, KnownProviders.DEEPSEEK, KnownProviders.HERMES)
            .forEach { id ->
                assertEquals(setOf(AuthMethod.API_KEY), registry.capabilities(id)!!.availableAuthMethods)
            }
    }

    @Test
    fun unverifiedProviderBehaviorIsMarkedNotClaimed() {
        val go = registry.capabilities(KnownProviders.OPENCODE_GO)!!
        val zen = registry.capabilities(KnownProviders.OPENCODE_ZEN)!!

        assertTrue(go.models.reasoningLevels.isEmpty())
        assertTrue(zen.models.reasoningLevels.isEmpty())
        assertTrue(go.models.unverified.contains(UnverifiedCapability.REASONING))
        assertTrue(zen.models.unverified.contains(UnverifiedCapability.AUTH))
        // The app must not claim a minimal check it has not verified.
        assertEquals(CredentialValidationSupport.NONE, go.auth.validation)
        assertEquals(CredentialValidationSupport.NONE, zen.auth.validation)
    }

    @Test
    fun deepSeekExposesItsReverifiedDocumentedCapabilities() {
        val deepSeek = registry.capabilities(KnownProviders.DEEPSEEK)!!

        // Re-verified at M16 (2026-09-29): Chat Completions accepts every level
        // this app exposes (`minimal`/`medium`/`xhigh` are documented aliases),
        // and reports usage. See docs/deepseek-adapter.md.
        assertEquals(
            setOf(
                ReasoningLevel.NONE,
                ReasoningLevel.MINIMAL,
                ReasoningLevel.LOW,
                ReasoningLevel.MEDIUM,
                ReasoningLevel.HIGH,
                ReasoningLevel.XHIGH,
                ReasoningLevel.MAX,
            ),
            deepSeek.models.reasoningLevels,
        )
        assertTrue(deepSeek.models.usageReporting)
        assertTrue(deepSeek.models.unverified.isEmpty())
        assertEquals(CredentialValidationSupport.LIST_MODELS, deepSeek.auth.validation)
    }

    @Test
    fun openAiAndOpenRouterExposeTheirDocumentedReasoningLevels() {
        val openAi = registry.capabilities(KnownProviders.OPENAI)!!
        assertTrue(openAi.models.reasoningLevels.containsAll(setOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH)))
        // Re-verified at M14 (2026-09-29): the documented effort union now includes
        // `minimal`, so the OpenAI row carries it. See docs/openai-adapter.md.
        assertTrue(openAi.models.reasoningLevels.contains(ReasoningLevel.MINIMAL))

        val openRouter = registry.capabilities(KnownProviders.OPENROUTER)!!
        assertTrue(openRouter.models.reasoningLevels.contains(ReasoningLevel.MINIMAL))
    }

    @Test
    fun fixedProvidersPinTheirDocumentedHostAndConfigurableProvidersDoNot() {
        registry.all().forEach { provider ->
            val transport = provider.transport
            if (transport.configurable) {
                assertNull(transport.baseUrl)
                assertNull(transport.expectedHost)
            } else {
                val host = URI(transport.baseUrl!!).host
                assertEquals(host, transport.expectedHost)
            }
        }

        assertTrue(registry.capabilities(KnownProviders.HERMES)!!.transport.configurable)
        assertTrue(registry.capabilities(KnownProviders.HERMES)!!.toolExecutionOnServer)
        assertFalse(registry.capabilities(KnownProviders.OPENAI)!!.transport.configurable)
    }

    @Test
    fun providerCapabilitiesReconcileWithTheM12LlmCapabilitiesSeam() {
        val openAi = registry.capabilities(KnownProviders.OPENAI)!!
        val capabilities = openAi.toLlmCapabilities()

        assertEquals(openAi.transport.streaming, capabilities.streaming)
        assertEquals(openAi.models.usageReporting, capabilities.usageReporting)
        assertEquals(openAi.models.reasoningLevels, capabilities.reasoningLevels)
    }
}
