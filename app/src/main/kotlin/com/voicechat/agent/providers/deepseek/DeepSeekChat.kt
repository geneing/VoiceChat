package com.voicechat.agent.providers.deepseek

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
 * One interpreted frame from a DeepSeek Chat Completions stream.
 *
 * DeepSeek's stream is **not** the OpenAI Responses event model: it is
 * OpenAI-*Chat-Completions*-shaped data-only SSE, the terminal signal is the
 * chunk whose `choices[].finish_reason` is non-null (not a typed event), and the
 * stream is followed by a literal `data: [DONE]` sentinel. The reasoning channel
 * is a distinct case on purpose: `choices[].delta.reasoning_content` is **not**
 * assistant text, so an adapter excludes it instead of concatenating it (risk
 * R-0066). The contract has no reasoning side channel yet, so the content is
 * dropped.
 *
 * A single SSE frame can carry more than one contract-relevant meaning (the
 * documented last chunk carries the finish reason and the usage together, and
 * defensively it may also carry a final content delta), so [DeepSeekChat.parse]
 * returns a list in wire order.
 */
sealed interface DeepSeekStreamFrame {
    /** Assistant output text (`choices[].delta.content`) to surface as a `Delta`. */
    data class Text(
        val text: String,
    ) : DeepSeekStreamFrame

    /** A reasoning/thinking delta (`choices[].delta.reasoning_content`); excluded from assistant text. */
    data class Reasoning(
        val text: String,
    ) : DeepSeekStreamFrame

    /** The generation finished; carries the reported usage/model and the finish reason. */
    data class Completed(
        val usage: LlmUsage?,
        val model: ModelId?,
        val finishReason: String,
    ) : DeepSeekStreamFrame

    /** The `data: [DONE]` transport sentinel. */
    data object Done : DeepSeekStreamFrame

    /** The provider reported a failure (a JSON `error` body or a failed finish reason). */
    data class Failed(
        val error: VoiceAgentError,
    ) : DeepSeekStreamFrame

    /** A frame with no contract-relevant content (role-only first chunk, keep-alive, etc.). */
    data object Ignored : DeepSeekStreamFrame
}

/**
 * The verified DeepSeek Chat Completions request/stream mapping (M16).
 *
 * Everything provider-specific lives here: the endpoint path, the JSON payload,
 * the thinking/effort spelling, and the per-chunk event dispatch. Nothing here
 * touches HTTP, a credential, or a log. The verified facts and their sources are
 * in `docs/deepseek-adapter.md` (accessed 2026-09-29). The mapping is taken from
 * DeepSeek's own API reference and thinking-mode guide; it is **not** copied
 * from the OpenAI Responses adapter, whose event model and request shape differ.
 */
object DeepSeekChat {
    /** The documented streaming chat endpoint, relative to the base URL. */
    const val PATH: String = "/chat/completions"

    /**
     * Encodes one request as the DeepSeek Chat Completions payload.
     *
     * DeepSeek's Chat Completions API is stateless and documents no storage
     * option, so no `store`-style field is sent. `stream: true` is always set.
     *
     * Reasoning is expressed with DeepSeek's own two parameters:
     * - `reasoning == null`: neither parameter is sent, so the provider default
     *   (thinking enabled, effort `high`) applies;
     * - [ReasoningLevel.NONE]: `thinking.type = disabled`, which is the
     *   documented way to switch to non-thinking mode;
     * - a real level: `thinking.type = enabled` plus `reasoning_effort` set to
     *   the level's documented spelling.
     */
    fun encodeRequest(request: LlmRequest): String {
        val fields =
            linkedMapOf<String, JsonNode>(
                "model" to RemoteJson.str(request.model.modelId.value),
                "messages" to RemoteJson.arr(request.messages.map { encodeMessage(it) }),
                "stream" to RemoteJson.bool(true),
            )
        request.reasoning?.let { level ->
            if (level == ReasoningLevel.NONE) {
                fields["thinking"] = RemoteJson.obj("type" to RemoteJson.str("disabled"))
            } else {
                fields["thinking"] = RemoteJson.obj("type" to RemoteJson.str("enabled"))
                fields["reasoning_effort"] = RemoteJson.str(effortWire(level))
            }
        }
        return RemoteJson.stringify(RemoteJson.obj(fields))
    }

    private fun encodeMessage(message: LlmMessage): JsonNode =
        RemoteJson.obj(
            "role" to RemoteJson.str(roleWire(message.role)),
            "content" to RemoteJson.str(message.content),
        )

    /**
     * The wire role for an [LlmRole]. DeepSeek's Chat Completions API documents
     * `system`, `user`, and `assistant` — it has no `developer` role, so a
     * `SYSTEM` message is sent as `system` (unlike OpenAI's Responses API).
     */
    fun roleWire(role: LlmRole): String =
        when (role) {
            LlmRole.SYSTEM -> "system"
            LlmRole.USER -> "user"
            LlmRole.ASSISTANT -> "assistant"
        }

    /**
     * The documented `reasoning_effort` spelling for [level].
     *
     * The reference lists `none`, `low`, `high`, and `max` as the possible
     * values and states that `minimal` is accepted and mapped to `low`, and
     * `medium`/`xhigh` are accepted and mapped to `high`. Every level this app
     * exposes is therefore accepted; the exact spelling is sent so the request
     * says what the user chose, and the provider owns the mapping.
     */
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

    /**
     * Interprets one SSE [frame] from a Chat Completions stream into zero or
     * more contract-relevant frames, in wire order.
     *
     * A frame whose payload is not valid JSON becomes a typed
     * `LLM_MALFORMED_RESPONSE` failure rather than an exception escaping the
     * adapter boundary. A JSON `error` object (a provider error delivered in a
     * 200 body) maps to a typed failure using only its stable `code`/`type`; the
     * provider `message` is never copied because it can echo the request. The
     * literal `data: [DONE]` sentinel is [DeepSeekStreamFrame.Done]; it carries
     * no payload and is not itself a completion.
     */
    fun parse(frame: SseFrame): List<DeepSeekStreamFrame> {
        if (frame.data == DONE_SENTINEL) return listOf(DeepSeekStreamFrame.Done)
        val root =
            try {
                RemoteJson.parse(frame.data)
            } catch (_: Exception) {
                return listOf(
                    DeepSeekStreamFrame.Failed(
                        VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider sent a frame that was not valid JSON"),
                    ),
                )
            }
        if (root !is JsonNode.Obj) return listOf(DeepSeekStreamFrame.Ignored)

        root.objField("error")?.let { return listOf(DeepSeekStreamFrame.Failed(errorFromBody(it))) }

        val choice = root.arrField("choices")?.items?.firstOrNull() as? JsonNode.Obj
        if (choice == null) return listOf(DeepSeekStreamFrame.Ignored)

        val frames = mutableListOf<DeepSeekStreamFrame>()
        choice.objField("delta")?.let { delta ->
            delta.stringField("content")?.takeIf { it.isNotEmpty() }?.let { frames += DeepSeekStreamFrame.Text(it) }
            // Thinking mode returns the chain of thought on its own field; it is
            // never assistant text (R-0066).
            delta.stringField("reasoning_content")?.takeIf { it.isNotEmpty() }?.let { frames += DeepSeekStreamFrame.Reasoning(it) }
        }

        val finishReason = choice.stringField("finish_reason")
        if (finishReason != null) {
            val failure = failureForFinishReason(finishReason)
            if (failure != null) {
                frames += DeepSeekStreamFrame.Failed(failure)
            } else {
                frames +=
                    DeepSeekStreamFrame.Completed(
                        usage = parseUsage(root.objField("usage")),
                        model = root.stringField("model")?.takeIf { it.isNotBlank() }?.let { ModelId(it) },
                        finishReason = finishReason,
                    )
            }
        }
        return frames.ifEmpty { listOf(DeepSeekStreamFrame.Ignored) }
    }

    private fun parseUsage(node: JsonNode.Obj?): LlmUsage? {
        if (node == null) return null
        val details = linkedMapOf<String, String>()
        node.intField("prompt_cache_hit_tokens")?.let { details["cachedInputTokens"] = it.toString() }
        node.intField("prompt_cache_miss_tokens")?.let { details["uncachedInputTokens"] = it.toString() }
        node.objField("completion_tokens_details")?.intField("reasoning_tokens")?.let {
            details["reasoningTokens"] = it.toString()
        }
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

    /**
     * Maps a DeepSeek `finish_reason` to a typed error, or `null` for a normal
     * stop. `length` means the response was truncated and is therefore **not**
     * reported as a whole completion (it becomes a typed failure carrying the
     * partial text), matching the contract's rule that `Completed` means the
     * response is whole.
     */
    private fun failureForFinishReason(reason: String): VoiceAgentError? =
        when (reason.lowercase()) {
            "stop" -> null
            "length" -> VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider truncated the response at the token limit")
            "content_filter" -> VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider filtered the response content")
            "insufficient_system_resource" -> VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider had insufficient system resources")
            "aborted" -> VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider aborted the generation")
            else -> VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider stopped the generation for an unrecognized reason")
        }

    /**
     * Maps a provider `error` body to a typed error using its stable
     * `code`/`type` only. The provider `message` is never copied.
     */
    private fun errorFromBody(error: JsonNode.Obj): VoiceAgentError {
        val code = (error.stringField("code") ?: error.stringField("type"))?.lowercase()
        return when (code) {
            "insufficient_balance", "insufficient_funds" -> {
                VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider account has insufficient balance")
            }

            "rate_limit_exceeded", "rate_limit" -> {
                VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the provider rate-limited the request")
            }

            "authentication_error", "invalid_api_key" -> {
                VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the provider rejected the credential")
            }

            "invalid_request_error", "invalid_request", "invalid_parameters" -> {
                VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the provider rejected the request")
            }

            "server_error" -> {
                VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider was unavailable")
            }

            else -> {
                VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider reported an error")
            }
        }
    }

    /** The literal SSE sentinel that ends a Chat Completions stream. */
    const val DONE_SENTINEL: String = "[DONE]"
}

/**
 * DeepSeek's model-discovery surface (`GET /models`).
 *
 * The response is a `{ "object": "list", "data": [ { "id", ... } ] }` body that
 * additionally carries context window, modalities, and per-model effort levels.
 * M16 needs only the identities for selection and the credential check; the
 * richer per-model metadata (reasoning levels) is refined by
 * `LlmCapabilityReconciler` and is an M22/M13 concern (see R-0123).
 */
object DeepSeekModels {
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
