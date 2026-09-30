package com.voicechat.agent.providers

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRequestValidation
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.CredentialStatus
import com.voicechat.agent.domain.ConnectionState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M13 acceptance for the per-model capability source (R-0065): a request is
 * checked against the intersection of the provider union and the specific
 * model's `/models` report, so an unsupported reasoning option is refused (and
 * hidden) rather than sent.
 */
class ModelCapabilityReconciliationTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val openAi = registry.capabilities(KnownProviders.OPENAI)!!
    private val modelId = ModelId("gpt-test")

    private val modelCatalog =
        StaticModelCapabilityCatalog(
            listOf(
                ModelCapabilities(
                    providerId = KnownProviders.OPENAI,
                    modelId = modelId,
                    streaming = true,
                    usageReporting = true,
                    reasoningLevels = setOf(ReasoningLevel.NONE, ReasoningLevel.LOW),
                ),
            ),
        )

    private fun request(reasoning: ReasoningLevel?) =
        LlmRequest(
            model = ProviderModelSelection(KnownProviders.OPENAI, modelId),
            messages = listOf(LlmMessage(LlmRole.USER, "hello")),
            reasoning = reasoning,
        )

    @Test
    fun anUnsupportedModelReasoningLevelIsRefusedWithATypedError() {
        val result =
            LlmCapabilityReconciler.validate(
                request(ReasoningLevel.HIGH),
                openAi,
                modelCatalog.modelCapabilities(KnownProviders.OPENAI, modelId),
            )

        assertTrue(result is LlmRequestValidation.Unsupported)
        assertEquals(ErrorCode.LLM_INVALID_REQUEST, (result as LlmRequestValidation.Unsupported).error.code)
    }

    @Test
    fun aSupportedModelReasoningLevelIsAccepted() {
        val result =
            LlmCapabilityReconciler.validate(
                request(ReasoningLevel.LOW),
                openAi,
                modelCatalog.modelCapabilities(KnownProviders.OPENAI, modelId),
            )

        assertEquals(LlmRequestValidation.Supported, result)
    }

    @Test
    fun noReasoningIsAlwaysAcceptedEvenForAnUnknownModel() {
        assertEquals(LlmRequestValidation.Supported, LlmCapabilityReconciler.validate(request(null), openAi, null))
        assertEquals(LlmRequestValidation.Supported, LlmCapabilityReconciler.validate(request(ReasoningLevel.NONE), openAi, null))
    }

    @Test
    fun anUnknownModelClaimsNoReasoningLevels() {
        val effective = LlmCapabilityReconciler.effective(openAi, model = null)

        assertTrue(effective.reasoningLevels.isEmpty())
        assertTrue(effective.streaming)
    }

    @Test
    fun theEffectiveCapabilitiesAreTheIntersectionNotTheProviderUnion() {
        val model = modelCatalog.modelCapabilities(KnownProviders.OPENAI, modelId)!!
        val effective = LlmCapabilityReconciler.effective(openAi, model)

        assertEquals(setOf(ReasoningLevel.NONE, ReasoningLevel.LOW), effective.reasoningLevels)
        assertTrue(openAi.models.reasoningLevels.contains(ReasoningLevel.HIGH))
        assertTrue(effective.reasoningLevels.none { it == ReasoningLevel.HIGH })
    }

    @Test
    fun aNonStreamingModelDisablesStreaming() {
        val nonStreaming =
            ModelCapabilities(
                providerId = KnownProviders.OPENAI,
                modelId = ModelId("legacy"),
                streaming = false,
                usageReporting = false,
                reasoningLevels = emptySet(),
            )

        assertEquals(false, LlmCapabilityReconciler.effective(openAi, nonStreaming).streaming)
    }

    @Test
    fun theStaticCatalogReturnsKnownEntriesAndNullOtherwise() {
        assertEquals(modelId, modelCatalog.modelCapabilities(KnownProviders.OPENAI, modelId)?.modelId)
        assertEquals(null, modelCatalog.modelCapabilities(KnownProviders.OPENAI, ModelId("missing")))
    }

    @Test
    fun connectionStateRequiresAStoredCredentialWhenTheProviderNeedsOne() {
        assertEquals(
            ConnectionState.Unavailable(
                com.voicechat.agent.domain.UnavailableReason.CREDENTIALS_REQUIRED,
                "OpenAI needs a credential before it can be used.",
            ),
            ProviderConnectionState.derive(openAi, CredentialStatus.NotStored),
        )
        assertEquals(
            ConnectionState.Disconnected,
            ProviderConnectionState.derive(openAi, CredentialStatus.Stored(KnownProviders.OPENAI, CredentialKind.API_KEY)),
        )
    }
}
