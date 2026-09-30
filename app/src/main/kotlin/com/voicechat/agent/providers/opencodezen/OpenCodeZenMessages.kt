package com.voicechat.agent.providers.opencodezen

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.LlmUsage
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.SseFrame
import com.voicechat.agent.remote.intField
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField

/**
 * The Anthropic Messages mapping for OpenCode Zen models documented with the
 * `@ai-sdk/anthropic` package (the Claude family, Qwen3.8 Flash, and Qwen3.7/3.6/3.5 Plus).
 *
 * Messages is the third, distinct protocol inside Zen: a top-level `system`
 * string instead of a system message, `max_tokens` required on every request,
 * and its own SSE event names (`message_start`, `content_block_delta`,
 * `message_delta`, `message_stop`, `error`). The Zen page names the Anthropic
 * protocol but not its event list; this mapping follows the Messages protocol
 * the named package implements.
 *
 * `max_tokens` is required by the Messages protocol but is not part of the M12
 * contract, so a bounded default is sent and the gap is tracked (R-0143). A
 * `thinking_delta` is a reasoning channel and is ignored, never assistant text.
 */
object OpenCodeZenMessages {
    /** The documented completion endpoint path. */
    const val PATH: String = "/messages"

    /**
     * A bounded `max_tokens`, required by the Messages protocol but not carried
     * by [LlmRequest]. Its value is an implementation default, not a Zen-verified
     * figure (R-0143).
     */
    const val DEFAULT_MAX_TOKENS: Int = 4096

    fun encodeRequest(request: LlmRequest): String {
        val system = request.messages.filter { it.role == LlmRole.SYSTEM }.joinToString("\n\n") { it.content }
        val conversation = request.messages.filter { it.role != LlmRole.SYSTEM }.map { encodeMessage(it) }
        val fields =
            linkedMapOf<String, JsonNode>(
                "model" to RemoteJson.str(request.model.modelId.value),
                "max_tokens" to RemoteJson.int(DEFAULT_MAX_TOKENS),
                "messages" to RemoteJson.arr(conversation),
                "stream" to RemoteJson.bool(true),
            )
        if (system.isNotEmpty()) fields["system"] = RemoteJson.str(system)
        return RemoteJson.stringify(RemoteJson.obj(fields))
    }

    private fun encodeMessage(message: LlmMessage): JsonNode =
        RemoteJson.obj(
            "role" to RemoteJson.str(roleWire(message.role)),
            "content" to RemoteJson.str(message.content),
        )

    /** The Messages wire role; a system message is lifted to the top level instead. */
    fun roleWire(role: LlmRole): String =
        when (role) {
            LlmRole.SYSTEM -> "system"
            LlmRole.USER -> "user"
            LlmRole.ASSISTANT -> "assistant"
        }

    /** Interprets one SSE [frame] from a Messages stream. */
    fun parse(frame: SseFrame): OpenCodeZenStreamFrame {
        val root =
            try {
                RemoteJson.parse(frame.data)
            } catch (_: Exception) {
                return malformed()
            }
        if (root !is JsonNode.Obj) return OpenCodeZenStreamFrame.Ignored
        val type = root.stringField("type") ?: frame.event ?: ""
        return when (type) {
            "message_start" -> {
                val message = root.objField("message")
                val model = message?.stringField("model")?.takeIf { it.isNotBlank() }?.let { ModelId(it) }
                val usage = parseUsage(message?.objField("usage"))
                if (model == null && usage == null) {
                    OpenCodeZenStreamFrame.Ignored
                } else {
                    OpenCodeZenStreamFrame.Reported(model, usage)
                }
            }

            "content_block_delta" -> {
                val delta = root.objField("delta")
                // A `thinking_delta` is the reasoning channel, not assistant text (R-0066).
                if (delta?.stringField("type") == "text_delta") {
                    OpenCodeZenStreamFrame.Delta(delta.stringField("text").orEmpty())
                } else {
                    OpenCodeZenStreamFrame.Ignored
                }
            }

            "message_delta" -> {
                val usage = parseUsage(root.objField("usage"))
                if (usage == null) {
                    OpenCodeZenStreamFrame.Ignored
                } else {
                    OpenCodeZenStreamFrame.Reported(null, usage)
                }
            }

            "message_stop" -> {
                OpenCodeZenStreamFrame.Completed(model = null, usage = null)
            }

            "error" -> {
                val error = root.objField("error")
                OpenCodeZenStreamFrame.Failed(
                    OpenCodeZenErrors.forCode(error?.stringField("type") ?: root.stringField("type"))
                        ?: OpenCodeZenErrors.unclassified(),
                )
            }

            // content_block_start/stop, ping, and any future block marker carry no text.
            else -> {
                OpenCodeZenStreamFrame.Ignored
            }
        }
    }

    private fun parseUsage(node: JsonNode.Obj?): LlmUsage? {
        if (node == null) return null
        val prompt = node.intField("input_tokens")
        val completion = node.intField("output_tokens")
        if (prompt == null && completion == null) return null
        return LlmUsage(promptTokens = prompt, completionTokens = completion, totalTokens = null)
    }

    private fun malformed(): OpenCodeZenStreamFrame.Failed =
        OpenCodeZenStreamFrame.Failed(
            VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider sent a frame that was not valid JSON"),
        )
}
