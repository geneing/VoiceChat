package com.voicechat.agent.providers.opencodego

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.stringField

/**
 * The protocol family an OpenCode Go model is served through.
 *
 * Go is **not** one wire protocol. Its official model table lists a different
 * endpoint (and AI SDK package) per model, and the three families speak
 * different payloads and different SSE events:
 *
 * | family | endpoint | documented client |
 * | --- | --- | --- |
 * | [CHAT_COMPLETIONS] | `/chat/completions` | `@ai-sdk/openai-compatible` |
 * | [RESPONSES] | `/responses` | `@ai-sdk/openai` |
 * | [MESSAGES] | `/messages` | `@ai-sdk/anthropic` |
 *
 * A single OpenAI-shaped client would silently send the wrong protocol to two of
 * the three families, which is exactly the "do not assume OpenAI-compatible
 * behavior" rule. The adapter therefore dispatches on the model id and refuses a
 * model whose family the official table does not place.
 *
 * Verified against <https://opencode.ai/v2/docs/console/go/> on **2026-09-29**;
 * sources and the unverified list are in `docs/opencode-go-adapter.md`.
 */
enum class OpenCodeGoFamily(
    /** The completion endpoint path, relative to the Go base URL. */
    val path: String,
) {
    /** OpenAI-compatible Chat Completions (`@ai-sdk/openai-compatible`). */
    CHAT_COMPLETIONS("/chat/completions"),

    /** OpenAI Responses (`@ai-sdk/openai`). */
    RESPONSES("/responses"),

    /** Anthropic Messages (`@ai-sdk/anthropic`). */
    MESSAGES("/messages"),
}

/**
 * The OpenCode Go model surface (`GET /models`) and the per-model family map.
 *
 * Two different things are verified here and they must not be conflated:
 *
 * - **Model discovery** is live: `GET {base}/models` returns an OpenAI-shaped
 *   `{"object":"list","data":[{"id":…}]}` with identities only. It carries **no**
 *   family, reasoning, or usage metadata, and the endpoint is public (it answers
 *   without an `Authorization` header), so it can list and never validate a
 *   credential.
 * - **Family routing** is static: the official Go page places each *documented*
 *   model on one of the three endpoints. A model id that the page does not place
 *   has no verified family, so [familyFor] returns `null` and the adapter refuses
 *   it rather than guessing a protocol.
 */
object OpenCodeGoModels {
    /** The documented model-list endpoint, relative to the Go base URL. */
    const val PATH: String = "/models"

    /**
     * The documented model-id → family map (Go model table, accessed 2026-09-29).
     *
     * Go states the model list "may change as we test and add new ones"; when it
     * does, this map must be re-verified before a new model can be selected
     * (R-0130). It intentionally covers only the ids the Go page itself places.
     */
    private val FAMILIES: Map<String, OpenCodeGoFamily> =
        buildMap {
            listOf(
                "glm-5.3-flash",
                "glm-5.3",
                "glm-5.2",
                "kimi-k3",
                "kimi-k2.7-code",
                "kimi-k2.6",
                "longcat-2.0",
                "deepseek-v4.1-flash",
                "deepseek-v4-pro",
                "deepseek-v4-flash",
                "deepseek-v4-flash-vision-exp",
                "mimo-v2.6-flash",
                "mimo-v2.6-pro",
                "mimo-v2.5",
                "mimo-v2.5-pro",
                "hy4-preview",
                "hy3",
                "space-bunny-free",
                "longcat-2.5-preview-free",
            ).forEach { put(it, OpenCodeGoFamily.CHAT_COMPLETIONS) }

            listOf(
                "grok-4.7",
                "grok-4.6",
                "gpt-6-luna",
                "gpt-5.6-luna",
                "muse-spark-1.3-contributor",
                "muse-spark-1.2-contributor",
            ).forEach { put(it, OpenCodeGoFamily.RESPONSES) }

            listOf(
                "minimax-m3",
                "minimax-m2.7",
                "qwen3.8-max",
                "qwen3.8-flash",
                "qwen3.7-plus",
            ).forEach { put(it, OpenCodeGoFamily.MESSAGES) }
        }

    /** The documented model ids and their family, for tests and diagnostics. */
    fun documentedFamilies(): Map<String, OpenCodeGoFamily> = FAMILIES

    /**
     * The documented family for [modelId], or `null` when the official Go table
     * does not place it. `null` is the honest "not verified" answer: the adapter
     * refuses instead of assuming a protocol.
     */
    fun familyFor(modelId: ModelId): OpenCodeGoFamily? = FAMILIES[modelId.value]

    /**
     * Parses the `data[].id` values from a `/models` response body.
     *
     * The response is the same identity-only shape OpenAI documents; it carries
     * no family or reasoning metadata, so it is used for listing, never for
     * capability claims.
     */
    fun parseModelIds(body: String): List<ModelId> {
        val root = RemoteJson.parse(body)
        val data = root.arrField("data") ?: return emptyList()
        return data.items.mapNotNull { item ->
            (item as? JsonNode.Obj)
                ?.stringField("id")
                ?.takeIf { it.isNotBlank() }
                ?.let { ModelId(it) }
        }
    }
}
