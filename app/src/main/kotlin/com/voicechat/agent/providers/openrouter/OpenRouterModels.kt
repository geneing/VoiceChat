package com.voicechat.agent.providers.openrouter

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ModelCapabilities
import com.voicechat.agent.providers.ModelCapabilityCatalog
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.get
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField

/**
 * One model from the OpenRouter `/models` catalog with the reasoning metadata the
 * gateway reports for it (M15).
 *
 * `reasoningLevels` is empty for a non-reasoning model (the `reasoning` object is
 * omitted) and is every gateway effort when the model reports a null
 * `supported_efforts` ("all gateway effort values are accepted").
 */
data class OpenRouterModel(
    val id: ModelId,
    val reasoningLevels: Set<ReasoningLevel>,
    val reasoningMandatory: Boolean,
    val supportsReasoningMaxTokens: Boolean,
)

/**
 * The verified OpenRouter model-discovery surface (`GET /api/v1/models`).
 *
 * Unlike OpenAI's `/models` (identities only), this response carries per-model
 * `reasoning.supported_efforts` / `default_effort` / `mandatory` /
 * `supports_max_tokens`, which is the per-model capability source R-0065/R-0102
 * describe. The catalog below maps it into the M13 [ModelCapabilityCatalog] seam;
 * wiring it into the settings runtime provider remains M22/M23.
 */
object OpenRouterModels {
    /** The documented model-list endpoint, relative to the base URL. */
    const val PATH: String = "/models"

    /** Every effort the OpenRouter gateway accepts; a model with a null
     * `supported_efforts` accepts them all. */
    val GATEWAY_EFFORTS: Set<ReasoningLevel> =
        setOf(
            ReasoningLevel.NONE,
            ReasoningLevel.MINIMAL,
            ReasoningLevel.LOW,
            ReasoningLevel.MEDIUM,
            ReasoningLevel.HIGH,
            ReasoningLevel.XHIGH,
            ReasoningLevel.MAX,
        )

    /** Parses the `data[]` model entries from a `/models` response body. */
    fun parse(body: String): List<OpenRouterModel> {
        val root = RemoteJson.parse(body)
        val data = root.arrField("data") ?: return emptyList()
        return data.items.mapNotNull { item -> (item as? JsonNode.Obj)?.let { parseModel(it) } }
    }

    /** Convenience for callers that only need the model identities. */
    fun parseModelIds(body: String): List<ModelId> = parse(body).map { it.id }

    private fun parseModel(node: JsonNode.Obj): OpenRouterModel? {
        val id = node.stringField("id")?.takeIf { it.isNotBlank() } ?: return null
        val reasoning = node.objField("reasoning")
        val mandatory = (reasoning?.get("mandatory") as? JsonNode.Bool)?.value ?: false
        val supportsMaxTokens = (reasoning?.get("supports_max_tokens") as? JsonNode.Bool)?.value ?: false
        return OpenRouterModel(
            id = ModelId(id),
            reasoningLevels = reasoningLevels(reasoning, mandatory),
            reasoningMandatory = mandatory,
            supportsReasoningMaxTokens = supportsMaxTokens,
        )
    }

    private fun reasoningLevels(
        reasoning: JsonNode.Obj?,
        mandatory: Boolean,
    ): Set<ReasoningLevel> {
        if (reasoning == null) return emptySet()
        val supported = reasoning.arrField("supported_efforts")
        val levels =
            if (supported == null) {
                GATEWAY_EFFORTS
            } else {
                supported.items
                    .mapNotNull { (it as? JsonNode.Str)?.value }
                    .mapNotNull { OpenRouterChatCompletions.reasoningFromWire(it) }
                    .toSet()
            }
        // A mandatory model rejects `effort: "none"`, so it is not offered; the
        // adapter still omits the reasoning object for a NONE request.
        return if (mandatory) levels - ReasoningLevel.NONE else levels
    }
}

/**
 * A [ModelCapabilityCatalog] backed by a parsed OpenRouter `/models` response.
 *
 * The streaming and usage flags are the verified provider-level facts (every
 * OpenRouter chat completion streams and reports usage); the reasoning set is the
 * per-model `supported_efforts`. [ModelCapabilities] returned for an unknown model
 * is `null`, so no reasoning level is claimed rather than assumed.
 */
class OpenRouterModelCatalog(
    private val models: List<OpenRouterModel>,
    private val providerId: ProviderId = KnownProviders.OPENROUTER,
) : ModelCapabilityCatalog {
    private val byId: Map<ModelId, OpenRouterModel> = models.associateBy { it.id }

    override fun modelCapabilities(
        providerId: ProviderId,
        modelId: ModelId,
    ): ModelCapabilities? {
        if (providerId != this.providerId) return null
        val model = byId[modelId] ?: return null
        return ModelCapabilities(
            providerId = providerId,
            modelId = modelId,
            streaming = true,
            usageReporting = true,
            reasoningLevels = model.reasoningLevels,
        )
    }

    companion object {
        /** Parses a `/models` body into a catalog for [providerId]. */
        fun fromModelsBody(
            body: String,
            providerId: ProviderId = KnownProviders.OPENROUTER,
        ): OpenRouterModelCatalog = OpenRouterModelCatalog(OpenRouterModels.parse(body), providerId)
    }
}
