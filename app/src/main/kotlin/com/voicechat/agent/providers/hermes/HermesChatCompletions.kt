package com.voicechat.agent.providers.hermes

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
 * The verified Hermes Agent API Server Chat Completions mapping (M19).
 *
 * Everything provider-specific lives here: the endpoint path, the JSON payload,
 * the reasoning opt-out, the `hermes.tool.progress` named event, the `data:
 * [DONE]` sentinel, and the typed error dispatch. Nothing here touches HTTP, a
 * credential, or a log.
 *
 * Verified against the official Hermes Agent documentation on **2026-09-29**
 * (`docs/hermes-adapter.md` records the facts and sources):
 *
 * - `POST {base}/chat/completions`, standard OpenAI Chat Completions format;
 * - SSE with `chat.completion.chunk` frames, a custom `hermes.tool.progress`
 *   named event, and `: keepalive` comments every 10 s;
 * - reasoning deltas on `choices[0].delta.reasoning_content`;
 * - the stream ends with `data: [DONE]`;
 * - per-request `model` and `model_options.reasoning.enabled` opt-out.
 */
object HermesChatCompletions {
    /** The documented streaming completion endpoint, relative to the base URL. */
    const val PATH: String = "/chat/completions"

    /** The documented sentinel that terminates an OpenAI-compatible SSE stream. */
    const val DONE_SENTINEL: String = "[DONE]"

    /**
     * The documented custom named event Hermes emits for tool-start visibility.
     * It carries no assistant text and is never surfaced as a `Delta`.
     */
    const val TOOL_PROGRESS_EVENT: String = "hermes.tool.progress"

    /**
     * Encodes one request as the documented Chat Completions payload.
     *
     * `model` carries the selected Hermes model alias (typically the server's
     * advertised `hermes-agent`/profile name); the server may ignore a bare
     * `model` unless `direct_model_requests` is enabled, in which case its own
     * configured default — the same alias `GET /v1/models` advertises — serves
     * the request. The adapter never silently selects another model.
     *
     * `ReasoningLevel.NONE` is the **documented** input-side opt-out
     * (`model_options.reasoning.enabled = false`). A real level is never sent:
     * the exact accepted effort vocabulary is not documented, so the adapter
     * declares no reasoning level and [com.voicechat.agent.contracts.LlmRequestValidator]
     * refuses one before a request is built.
     */
    fun encodeRequest(request: LlmRequest): String {
        val messages = RemoteJson.arr(request.messages.map { encodeMessage(it) })
        val fields =
            linkedMapOf<String, JsonNode>(
                "model" to RemoteJson.str(request.model.modelId.value),
                "messages" to messages,
                "stream" to RemoteJson.bool(true),
            )
        if (request.reasoning == ReasoningLevel.NONE) {
            fields["model_options"] =
                RemoteJson.obj(
                    "reasoning" to RemoteJson.obj("enabled" to RemoteJson.bool(false)),
                )
        }
        return RemoteJson.stringify(RemoteJson.obj(fields))
    }

    private fun encodeMessage(message: LlmMessage): JsonNode =
        RemoteJson.obj(
            "role" to RemoteJson.str(roleWire(message.role)),
            "content" to RemoteJson.str(message.content),
        )

    /**
     * The Chat Completions wire role. Hermes documents standard Chat Completions
     * and layers a `system` message on top of its own core prompt, so a system
     * message stays `system` (it is not OpenAI's Responses `developer` role).
     */
    fun roleWire(role: LlmRole): String =
        when (role) {
            LlmRole.SYSTEM -> "system"
            LlmRole.USER -> "user"
            LlmRole.ASSISTANT -> "assistant"
        }

    /**
     * Interprets one SSE [frame] from a Hermes Chat Completions stream.
     *
     * `[DONE]` is checked before JSON parsing (it is not JSON). A named event
     * other than the default `message` (for example `hermes.tool.progress`) is
     * never assistant text, so it is [HermesStreamFrame.Ignored]. A frame whose
     * payload is not valid JSON becomes a typed `LLM_MALFORMED_RESPONSE` failure
     * rather than an exception escaping the adapter boundary.
     */
    fun parse(frame: SseFrame): HermesStreamFrame {
        if (frame.event != null && frame.event != "message") return HermesStreamFrame.Ignored
        if (frame.data.trim() == DONE_SENTINEL) return HermesStreamFrame.Done

        val root =
            try {
                RemoteJson.parse(frame.data)
            } catch (_: Exception) {
                return HermesStreamFrame.Failed(
                    VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the server sent a frame that was not valid JSON"),
                )
            }
        if (root !is JsonNode.Obj) return HermesStreamFrame.Ignored

        root.objField("error")?.let { error ->
            return HermesStreamFrame.Failed(errorFrom(error))
        }

        val choice = root.arrField("choices")?.items?.firstOrNull() as? JsonNode.Obj
        val delta = choice?.objField("delta")
        val content = delta?.stringField("content")
        if (!content.isNullOrEmpty()) return HermesStreamFrame.Delta(content)

        // The reasoning channel is never assistant text (R-0066).
        val reasoning = delta?.stringField("reasoning_content")
        if (!reasoning.isNullOrEmpty()) return HermesStreamFrame.Reasoning(reasoning)

        val finishReason = choice?.stringField("finish_reason")
        val usage = parseUsage(root.objField("usage"))
        val model = root.stringField("model")?.takeIf { it.isNotBlank() }?.let { ModelId(it) }
        if (finishReason != null || usage != null || model != null) {
            return HermesStreamFrame.Reported(model = model, usage = usage, finishReason = finishReason)
        }
        // A role-only opening chunk or an empty keep-alive chunk is not a delta.
        return HermesStreamFrame.Ignored
    }

    /**
     * Resolves the stream's termination once it stops.
     *
     * A `null` finish reason with a `[DONE]` sentinel means the server used the
     * OpenAI-compatible end-of-stream marker without a final reason chunk, which
     * is a completion. `stop` is the documented reason for a final answer. Every
     * other reason is not a whole answer: `length` is a truncated response, and
     * the rest (`content_filter`, `tool_calls`, `function_call`, …) are failures
     * this build does not surface as success.
     */
    fun outcomeFor(finishReason: String?): HermesCompletion =
        when (finishReason?.lowercase()) {
            null, "stop" -> {
                HermesCompletion.Completed
            }

            "length" -> {
                HermesCompletion.Failed(
                    VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the response was truncated before it finished"),
                )
            }

            "content_filter" -> {
                HermesCompletion.Failed(
                    VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the server filtered the response"),
                )
            }

            else -> {
                HermesCompletion.Failed(
                    VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the server stopped the stream"),
                )
            }
        }

    /** The typed error for a mid-stream OpenAI-style `error` object. */
    private fun errorFrom(error: JsonNode.Obj): VoiceAgentError {
        val type = error.stringField("type") ?: error.stringField("code")
        return HermesErrors.forType(type) ?: HermesErrors.unclassified()
    }

    private fun parseUsage(node: JsonNode.Obj?): LlmUsage? {
        if (node == null) return null
        val details = linkedMapOf<String, String>()
        node.objField("prompt_tokens_details")?.intField("cached_tokens")?.let { details["cachedInputTokens"] = it.toString() }
        node.objField("completion_tokens_details")?.intField("reasoning_tokens")?.let { details["reasoningTokens"] = it.toString() }
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
}
