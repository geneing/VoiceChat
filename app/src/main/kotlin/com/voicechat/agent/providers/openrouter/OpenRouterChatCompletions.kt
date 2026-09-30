package com.voicechat.agent.providers.openrouter

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.LlmUsage
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.SseFrame
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.get
import com.voicechat.agent.remote.intField
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField
import java.math.BigDecimal

/**
 * One interpreted frame from an OpenRouter Chat Completions stream.
 *
 * OpenRouter normalizes every provider to the OpenAI Chat Completions wire shape,
 * but it adds a terminal `[DONE]` sentinel, an accounting chunk that carries
 * `usage`, and a unified mid-stream `error` object. The reasoning channel is a
 * distinct case on purpose: reasoning text is **not** assistant text, so an
 * adapter excludes it from `Delta` instead of concatenating it (risk R-0066).
 */
sealed interface OpenRouterStreamFrame {
    /** Assistant output text (or a refusal) to surface as a `Delta`. */
    data class Text(
        val text: String,
    ) : OpenRouterStreamFrame

    /** A reasoning/thinking delta; excluded from assistant text. */
    data class Reasoning(
        val text: String,
    ) : OpenRouterStreamFrame

    /**
     * The accounting chunk: a content-free chunk that carries the final `usage`
     * and (usually) the reported serving `model`. It is deliberately **not** a
     * completion; OpenRouter sends it just before `[DONE]` and documents it as an
     * accounting frame, so the adapter waits for [Done] before completing.
     */
    data class Accounting(
        val usage: LlmUsage,
        val model: ModelId?,
    ) : OpenRouterStreamFrame

    /** The `[DONE]` sentinel that ends a Chat Completions stream. */
    data object Done : OpenRouterStreamFrame

    /** The provider (or OpenRouter) reported a terminal failure. */
    data class Failed(
        val error: VoiceAgentError,
    ) : OpenRouterStreamFrame

    /** A frame with no contract-relevant content (role-only or debug frames). */
    data object Ignored : OpenRouterStreamFrame
}

/**
 * The verified OpenRouter Chat Completions request/stream mapping (M15).
 *
 * Everything provider-specific lives here: the endpoint path, the JSON payload,
 * the reasoning-effort spelling, the `[DONE]` sentinel, and the typed error
 * dispatch. Nothing here touches HTTP, a credential, or a log. The verified facts
 * and their sources are in `docs/openrouter-adapter.md`.
 */
object OpenRouterChatCompletions {
    /** The documented streaming completion endpoint, relative to the base URL. */
    const val PATH: String = "/chat/completions"

    /** The documented sentinel that terminates a Chat Completions SSE stream. */
    const val DONE_SENTINEL: String = "[DONE]"

    /**
     * Encodes one request as the OpenRouter Chat Completions payload.
     *
     * **Routing is constrained (R-0017).** OpenRouter will, by default, fall back
     * to another provider or GPU on a 5xx or a rate limit, and the `models[]` +
     * `route: "fallback"` fields route across *different models*. Because the app
     * forbids silently switching a provider or model, this encoder:
     *
     * - sends `provider.allow_fallbacks = false`, so a failed attempt is reported
     *   rather than retried against a backup provider;
     * - never emits `models[]` or `route`, so the selected model is the only
     *   candidate;
     * - never appends a routing variant (`:nitro`, `:floor`) to the model slug.
     *
     * The adapter records the model OpenRouter reports in the response, so even a
     * routing alias that resolves to a concrete model is visible rather than
     * silently accepted.
     *
     * `reasoning.effort` is sent only for a real effort level. `ReasoningLevel.NONE`
     * omits the field entirely, because some models reject `"none"` (OpenRouter
     * marks them `reasoning.mandatory`) and "do not reason" is expressed by the
     * model default; `LlmRequestValidator` has already refused a level the adapter
     * does not declare.
     */
    fun encodeRequest(request: LlmRequest): String {
        val messages = RemoteJson.arr(request.messages.map { encodeMessage(it) })
        val fields =
            linkedMapOf<String, JsonNode>(
                "model" to RemoteJson.str(request.model.modelId.value),
                "messages" to messages,
                "stream" to RemoteJson.bool(true),
                // No `models[]`, no `route`, and fallbacks disabled: the selected
                // model/provider is the only routing candidate (R-0017).
                "provider" to RemoteJson.obj("allow_fallbacks" to RemoteJson.bool(false)),
            )
        request.reasoning
            ?.takeIf { it != ReasoningLevel.NONE }
            ?.let { level ->
                fields["reasoning"] = RemoteJson.obj("effort" to RemoteJson.str(effortWire(level)))
            }
        return RemoteJson.stringify(RemoteJson.obj(fields))
    }

    private fun encodeMessage(message: LlmMessage): JsonNode =
        RemoteJson.obj(
            "role" to RemoteJson.str(roleWire(message.role)),
            "content" to RemoteJson.str(message.content),
        )

    /**
     * The wire role for an [LlmRole]. Chat Completions uses `system`, unlike the
     * Responses API's `developer`.
     */
    fun roleWire(role: LlmRole): String =
        when (role) {
            LlmRole.SYSTEM -> "system"
            LlmRole.USER -> "user"
            LlmRole.ASSISTANT -> "assistant"
        }

    /** The documented `reasoning.effort` value for [level]. */
    fun effortWire(level: ReasoningLevel): String =
        when (level) {
            ReasoningLevel.NONE -> "none"
            ReasoningLevel.MINIMAL -> "minimal"
            ReasoningLevel.LOW -> "low"
            ReasoningLevel.MEDIUM -> "medium"
            ReasoningLevel.HIGH -> "high"
            ReasoningLevel.XHIGH -> "xhigh"
            ReasoningLevel.MAX -> "max"
        }

    /** The [ReasoningLevel] for a reported effort, or `null` when not recognized. */
    fun reasoningFromWire(value: String?): ReasoningLevel? =
        when (value?.lowercase()) {
            "none" -> ReasoningLevel.NONE
            "minimal" -> ReasoningLevel.MINIMAL
            "low" -> ReasoningLevel.LOW
            "medium" -> ReasoningLevel.MEDIUM
            "high" -> ReasoningLevel.HIGH
            "xhigh" -> ReasoningLevel.XHIGH
            "max" -> ReasoningLevel.MAX
            else -> null
        }

    /**
     * Interprets one SSE [frame] from a Chat Completions stream.
     *
     * `[DONE]` is checked before JSON parsing (it is not JSON). A frame whose
     * payload is not valid JSON becomes a typed `LLM_MALFORMED_RESPONSE` failure
     * rather than an exception escaping the adapter boundary. A mid-stream error
     * is the top-level `error` object OpenRouter sends in place of an HTTP status
     * once the response is committed.
     */
    fun parse(frame: SseFrame): OpenRouterStreamFrame {
        if (frame.data.trim() == DONE_SENTINEL) return OpenRouterStreamFrame.Done

        val root =
            try {
                RemoteJson.parse(frame.data)
            } catch (_: Exception) {
                return OpenRouterStreamFrame.Failed(
                    VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider sent a frame that was not valid JSON"),
                )
            }
        if (root !is JsonNode.Obj) return OpenRouterStreamFrame.Ignored

        // A mid-stream error after the 200 OK is committed arrives as a top-level
        // `error` object rather than an HTTP status (docs/openrouter-adapter.md).
        root.objField("error")?.let { return OpenRouterStreamFrame.Failed(errorFrom(it)) }

        val choice = root.arrField("choices")?.items?.firstOrNull() as? JsonNode.Obj ?: return OpenRouterStreamFrame.Ignored
        if (choice.stringField("finish_reason") == "error") {
            return OpenRouterStreamFrame.Failed(
                VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider reported a mid-stream error"),
            )
        }

        val delta = choice.objField("delta")
        val content = delta?.stringField("content")
        if (!content.isNullOrEmpty()) return OpenRouterStreamFrame.Text(content)

        // The reasoning channel is never assistant text (R-0066); it may arrive as
        // a plain string or as the structured `reasoning_details` array.
        val reasoning = delta?.stringField("reasoning")
        if (!reasoning.isNullOrEmpty()) return OpenRouterStreamFrame.Reasoning(reasoning)
        val reasoningDetails = delta?.arrField("reasoning_details")
        if (reasoningDetails != null && reasoningDetails.items.isNotEmpty()) {
            return OpenRouterStreamFrame.Reasoning("")
        }

        val usage = parseUsage(root.objField("usage"))
        if (usage != null) return OpenRouterStreamFrame.Accounting(usage = usage, model = modelOf(root))

        return OpenRouterStreamFrame.Ignored
    }

    /** The reported serving model on this frame, or `null` when unreported. */
    fun modelOf(root: JsonNode.Obj): ModelId? =
        root
            .stringField("model")
            ?.takeIf { it.isNotBlank() }
            ?.let { ModelId(it) }

    private fun parseUsage(node: JsonNode.Obj?): LlmUsage? {
        if (node == null) return null
        val details = linkedMapOf<String, String>()
        node.objField("prompt_tokens_details")?.intField("cached_tokens")?.let { details["cachedInputTokens"] = it.toString() }
        node.objField("completion_tokens_details")?.intField("reasoning_tokens")?.let { details["reasoningTokens"] = it.toString() }
        costDetail(node)?.let { details["cost"] = it }
        val prompt = node.intField("prompt_tokens")
        val completion = node.intField("completion_tokens")
        val total = node.intField("total_tokens")
        if (prompt == null && completion == null && total == null && details.isEmpty()) return null
        return LlmUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            details = details,
        )
    }

    /** The reported credit cost, rendered without scientific notation. */
    private fun costDetail(node: JsonNode.Obj): String? {
        val value = (node.get("cost") as? JsonNode.Num)?.value ?: return null
        if (!value.isFinite()) return null
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
    }

    /**
     * Maps a mid-stream OpenRouter error object to a typed error.
     *
     * OpenRouter tags provider failures with a stable `error.metadata.error_type`
     * vocabulary; that value is preferred over the numeric `code`. Only the stable
     * identifier is kept — the provider `message` is never copied into the detail
     * because it can echo the request.
     */
    private fun errorFrom(error: JsonNode.Obj): VoiceAgentError {
        val type = error.objField("metadata")?.stringField("error_type") ?: error.stringField("error_type")
        providerError(type)?.let { return it }
        return RemoteCodeMapper.errorFor(error.intField("code"))
    }

    private fun providerError(type: String?): VoiceAgentError? =
        when (type?.lowercase()) {
            "rate_limit_exceeded" -> VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the provider rate-limited the request")

            "provider_overloaded",
            "provider_unavailable",
            "server",
            "unmapped",
            -> VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider was unavailable")

            "timeout" -> VoiceAgentError(ErrorCode.LLM_TIMEOUT, "the provider timed out")

            "authentication" -> VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the provider rejected the credential")

            // HTTP 403 (guardrail/moderation/permission) shares the shared
            // transport's authentication mapping.
            "permission_denied" -> VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the provider rejected the request")

            "payment_required" -> VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the account has insufficient credits")

            "context_length_exceeded",
            "max_tokens_exceeded",
            "token_limit_exceeded",
            "string_too_long",
            "invalid_request",
            "invalid_prompt",
            "not_found",
            "precondition_failed",
            "payload_too_large",
            "unprocessable",
            "content_policy_violation",
            "refusal",
            -> VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the provider rejected the request")

            else -> null
        }

    /**
     * The documented top-level HTTP statuses a provider error can collapse to
     * when a streamed error carries no typed `error_type`. Mirrors
     * [com.voicechat.agent.remote.RemoteStatusMapper] so a mid-stream error and a
     * pre-stream failure classify the same way.
     */
    private object RemoteCodeMapper {
        fun errorFor(status: Int?): VoiceAgentError =
            when (status) {
                401, 403 -> VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the provider rejected the credential (HTTP $status)")
                408, 504 -> VoiceAgentError(ErrorCode.LLM_TIMEOUT, "the provider timed out (HTTP $status)")
                429 -> VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the provider rate-limited the request (HTTP 429)")
                in 500..599 -> VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider was unavailable (HTTP $status)")
                in 400..499 -> VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the provider rejected the request (HTTP $status)")
                else -> VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider reported an error")
            }
    }
}
