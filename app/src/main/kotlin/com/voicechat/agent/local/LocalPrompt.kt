package com.voicechat.agent.local

import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole

/**
 * Flattens a bounded [LlmRequest] into one prompt for a local runtime (M20).
 *
 * The M12 context is role-tagged message text. The Prompt/LiteRT-LM APIs each
 * have their own multi-message surfaces, but this adapter deliberately sends one
 * deterministic prompt built from the app's own bounded context rather than
 * relying on a beta API's conversation state. It is pure so the mapping is a JVM
 * test, and it carries only text the app already decided to send.
 */
object LocalPrompt {
    /** Renders [request]'s messages in order, role-labelled. */
    fun render(request: LlmRequest): String =
        request.messages.joinToString(separator = "\n") { message ->
            "${message.role.label()}: ${message.content}"
        }

    private fun LlmRole.label(): String =
        when (this) {
            LlmRole.SYSTEM -> "SYSTEM"
            LlmRole.USER -> "USER"
            LlmRole.ASSISTANT -> "ASSISTANT"
        }
}
