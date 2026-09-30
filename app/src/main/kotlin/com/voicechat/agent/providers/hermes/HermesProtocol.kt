package com.voicechat.agent.providers.hermes

import com.voicechat.agent.contracts.LlmUsage
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.VoiceAgentError

/**
 * One interpreted frame from the Hermes Agent API Server stream (M19).
 *
 * Hermes exposes an OpenAI-compatible Chat Completions surface
 * (`POST /v1/chat/completions`) that streams standard `chat.completion.chunk`
 * SSE frames plus a custom named `hermes.tool.progress` event and
 * `: keepalive` comments, and terminates with `data: [DONE]`
 * (`docs/hermes-adapter.md`, accessed 2026-09-29). Because Hermes is an **agent
 * runtime that executes tools on the server host**, the tool-progress event is
 * deliberately *not* assistant text: it becomes [Ignored].
 *
 * The terminal signal is either the documented `[DONE]` sentinel ([Done]) or a
 * chunk carrying a non-null `choices[].finish_reason`, which the adapter records
 * on [Reported] and resolves after the stream ends. The reasoning channel
 * (`choices[].delta.reasoning_content`) is a distinct [Reasoning] case, never
 * concatenated into assistant text (risk R-0066).
 */
sealed interface HermesStreamFrame {
    /** Incremental assistant text to surface as a `Delta`. */
    data class Delta(
        val text: String,
    ) : HermesStreamFrame

    /** A reasoning/thinking delta; excluded from assistant text. */
    data class Reasoning(
        val text: String,
    ) : HermesStreamFrame

    /**
     * A non-terminal metadata observation: the serving model, a usage report, or
     * a `finish_reason`. Any of the three may be absent; a frame that reports
     * none of them is [Ignored].
     */
    data class Reported(
        val model: ModelId?,
        val usage: LlmUsage?,
        val finishReason: String?,
    ) : HermesStreamFrame

    /** The documented `data: [DONE]` sentinel that ends the stream. */
    data object Done : HermesStreamFrame

    /** The server (or a routed provider) reported a terminal failure. */
    data class Failed(
        val error: VoiceAgentError,
    ) : HermesStreamFrame

    /** A frame with no contract-relevant content (role-only, tool progress, keep-alive). */
    data object Ignored : HermesStreamFrame
}

/**
 * The resolved termination of a Hermes stream, evaluated once the stream stops
 * (`[DONE]`, a `finish_reason`, or an early end).
 */
sealed interface HermesCompletion {
    /** The response is whole. */
    data object Completed : HermesCompletion

    /** The response stopped without finishing. [error] is typed and safe to show. */
    data class Failed(
        val error: VoiceAgentError,
    ) : HermesCompletion
}

/**
 * Maps a Hermes/OpenAI-compatible provider error identifier to a typed error.
 *
 * Hermes routes to configured providers, so a mid-stream body can carry an
 * OpenAI-style `error.type`/`error.code`. Only the stable identifier is read;
 * the provider `message` is never copied into [VoiceAgentError.detail] because it
 * can echo the request.
 */
object HermesErrors {
    /** The typed error for a Hermes error body [type], or `null` when unrecognized. */
    fun forType(type: String?): VoiceAgentError? =
        when (type?.lowercase()?.trim()) {
            null, "" -> {
                null
            }

            "rate_limit_exceeded",
            "insufficient_quota",
            "rate_limit_error",
            "rate_limit",
            -> {
                VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the server rate-limited the request")
            }

            "authentication_error",
            "invalid_api_key",
            "permission_error",
            "permission_denied",
            -> {
                VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the server rejected the credential")
            }

            "server_error",
            "api_error",
            "overloaded_error",
            "overloaded",
            "provider_unavailable",
            -> {
                VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the server was unavailable")
            }

            "timeout",
            "request_timeout",
            -> {
                VoiceAgentError(ErrorCode.LLM_TIMEOUT, "the server timed out")
            }

            else -> {
                VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the server reported an error ($type)")
            }
        }

    /**
     * The typed error for an HTTP status a streamed error can collapse to when the
     * body carries no typed identifier. Mirrors
     * [com.voicechat.agent.remote.RemoteStatusMapper] so a pre-stream failure and a
     * mid-stream error classify the same way.
     */
    fun forStatus(status: Int?): VoiceAgentError =
        when (status) {
            401, 403 -> VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the server rejected the credential (HTTP $status)")
            408, 504 -> VoiceAgentError(ErrorCode.LLM_TIMEOUT, "the server timed out (HTTP $status)")
            429 -> VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the server rate-limited the request (HTTP 429)")
            in 500..599 -> VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the server was unavailable (HTTP $status)")
            in 400..499 -> VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the server rejected the request (HTTP $status)")
            else -> VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the server reported an error")
        }

    /** The fallback for an error body that carries no recognizable identifier. */
    fun unclassified(): VoiceAgentError = VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the server reported an error")
}
