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
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.intField
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField

/**
 * The OpenAI-compatible Chat Completions mapping for OpenCode Zen models
 * documented with the `@ai-sdk/openai-compatible` package (Qwen3.8 Max,
 * DeepSeek V4, MiniMax, GLM, Kimi, and the free models).
 *
 * The Zen page names the compatible protocol but does not print its SSE example,
 * so this mapping follows the Chat Completions protocol the named package
 * implements: a `chat.completion.chunk` per delta and a `data: [DONE]` sentinel
 * that ends the stream. It is a **distinct** mapping from the Responses family
 * inside the same provider; the two are selected per model, never mixed.
 */
object OpenCodeZenChatCompletions {
    fun encodeRequest(request: LlmRequest): String {
        val messages = RemoteJson.arr(request.messages.map { encodeMessage(it) })
        val fields =
            linkedMapOf<String, JsonNode>(
                "model" to RemoteJson.str(request.model.modelId.value),
                "messages" to messages,
                "stream" to RemoteJson.bool(true),
            )
        return RemoteJson.stringify(RemoteJson.obj(fields))
    }

    private fun encodeMessage(message: LlmMessage): JsonNode =
        RemoteJson.obj(
            "role" to RemoteJson.str(roleWire(message.role)),
            "content" to RemoteJson.str(message.content),
        )

    /** The Chat Completions wire role; unlike Responses, a system prompt stays `system`. */
    fun roleWire(role: LlmRole): String =
        when (role) {
            LlmRole.SYSTEM -> "system"
            LlmRole.USER -> "user"
            LlmRole.ASSISTANT -> "assistant"
        }

    /**
     * Interprets one SSE [frame]. A frame whose payload is not valid JSON, or an
     * `error` object, becomes a typed failure rather than escaping the adapter.
     */
    fun parse(frame: SseFrame): OpenCodeZenStreamFrame {
        val data = frame.data
        if (data == DONE_SENTINEL) {
            return OpenCodeZenStreamFrame.Completed(model = null, usage = null)
        }
        val root =
            try {
                RemoteJson.parse(data)
            } catch (_: Exception) {
                return malformed()
            }
        if (root !is JsonNode.Obj) return OpenCodeZenStreamFrame.Ignored
        root.objField("error")?.let { error ->
            return OpenCodeZenStreamFrame.Failed(
                OpenCodeZenErrors.forCode(error.stringField("code") ?: error.stringField("type"))
                    ?: OpenCodeZenErrors.unclassified(),
            )
        }
        val choice = root.arrField("choices")?.items?.firstOrNull() as? JsonNode.Obj
        val content = choice?.objField("delta")?.stringField("content")
        val model = root.stringField("model")?.takeIf { it.isNotBlank() }?.let { ModelId(it) }
        if (!content.isNullOrEmpty()) {
            return OpenCodeZenStreamFrame.Delta(text = content, model = model)
        }
        val usage = parseUsage(root.objField("usage"))
        if (model != null || usage != null) {
            return OpenCodeZenStreamFrame.Reported(model = model, usage = usage)
        }
        // A role-only opening chunk or an empty keep-alive chunk is not a delta.
        return OpenCodeZenStreamFrame.Ignored
    }

    private fun parseUsage(node: JsonNode.Obj?): LlmUsage? {
        if (node == null) return null
        val prompt = node.intField("prompt_tokens")
        val completion = node.intField("completion_tokens")
        val total = node.intField("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        return LlmUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
    }

    private fun malformed(): OpenCodeZenStreamFrame.Failed =
        OpenCodeZenStreamFrame.Failed(
            VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider sent a frame that was not valid JSON"),
        )

    /** The documented Chat Completions end-of-stream sentinel. */
    const val DONE_SENTINEL: String = "[DONE]"
}
