package com.voicechat.agent.providers.hermes

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
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
 * One model advertised by a Hermes server's `GET /v1/models`.
 *
 * The OpenAI-compatible surface advertises a single stable alias (`hermes-agent`
 * or the active profile name), not every provider/model the agent can route to.
 */
data class HermesModel(
    val id: ModelId,
)

/**
 * The verified Hermes model-discovery surface (`GET /v1/models`).
 *
 * Verified from the official docs (accessed 2026-09-29): the endpoint "lists the
 * agent as an available model", the advertised name defaults to the profile name
 * (or `hermes-agent` for the default profile), and the richer picker inventory
 * lives on the Hermes-native `GET /api/model/options` (not used here because it
 * is a Hermes control-plane surface, not the OpenAI-compatible one).
 */
object HermesModels {
    /** The documented model-list endpoint, relative to the base URL. */
    const val PATH: String = "/models"

    /** The default agent alias when a server advertises none. */
    const val DEFAULT_AGENT_ALIAS: String = "hermes-agent"

    /** Parses the `data[]` model entries from a `/models` response body. */
    fun parse(body: String): List<HermesModel> {
        val root = RemoteJson.parse(body)
        val data = root.arrField("data") ?: return emptyList()
        return data.items
            .mapNotNull { item -> (item as? JsonNode.Obj)?.stringField("id") }
            .mapNotNull { id -> id.takeIf { it.isNotBlank() }?.let { HermesModel(ModelId(it)) } }
    }

    /** Convenience for callers that only need the model identities. */
    fun parseModelIds(body: String): List<ModelId> = parse(body).map { it.id }
}

/**
 * The subset of the Hermes `GET /v1/capabilities` payload this app consumes.
 *
 * Verified from the official docs (accessed 2026-09-29): the endpoint returns
 * `auth: {type, required}` and a `features` object advertising
 * `chat_completions`, `responses_api`, and `reasoning_streaming`.
 */
data class HermesServerCapabilities(
    val authType: String?,
    val authRequired: Boolean,
    val chatCompletions: Boolean,
    val responsesApi: Boolean,
    val reasoningStreaming: Boolean,
)

/** The verified Hermes `GET /v1/capabilities` discovery surface. */
object HermesCapabilities {
    /** The documented capabilities endpoint, relative to the base URL. */
    const val PATH: String = "/capabilities"

    /** Parses a `/capabilities` response body, defaulting absent flags to `false`. */
    fun parse(body: String): HermesServerCapabilities {
        val root = RemoteJson.parse(body)
        val auth = (root as? JsonNode.Obj)?.objField("auth")
        val features = (root as? JsonNode.Obj)?.objField("features")
        return HermesServerCapabilities(
            authType = auth?.stringField("type"),
            authRequired = (auth?.get("required") as? JsonNode.Bool)?.value ?: false,
            chatCompletions = (features?.get("chat_completions") as? JsonNode.Bool)?.value ?: false,
            responsesApi = (features?.get("responses_api") as? JsonNode.Bool)?.value ?: false,
            reasoningStreaming = (features?.get("reasoning_streaming") as? JsonNode.Bool)?.value ?: false,
        )
    }
}

/**
 * A [ModelCapabilityCatalog] backed by a parsed Hermes `/v1/models` response.
 *
 * The alias streams (verified) but no usage reporting is claimed for the stream
 * and the reasoning-effort vocabulary is not documented, so the catalog claims
 * neither. An unknown model returns `null`, so no capability is assumed.
 */
class HermesModelCatalog(
    private val models: List<HermesModel>,
    private val providerId: ProviderId = KnownProviders.HERMES,
) : ModelCapabilityCatalog {
    private val ids: Set<ModelId> = models.map { it.id }.toSet()

    override fun modelCapabilities(
        providerId: ProviderId,
        modelId: ModelId,
    ): ModelCapabilities? {
        if (providerId != this.providerId || modelId !in ids) return null
        return ModelCapabilities(
            providerId = providerId,
            modelId = modelId,
            streaming = true,
            usageReporting = false,
            reasoningLevels = emptySet(),
        )
    }

    companion object {
        /** Parses a `/models` body into a catalog for [providerId]. */
        fun fromModelsBody(
            body: String,
            providerId: ProviderId = KnownProviders.HERMES,
        ): HermesModelCatalog = HermesModelCatalog(HermesModels.parse(body), providerId)
    }
}
