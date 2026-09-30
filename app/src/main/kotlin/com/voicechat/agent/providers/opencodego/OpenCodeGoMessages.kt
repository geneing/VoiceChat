package com.voicechat.agent.providers.opencodego

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
 * The Anthropic Messages mapping for OpenCode Go models documented with the
 * `@ai-sdk/anthropic` package (MiniMax, Qwen).
 *
 * Messages is the third, distinct protocol inside Go: a top-level `system`
 * string instead of a system message, `max_tokens` required on every request,
 * and its own SSE event names (`message_start`, `content_block_delta`,
 * `message_delta`, `message_stop`, `error`). The Go page names the Anthropic
 * protocol but not its event list; this mapping follows the Messages protocol
 * the named package implements.
 *
 * `max_tokens` is required by the Messages protocol but is not part of the M12
 * contract, so a bounded default is sent and the gap is tracked (R-0133). A
 * `thinking_delta` is a reasoning channel and is ignored, never assistant text.
 */
object OpenCodeGoMessages {
    /** The documented completion endpoint path. */
    const val PATH: String = "/messages"

    /**
     * A bounded `max_tokens`, required by the Messages protocol but not carried
     * by [LlmRequest]. Its value is an implementation default, not a Go-verified
     * figure (R-0133).
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
    fun parse(frame: SseFrame): OpenCodeGoStreamFrame {
        val root =
            try {
                RemoteJson.parse(frame.data)
            } catch (_: Exception) {
                return malformed()
            }
        if (root !is JsonNode.Obj) return OpenCodeGoStreamFrame.Ignored
        val type = root.stringField("type") ?: frame.event ?: ""
        return when (type) {
            "message_start" -> {
                val message = root.objField("message")
                val model = message?.stringField("model")?.takeIf { it.isNotBlank() }?.let { ModelId(it) }
                val usage = parseUsage(message?.objField("usage"))
                if (model == null && usage == null) OpenCodeGoStreamFrame.Ignored else OpenCodeGoStreamFrame.Reported(model, usage)
            }

            "content_block_delta" -> {
                val delta = root.objField("delta")
                // A `thinking_delta` is the reasoning channel, not assistant text (R-0066).
                if (delta?.stringField("type") == "text_delta") {
                    OpenCodeGoStreamFrame.Delta(delta.stringField("text").orEmpty())
                } else {
                    OpenCodeGoStreamFrame.Ignored
                }
            }

            "message_delta" -> {
                val usage = parseUsage(root.objField("usage"))
                if (usage == null) OpenCodeGoStreamFrame.Ignored else OpenCodeGoStreamFrame.Reported(null, usage)
            }

            "message_stop" -> {
                OpenCodeGoStreamFrame.Completed(model = null, usage = null)
            }

            "error" -> {
                val error = root.objField("error")
                OpenCodeGoStreamFrame.Failed(
                    OpenCodeGoErrors.forCode(error?.stringField("type") ?: root.stringField("type"))
                        ?: OpenCodeGoErrors.unclassified(),
                )
            }

            // content_block_start/stop, ping, and any future block marker carry no text.
            else -> {
                OpenCodeGoStreamFrame.Ignored
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

    private fun malformed(): OpenCodeGoStreamFrame.Failed =
        OpenCodeGoStreamFrame.Failed(
            VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider sent a frame that was not valid JSON"),
        )
}
