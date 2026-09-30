package com.voicechat.agent.providers.opencodego

import com.voicechat.agent.contracts.LlmUsage
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.VoiceAgentError

/**
 * One interpreted frame from any OpenCode Go family stream, normalized so the
 * adapter can fold all three protocols uniformly.
 *
 * A single frame never both carries assistant text and ends the stream: the
 * terminal event for a family is its own documented end-of-stream marker
 * (`data: [DONE]` for Chat Completions, `response.completed` for Responses,
 * `message_stop` for Anthropic Messages), and the serving model/usage may arrive
 * on a *different* frame than the terminal one. [Reported] carries those
 * metadata observations, and the adapter merges the last one into the final
 * `Completed` event.
 */
sealed interface OpenCodeGoStreamFrame {
    /** Incremental assistant text to surface as a `Delta`. */
    data class Delta(
        val text: String,
        /** The serving model when the frame names it (Chat Completions does). */
        val model: ModelId? = null,
    ) : OpenCodeGoStreamFrame

    /**
     * A non-terminal metadata observation (serving model, usage). Both fields are
     * optional; a frame that reports neither is [Ignored] instead.
     */
    data class Reported(
        val model: ModelId?,
        val usage: LlmUsage?,
    ) : OpenCodeGoStreamFrame

    /** The provider's end-of-stream marker; the response is whole. */
    data class Completed(
        val model: ModelId?,
        val usage: LlmUsage?,
    ) : OpenCodeGoStreamFrame

    /** The provider reported a terminal error. */
    data class Failed(
        val error: VoiceAgentError,
    ) : OpenCodeGoStreamFrame

    /** A frame with no contract-relevant content (keep-alives, block markers). */
    data object Ignored : OpenCodeGoStreamFrame
}

/**
 * Maps an OpenCode Go provider error code/type to a typed error.
 *
 * The three families spell error codes differently — OpenAI-style `code` values
 * for Chat Completions/Responses and Anthropic-style `type` values for Messages
 * — so one mapping table covers both vocabularies. Only the stable identifier is
 * read; the provider `message` is never copied into [VoiceAgentError.detail]
 * because it can echo the request.
 *
 * Verified from the families' own documented error vocabularies (OpenAI and
 * Anthropic), which the Go model table names through its AI SDK packages;
 * `docs/opencode-go-adapter.md` records this and the access date.
 */
object OpenCodeGoErrors {
    /** The typed error for a provider error [codeOrType], or `null` when absent. */
    fun forCode(codeOrType: String?): VoiceAgentError? {
        val normalized = codeOrType?.lowercase()?.trim().orEmpty()
        if (normalized.isEmpty()) return null
        return when (normalized) {
            "rate_limit_exceeded",
            "insufficient_quota",
            "rate_limit_error",
            "rate_limit",
            -> {
                VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the provider rate-limited the request")
            }

            "invalid_request_error",
            "invalid_request",
            "invalid_prompt",
            "not_found_error",
            "request_too_large",
            -> {
                VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the provider rejected the request")
            }

            "authentication_error",
            "invalid_api_key",
            "permission_error",
            -> {
                VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the provider rejected the credential")
            }

            "server_error",
            "api_error",
            "overloaded_error",
            "overloaded",
            -> {
                VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider was unavailable")
            }

            else -> {
                VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider reported an error ($normalized)")
            }
        }
    }

    /** The fallback for an error frame that carries no recognizable code. */
    fun unclassified(): VoiceAgentError = VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the provider reported an error")
}
