package com.voicechat.agent.providers.opencodezen

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.stringField

/**
 * The protocol family an OpenCode Zen model is served through.
 *
 * Zen is **not** one wire protocol. Its official endpoint table lists a
 * different endpoint (and AI SDK package) per model, and the three chat families
 * speak different payloads and different SSE events:
 *
 * | family | endpoint | documented client |
 * | --- | --- | --- |
 * | [CHAT_COMPLETIONS] | `/chat/completions` | `@ai-sdk/openai-compatible` |
 * | [RESPONSES] | `/responses` | `@ai-sdk/openai` |
 * | [MESSAGES] | `/messages` | `@ai-sdk/anthropic` |
 *
 * Zen's table lists two further surfaces that are deliberately **not** chat
 * completions and are not modeled here:
 *
 * - `/systemone` (`@ai-sdk` package `-`) serves Jev, a System One structured
 *   decision model. It is never a conversational model.
 * - `/models/<model>` (`@ai-sdk/google`) serves the Gemini models through the
 *   Google generateContent family. That request/stream shape is not printed by
 *   the Zen page, so it is not implemented (R-0142).
 *
 * A single OpenAI-shaped client would silently send the wrong protocol to two of
 * the three chat families, which is the "do not assume OpenAI-compatible
 * behavior" rule. The adapter dispatches on the model id and refuses a model the
 * official table does not place.
 *
 * Verified against <https://opencode.ai/v2/docs/console/models/> on
 * **2026-09-29**; sources and the unverified list are in
 * `docs/opencode-zen-adapter.md`.
 */
enum class OpenCodeZenFamily(
    /** The completion endpoint path, relative to the Zen base URL. */
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
 * The OpenCode Zen model surface (`GET /models`) and the per-model family map.
 *
 * Two different things are verified here and they must not be conflated:
 *
 * - **Model discovery** is live: `GET {base}/models` returns an OpenAI-shaped
 *   `{"object":"list","data":[{"id":…,"created":…,"owned_by":"opencode"}]}` with
 *   identities only. It carries **no** family, reasoning, or usage metadata, and
 *   the endpoint answers without an `Authorization` header, so it can list and
 *   never validate a credential.
 * - **Family routing** is static: the official Zen endpoint table places each
 *   *documented* model on one of the endpoints. A model id the table does not
 *   place has no verified chat family, so [familyFor] returns `null` and the
 *   adapter refuses it rather than guessing a protocol.
 */
object OpenCodeZenModels {
    /** The documented model-list endpoint, relative to the Zen base URL. */
    const val PATH: String = "/models"

    /**
     * The documented chat model-id → family map (Zen endpoint table, accessed
     * 2026-09-29).
     *
     * Zen states its model list can change; when it does, this map must be
     * re-verified before a new model can be selected (R-0140). It intentionally
     * covers only the ids the Zen page itself places on a chat completion
     * endpoint.
     */
    private val FAMILIES: Map<String, OpenCodeZenFamily> =
        buildMap {
            listOf(
                "qwen3.8-max",
                "deepseek-v4.1-flash",
                "deepseek-v4-pro",
                "deepseek-v4-flash",
                "deepseek-v4-flash-vision-exp",
                "minimax-m3",
                "minimax-m2.7",
                "minimax-m2.5",
                "glm-5.3-flash",
                "glm-5.3",
                "glm-5.2",
                "glm-5.1",
                "glm-5",
                "kimi-k2.5",
                "kimi-k2.6",
                "kimi-k2.7-code",
                "kimi-k3",
                "big-pickle",
                "space-bunny-free",
                "longcat-2.5-preview-free",
                "mimo-v2.6-flash-free",
                "mimo-v2.5-free",
                "ling-3.0-flash-fin-free",
                "nemotron-3-ultra-free",
                "nemotron-3.5-lightning-free",
            ).forEach { put(it, OpenCodeZenFamily.CHAT_COMPLETIONS) }

            listOf(
                "gpt-6-astra",
                "gpt-6-sol",
                "gpt-6.1-sol",
                "gpt-6-luna",
                "gpt-5.6-sol",
                "gpt-5.6-terra",
                "gpt-5.6-luna",
                "gpt-5.5",
                "gpt-5.5-pro",
                "gpt-5.4",
                "gpt-5.4-pro",
                "gpt-5.4-mini",
                "gpt-5.4-nano",
                "gpt-5.3-codex",
                "gpt-5.3-codex-spark",
                "gpt-5.2",
                "gpt-5.2-codex",
                "gpt-5.1",
                "gpt-5.1-codex",
                "gpt-5.1-codex-max",
                "gpt-5.1-codex-mini",
                "gpt-5",
                "gpt-5-codex",
                "gpt-5-nano",
                "grok-4.7",
                "grok-4.6",
                "grok-4.5",
                "grok-build-0.1",
                "muse-spark-1.3",
                "muse-spark-1.2",
                "muse-spark-1.3-contributor-free",
            ).forEach { put(it, OpenCodeZenFamily.RESPONSES) }

            listOf(
                "claude-fable-5-1",
                "claude-fable-5",
                "claude-opus-5-5",
                "claude-opus-5",
                "claude-opus-4-8",
                "claude-opus-4-7",
                "claude-opus-4-6",
                "claude-opus-4-5",
                "claude-sonnet-5",
                "claude-sonnet-4-6",
                "claude-sonnet-4-5",
                "claude-haiku-4-5",
                "qwen3.8-flash",
                "qwen3.7-max",
                "qwen3.7-plus",
                "qwen3.6-plus",
                "qwen3.5-plus",
            ).forEach { put(it, OpenCodeZenFamily.MESSAGES) }
        }

    /**
     * The documented System One (`/systemone`) model ids. Jev is a structured
     * decision model, **not** a conversational chat completion, so these ids are
     * never placed in [FAMILIES] and [familyFor] returns `null` for them.
     */
    val SYSTEM_ONE_MODEL_IDS: Set<String> = setOf("jev-1.13", "jev-1.13-free")

    /**
     * The documented Gemini model ids served through `@ai-sdk/google`
     * (`/models/<model>`). Their request/stream shape is not printed by the Zen
     * page, so they are not implemented and [familyFor] returns `null` for them
     * (R-0142).
     */
    val GOOGLE_FAMILY_MODEL_IDS: Set<String> =
        setOf(
            "gemini-3.8-flash",
            "gemini-3.7-flash",
            "gemini-3.6-flash",
            "gemini-3.5-flash",
            "gemini-3.5-flash-lite",
            "gemini-3.1-pro",
            "gemini-3-flash",
        )

    /** The documented chat model ids and their family, for tests and diagnostics. */
    fun documentedFamilies(): Map<String, OpenCodeZenFamily> = FAMILIES

    /**
     * The documented family for [modelId], or `null` when the official Zen table
     * does not place it as a chat completion. `null` is the honest "not verified"
     * answer: the adapter refuses instead of assuming a protocol. This includes
     * the `/systemone` Jev ids and the Google-family Gemini ids.
     */
    fun familyFor(modelId: ModelId): OpenCodeZenFamily? = FAMILIES[modelId.value]

    /**
     * Parses the `data[].id` values from a `/models` response body.
     *
     * The response is an identity-only OpenAI-shaped list (`id`, `object`,
     * `created`, `owned_by`); it carries no family or reasoning metadata, so it is
     * used for listing, never for capability claims.
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
