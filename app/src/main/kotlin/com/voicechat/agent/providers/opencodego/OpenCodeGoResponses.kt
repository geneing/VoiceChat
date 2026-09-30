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
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.intField
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField

/**
 * The OpenAI Responses mapping for OpenCode Go models documented with the
 * `@ai-sdk/openai` package (Grok, GPT Luna, Muse Spark).
 *
 * Responses is a **different** protocol from Chat Completions inside the same
 * provider, so it has its own request payload and typed-event dispatch. `store`
 * is always `false` (R-0018): the Responses API retains response data when
 * `store` is true and it defaults to true, so the privacy rule is enforced here
 * rather than left to chance.
 *
 * The Go page names the Responses protocol but not its event list; this mapping
 * follows the Responses protocol the named package implements. Reasoning events
 * are ignored, never concatenated into assistant text (R-0066).
 */
object OpenCodeGoResponses {
    /** The documented streaming completion endpoint path. */
    const val PATH: String = "/responses"

    fun encodeRequest(request: LlmRequest): String {
        val input = RemoteJson.arr(request.messages.map { encodeMessage(it) })
        val fields =
            linkedMapOf<String, JsonNode>(
                "model" to RemoteJson.str(request.model.modelId.value),
                "input" to input,
                "store" to RemoteJson.bool(false),
                "stream" to RemoteJson.bool(true),
            )
        return RemoteJson.stringify(RemoteJson.obj(fields))
    }

    private fun encodeMessage(message: LlmMessage): JsonNode =
        RemoteJson.obj(
            "type" to RemoteJson.str("message"),
            "role" to RemoteJson.str(roleWire(message.role)),
            "content" to RemoteJson.str(message.content),
        )

    /** A system prompt maps to `developer`, the Responses instruction-priority role. */
    fun roleWire(role: LlmRole): String =
        when (role) {
            LlmRole.SYSTEM -> "developer"
            LlmRole.USER -> "user"
            LlmRole.ASSISTANT -> "assistant"
        }

    /** Interprets one SSE [frame] from a Responses stream. */
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
            "response.output_text.delta",
            "response.refusal.delta",
            -> {
                OpenCodeGoStreamFrame.Delta(root.stringField("delta").orEmpty())
            }

            // Reasoning is a separate channel; it is never assistant text (R-0066).
            "response.reasoning_summary_text.delta",
            "response.reasoning_text.delta",
            -> {
                OpenCodeGoStreamFrame.Ignored
            }

            "response.completed" -> {
                val response = root.objField("response")
                OpenCodeGoStreamFrame.Completed(
                    model = response?.stringField("model")?.takeIf { it.isNotBlank() }?.let { ModelId(it) },
                    usage = parseUsage(response?.objField("usage")),
                )
            }

            "response.incomplete" -> {
                OpenCodeGoStreamFrame.Failed(
                    VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider reported an incomplete response"),
                )
            }

            "response.failed" -> {
                val code = root.objField("response")?.objField("error")?.stringField("code")
                OpenCodeGoStreamFrame.Failed(OpenCodeGoErrors.forCode(code) ?: OpenCodeGoErrors.unclassified())
            }

            "error" -> {
                OpenCodeGoStreamFrame.Failed(
                    OpenCodeGoErrors.forCode(root.stringField("code") ?: root.stringField("type"))
                        ?: OpenCodeGoErrors.unclassified(),
                )
            }

            else -> {
                OpenCodeGoStreamFrame.Ignored
            }
        }
    }

    private fun parseUsage(node: JsonNode.Obj?): LlmUsage? {
        if (node == null) return null
        val prompt = node.intField("input_tokens")
        val completion = node.intField("output_tokens")
        val total = node.intField("total_tokens")
        if (prompt == null && completion == null && total == null) return null
        return LlmUsage(promptTokens = prompt, completionTokens = completion, totalTokens = total)
    }

    private fun malformed(): OpenCodeGoStreamFrame.Failed =
        OpenCodeGoStreamFrame.Failed(
            VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the provider sent a frame that was not valid JSON"),
        )
}
