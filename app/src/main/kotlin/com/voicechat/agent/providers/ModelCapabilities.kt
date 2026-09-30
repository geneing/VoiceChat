package com.voicechat.agent.providers

import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRequestValidation
import com.voicechat.agent.contracts.LlmRequestValidator
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel

/**
 * Per-model capabilities as reported by a provider's model-discovery surface.
 *
 * This is the "per-*model* capability source" that R-0065 identified as missing:
 * the M12 [LlmCapabilities] is declared per adapter, but which reasoning levels a
 * specific model exposes comes from `/models` (OpenAI/OpenRouter/DeepSeek and the
 * OpenCode surfaces) and must refine the provider-level union.
 */
data class ModelCapabilities(
    val providerId: ProviderId,
    val modelId: ModelId,
    val streaming: Boolean,
    val usageReporting: Boolean,
    val reasoningLevels: Set<ReasoningLevel>,
)

/**
 * A source of per-model capabilities, typically a provider's `/models` response
 * parsed by its adapter (M14+).
 *
 * Returning `null` means "not known yet", which the reconciler treats as "claim
 * nothing beyond the provider-level transport" rather than assuming parity.
 */
interface ModelCapabilityCatalog {
    fun modelCapabilities(
        providerId: ProviderId,
        modelId: ModelId,
    ): ModelCapabilities?
}

/** A deterministic catalog used by tests and as a placeholder before live discovery. */
class StaticModelCapabilityCatalog(
    entries: List<ModelCapabilities>,
) : ModelCapabilityCatalog {
    private val byKey: Map<Pair<ProviderId, ModelId>, ModelCapabilities> =
        entries.associateBy { it.providerId to it.modelId }

    override fun modelCapabilities(
        providerId: ProviderId,
        modelId: ModelId,
    ): ModelCapabilities? = byKey[providerId to modelId]
}

/**
 * Reconciles the provider-level registry with per-model capabilities and the
 * M12 request validator.
 *
 * The effective capability is the **intersection**: a level is shown or accepted
 * only when both the provider and the specific model expose it. When the model
 * is unknown, no reasoning level is claimed (a request may still ask for
 * [ReasoningLevel.NONE], which every provider accepts) — this is the mechanical
 * form of "hide unsupported model options" (R-0065).
 */
object LlmCapabilityReconciler {
    /** The capabilities a request for [model] must be checked against. */
    fun effective(
        provider: ProviderCapabilities,
        model: ModelCapabilities?,
    ): LlmCapabilities =
        if (model == null) {
            LlmCapabilities(
                streaming = provider.transport.streaming,
                usageReporting = false,
                reasoningLevels = emptySet(),
            )
        } else {
            LlmCapabilities(
                streaming = provider.transport.streaming && model.streaming,
                usageReporting = provider.models.usageReporting && model.usageReporting,
                reasoningLevels = provider.models.reasoningLevels.intersect(model.reasoningLevels),
            )
        }

    /** Validates [request] against the reconciled capabilities for its model. */
    fun validate(
        request: LlmRequest,
        provider: ProviderCapabilities,
        model: ModelCapabilities?,
    ): LlmRequestValidation = LlmRequestValidator.validate(request, effective(provider, model))
}
