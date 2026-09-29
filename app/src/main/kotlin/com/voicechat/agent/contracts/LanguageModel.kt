package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow

/** Role of one message in an LLM request. */
enum class LlmRole {
    SYSTEM,
    USER,
    ASSISTANT,
}

/**
 * One message in a bounded request context.
 *
 * This carries only text. Provider-specific payloads, tool definitions, and
 * vendor SDK types stay inside adapters.
 */
data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

/**
 * A provider-neutral streaming request.
 *
 * This is the M02 seam; M12 refines the request (bounded context construction)
 * and stream metadata. [messages] is the already-bounded context the app chose
 * to send, not the full stored conversation.
 */
data class LlmRequest(
    val model: ProviderModelSelection,
    val messages: List<LlmMessage>,
    val reasoning: ReasoningLevel? = null,
)

/** Token usage when a provider reports it. */
data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
)

/**
 * One event in a streamed LLM response.
 *
 * Cancellation and partial completion are explicit: [Cancelled] and [Failed]
 * both carry the text that had already arrived, so orchestration can reconcile
 * what was generated with what was delivered.
 */
sealed interface LlmStreamEvent {
    /** Incremental assistant text; show it immediately. */
    data class Delta(
        val text: String,
    ) : LlmStreamEvent

    /** The response completed normally. */
    data class Completed(
        val usage: LlmUsage? = null,
    ) : LlmStreamEvent

    /** The request was cancelled; [partialText] is what had arrived. */
    data class Cancelled(
        val partialText: String,
    ) : LlmStreamEvent

    /** The request failed; [partialText] is what had arrived before the failure. */
    data class Failed(
        val error: VoiceAgentError,
        val partialText: String,
    ) : LlmStreamEvent
}

/**
 * Provider-neutral streaming language model.
 *
 * **Ownership and lifecycle.** [stream] returns a cold flow for one request;
 * each collection starts exactly one request. The flow completes after a single
 * terminal event ([LlmStreamEvent.Completed], [LlmStreamEvent.Cancelled], or
 * [LlmStreamEvent.Failed]); a provider adapter may also end with a typed
 * exception, which callers should map to [LlmStreamEvent.Failed]. Cancelling
 * collection cancels the in-flight request and must not emit further deltas.
 * Requests run off the main thread, and no credential or raw prompt is logged.
 *
 * The adapter owns its provider identity and selected model in the request, so
 * orchestration never branches on a vendor.
 */
interface LanguageModel {
    /** The provider this adapter talks to. */
    val providerId: ProviderId

    /** Streams one request's response. */
    fun stream(request: LlmRequest): Flow<LlmStreamEvent>

    /** Releases transport resources. Idempotent. */
    suspend fun close()
}
