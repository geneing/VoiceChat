package com.voicechat.agent.providers.openai

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
import com.voicechat.agent.remote.intField
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField

/**
 * One interpreted frame from an OpenAI Responses stream.
 *
 * The reasoning channel is a distinct case on purpose: `response.reasoning_*`
 * deltas are **not** assistant text, so an adapter excludes them from `Delta`
 * instead of concatenating them (risk R-0066). The contract has no reasoning
 * side channel yet, so the content is dropped and only the reported effort
 * (from `response.completed`) is surfaced.
 */
sealed interface OpenAiStreamFrame {
    /** Assistant output text (or a refusal) to surface as a `Delta`. */
    data class Text(
        val text: String,
    ) : OpenAiStreamFrame

    /** A reasoning/thinking delta; excluded from assistant text. */
    data class Reasoning(
        val text: String,
    ) : OpenAiStreamFrame

    /** The response completed; carries reported usage/model/reasoning. */
    data class Completed(
        val usage: LlmUsage?,
        val model: ModelId?,
        val reasoning: ReasoningLevel?,
    ) : OpenAiStreamFrame

    /** The provider reported a terminal failure. */
    data class Failed(
        val error: VoiceAgentError,
    ) : OpenAiStreamFrame

    /** A frame with no contract-relevant content (created/in_progress/tool events). */
    data object Ignored : OpenAiStreamFrame
}

/**
 * The verified OpenAI Responses request/stream mapping (M14).
 *
 * Everything provider-specific lives here: the endpoint path, the JSON payload,
 * the reasoning-effort spelling, and the typed-event dispatch. Nothing here
 * touches HTTP, a credential, or a log. The verified facts and their sources are
 * in `docs/openai-adapter.md`.
 */
object OpenAiResponses {
    /** The documented streaming completion endpoint, relative to the base URL. */
    const val PATH: String = "/responses"

    /**
     * Encodes one request as the Responses API payload.
     *
     * `store` is always `false` (R-0018): the API stores response data for at
     * least 30 days when `store` is true, and it defaults to true when omitted,
     * so the privacy requirement is enforced here rather than left to chance.
     *
     * `reasoning.effort` is sent only for a real effort level. `ReasoningLevel.NONE`
     * omits the field entirely, because some models reject `"none"` with HTTP 400
     * and "do not reason" is expressed by the model default.
     */
    fun encodeRequest(request: LlmRequest): String {
        val input = RemoteJson.arr(request.messages.map { encodeMessage(it) })
        val fields =
            linkedMapOf<String, JsonNode>(
                "model" to RemoteJson.str(request.model.modelId.value),
                "input" to input,
                "store" to RemoteJson.bool(false),
                "stream" to RemoteJson.bool(true),
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
            "type" to RemoteJson.str("message"),
            "role" to RemoteJson.str(roleWire(message.role)),
            "content" to RemoteJson.str(message.content),
        )

    /**
     * The wire role for an [LlmRole]. A system prompt is sent as `developer`,
     * which is the Responses API role that takes instruction precedence and
     * replaces `system` for current models; `system` remains accepted but is not
     * used.
     */
    fun roleWire(role: LlmRole): String =
        when (role) {
            LlmRole.SYSTEM -> "developer"
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
     * Interprets one SSE [frame] from a Responses stream.
     *
     * A frame whose payload is not valid JSON becomes a typed
     * `LLM_MALFORMED_RESPONSE` failure rather than an exception escaping the
     * adapter boundary. The payload's `type` field is preferred over the SSE
     * `event:` name (they agree; the body is authoritative for the API version).
     */
    fun parse(frame: SseFrame): OpenAiStreamFrame {
        val root =
            try {
                RemoteJson.parse(frame.data)
            } catch (_: Exception) {
                return OpenAiStreamFrame.Failed(
                    VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider sent a frame that was not valid JSON"),
                )
            }
        if (root !is JsonNode.Obj) return OpenAiStreamFrame.Ignored
        val type = root.stringField("type") ?: frame.event ?: ""
        return when (type) {
            "response.output_text.delta" -> {
                OpenAiStreamFrame.Text(root.stringField("delta").orEmpty())
            }

            "response.refusal.delta" -> {
                OpenAiStreamFrame.Text(root.stringField("delta").orEmpty())
            }

            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> {
                OpenAiStreamFrame.Reasoning(root.stringField("delta").orEmpty())
            }

            "response.completed" -> {
                completed(root)
            }

            "response.incomplete" -> {
                OpenAiStreamFrame.Failed(
                    VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider reported an incomplete response"),
                )
            }

            "response.failed" -> {
                OpenAiStreamFrame.Failed(responseError(root) ?: unavailable())
            }

            "error" -> {
                OpenAiStreamFrame.Failed(errorEvent(root))
            }

            else -> {
                OpenAiStreamFrame.Ignored
            }
        }
    }

    private fun completed(root: JsonNode.Obj): OpenAiStreamFrame.Completed {
        val response = root.objField("response")
        val usage = parseUsage(response?.objField("usage"))
        val model =
            response
                ?.stringField("model")
                ?.takeIf { it.isNotBlank() }
                ?.let { ModelId(it) }
        val reasoning = reasoningFromWire(response?.objField("reasoning")?.stringField("effort"))
        return OpenAiStreamFrame.Completed(usage = usage, model = model, reasoning = reasoning)
    }

    private fun parseUsage(node: JsonNode.Obj?): LlmUsage? {
        if (node == null) return null
        val details = linkedMapOf<String, String>()
        node.objField("output_tokens_details")?.intField("reasoning_tokens")?.let { details["reasoningTokens"] = it.toString() }
        node.objField("input_tokens_details")?.intField("cached_tokens")?.let { details["cachedInputTokens"] = it.toString() }
        val prompt = node.intField("input_tokens")
        val completion = node.intField("output_tokens")
        val total = node.intField("total_tokens")
        if (prompt == null && completion == null && total == null && details.isEmpty()) return null
        return LlmUsage(
            promptTokens = prompt,
            completionTokens = completion,
            totalTokens = total,
            details = details,
        )
    }

    private fun responseError(root: JsonNode.Obj): VoiceAgentError? {
        val code = root.objField("response")?.objField("error")?.stringField("code")
        return code?.let { providerError(it) }
    }

    private fun errorEvent(root: JsonNode.Obj): VoiceAgentError {
        val code = root.stringField("code")
        return code?.let { providerError(it) } ?: VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider reported an error")
    }

    /**
     * Maps a provider error [code] to a typed error. Only the stable code
     * identifier is kept; the provider `message` is never copied into the detail
     * because it can echo the request.
     */
    private fun providerError(code: String): VoiceAgentError =
        when (code.lowercase()) {
            "rate_limit_exceeded" -> VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the provider rate-limited the request")

            "server_error" -> unavailable()

            "invalid_prompt",
            "invalid_request_error",
            "invalid_request",
            -> VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the provider rejected the request")

            "invalid_api_key",
            "authentication_error",
            -> VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the provider rejected the credential")

            else -> VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider reported an error ($code)")
        }

    private fun unavailable() = VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider was unavailable")
}

/**
 * The verified model-discovery surface (`GET /v1/models`).
 *
 * The list carries model identities only; which reasoning levels a model exposes
 * is not part of this response, so the adapter uses the verified provider union
 * (`ProviderCapabilityRegistry`) and the per-model catalog stays an M22 concern.
 */
object OpenAiModels {
    /** The documented model-list endpoint, relative to the base URL. */
    const val PATH: String = "/models"

    /** Parses the `data[].id` values from a `/models` response body. */
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
