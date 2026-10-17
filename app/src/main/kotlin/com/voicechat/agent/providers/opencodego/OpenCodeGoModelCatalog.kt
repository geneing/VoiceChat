package com.voicechat.agent.providers.opencodego

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ModelCapabilities
import com.voicechat.agent.providers.ModelCapabilityCatalog

/**
 * The **measured** per-model capabilities for OpenCode Go (M22, R-0065, R-0130).
 *
 * Go's public `GET /models` lists identities only — no family, reasoning, or
 * usage metadata — so nothing can be read from the wire. This catalog is
 * therefore the dated, measured table recorded in `docs/voice-llm-selection.md`
 * and [OpenCodeGoReasoning], and it deliberately answers `null` for an id that was
 * not measured, which the reconciler turns into "claim nothing beyond the
 * provider default" instead of an assumed capability.
 *
 * It refines, never replaces, the provider-level registry row: the effective
 * capability is the intersection of the two (`LlmCapabilityReconciler`), so Go's
 * provider-level union cannot grant a level a specific model was not measured to
 * accept.
 *
 * Today Go reports usage for every family and streams reliably; those two flags
 * are provider-verified facts and are returned for a measured model only because
 * the reconciler ANDs them with the provider row.
 */
class OpenCodeGoModelCatalog(
    private val providerId: ProviderId = KnownProviders.OPENCODE_GO,
) : ModelCapabilityCatalog {
    override fun modelCapabilities(
        providerId: ProviderId,
        modelId: ModelId,
    ): ModelCapabilities? {
        if (providerId != this.providerId) return null
        val support = OpenCodeGoReasoning.support(modelId) ?: return null
        return ModelCapabilities(
            providerId = providerId,
            modelId = modelId,
            streaming = true,
            usageReporting = true,
            reasoningLevels = support.levels,
        )
    }

    companion object {
        /** The shared instance; the table is static and immutable. */
        val INSTANCE: OpenCodeGoModelCatalog = OpenCodeGoModelCatalog()
    }
}
