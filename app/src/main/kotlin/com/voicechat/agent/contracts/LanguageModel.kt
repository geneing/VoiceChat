package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCategory
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow

// Provider-neutral language-model request, stream, and error contract.
//
// This file is the single seam every external provider adapter (M14-M19) and
// every on-device runtime adapter (M20) implements, and the only surface
// orchestration (M21) and the dialog (M06) consume. Two rules shape it:
//
// 1. **No vendor type.** Only Kotlin/coroutines and the M02 domain types appear
//    here (enforced by `DomainPurityTest` and `LlmContractPurityTest`). A
//    provider SDK, HTTP client, or JSON type stays inside its adapter.
// 2. **No assumed parity.** Providers differ in streaming shape, usage
//    reporting, and reasoning controls, so capability is declared per adapter
//    and never inferred. The request carries only what the app decided plus an
//    optional reasoning level; the adapter rejects or ignores what its provider
//    does not support, and reports it through `LlmRequestValidator`.
//
// The model is described in `docs/llm-contract.md`.

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
 * vendor SDK types stay inside adapters. A message is deliberately **not** an
 * M05 `ContextMessage`: the bounded context is built by
 * `ModelContextBuilder` and mapped here, so no history-selection rule is
 * duplicated in this seam.
 */
data class LlmMessage(
    val role: LlmRole,
    val content: String,
)

/**
 * The bounded, provider-neutral request for one streamed completion.
 *
 * [model] carries provider and model identity together so the trace can always
 * name the true origin of a request and a silent provider/model switch is
 * visible (see `docs/llm-providers.md`).
 *
 * [messages] is the already-bounded context the app chose to send, not the full
 * stored conversation: `ModelContextBuilder` (M05) selects it and no bounding
 * happens here. The list may be empty for a provider that needs none.
 *
 * [reasoning] is a *request*, not a promise: a provider that does not support
 * reasoning must not be handed a level. Use
 * [LlmRequestValidator.validate] with the adapter's declared
 * [LlmCapabilities] before starting a request.
 */
data class LlmRequest(
    val model: ProviderModelSelection,
    val messages: List<LlmMessage>,
    val reasoning: ReasoningLevel? = null,
) {
    /** Total message characters, for privacy-safe size diagnostics. */
    val characterCount: Int get() = messages.sumOf { it.content.length }
}

/**
 * What one adapter actually supports.
 *
 * Capability is declared per adapter instead of assumed globally, because the
 * planned providers differ (`docs/decisions.md` §4): DeepSeek exposes per-model
 * effort levels, OpenAI has `none`-`xhigh` (some models add `max`), and OpenCode
 * Go/Zen and Hermes reasoning support is not documented at all.
 *
 * [streaming] exists because a non-streaming provider is still a legal adapter:
 * it emits one [LlmStreamEvent.Delta] followed by
 * [LlmStreamEvent.Completed], never a fabricated multi-delta stream.
 */
data class LlmCapabilities(
    val streaming: Boolean = true,
    val usageReporting: Boolean = false,
    val reasoningLevels: Set<ReasoningLevel> = emptySet(),
) {
    companion object {
        /**
         * Conservative default for a provider whose docs are not verified yet:
         * it streams but claims nothing else.
         */
        val UNVERIFIED: LlmCapabilities = LlmCapabilities()
    }
}

/**
 * Outcome of checking a request against [LlmCapabilities].
 *
 * Unsupported is a first-class, typed result rather than a silent downgrade, so
 * the UI can hide or refuse an option instead of sending a parameter the
 * provider will reject or ignore.
 */
sealed interface LlmRequestValidation {
    /** The request is supported as written. */
    data object Supported : LlmRequestValidation

    /**
     * The request asks for a feature the adapter does not support. The caller
     * must fix the request (or the selection); the request is never sent.
     */
    data class Unsupported(
        val error: VoiceAgentError,
    ) : LlmRequestValidation {
        init {
            require(error.category == ErrorCategory.LANGUAGE_MODEL) {
                "an unsupported request must carry a language-model error, was ${error.code}"
            }
        }
    }
}

/**
 * Checks a request against an adapter's declared capabilities.
 *
 * This is the mechanical enforcement of "do not assume all providers have
 * identical capabilities": capability is declared by the adapter and the
 * request is checked against that declaration. It does not replace a per-model
 * catalog (M13/M22); it only prevents a request from asking for a capability the
 * adapter has already said it does not have.
 */
object LlmRequestValidator {
    /** Validates [request] against [capabilities]. */
    fun validate(
        request: LlmRequest,
        capabilities: LlmCapabilities,
    ): LlmRequestValidation {
        val level = request.reasoning ?: return LlmRequestValidation.Supported
        if (level == ReasoningLevel.NONE || level in capabilities.reasoningLevels) {
            return LlmRequestValidation.Supported
        }
        val error =
            VoiceAgentError(
                code = ErrorCode.LLM_INVALID_REQUEST,
                detail = "reasoning level is not supported by this provider",
            )
        return LlmRequestValidation.Unsupported(error)
    }
}

/**
 * Token usage or other metering the provider reported for one stream.
 *
 * Every field is optional and nullable because usage reporting is not uniform:
 * a provider may report only totals, only counts, or nothing at all. `null` means
 * *not reported* and must never be rendered as `0`, which would be a false
 * claim about what the provider returned. [details] may hold an allow-listed
 * vendor figure (for example a cost or cached-token count) and is never a place
 * for prompt, response, or credential content.
 */
data class LlmUsage(
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val details: Map<String, String> = emptyMap(),
) {
    init {
        require(promptTokens == null || promptTokens >= 0) { "promptTokens must not be negative" }
        require(completionTokens == null || completionTokens >= 0) { "completionTokens must not be negative" }
        require(totalTokens == null || totalTokens >= 0) { "totalTokens must not be negative" }
    }

    /** True when the provider reported nothing at all. */
    val isEmpty: Boolean
        get() = promptTokens == null && completionTokens == null && totalTokens == null && details.isEmpty()
}

/**
 * Why a generation ended without completing.
 *
 * The reason is separate from the typed [VoiceAgentError] so a caller can branch
 * on the *kind* of stop without string-matching a provider message. Each value
 * maps to one stable [ErrorCode]:
 *
 * | reason | code |
 * | --- | --- |
 * | [TIMEOUT] | [ErrorCode.LLM_TIMEOUT] |
 * | [RATE_LIMITED] | [ErrorCode.LLM_RATE_LIMITED] |
 * | [AUTHENTICATION] | [ErrorCode.LLM_AUTHENTICATION_FAILED] |
 * | [NETWORK] | [ErrorCode.LLM_NETWORK_FAILED] |
 * | [MALFORMED_RESPONSE] | [ErrorCode.LLM_MALFORMED_RESPONSE] |
 * | [UNAVAILABLE] | [ErrorCode.LLM_UNAVAILABLE] |
 * | [NOT_CONFIGURED] | [ErrorCode.LLM_NOT_CONFIGURED] |
 * | [INVALID_REQUEST] | [ErrorCode.LLM_INVALID_REQUEST] |
 * | [CANCELLED] | [ErrorCode.LLM_CANCELLED] |
 * | [OTHER] | [ErrorCode.LLM_REQUEST_FAILED] |
 */
enum class LlmFailureReason(
    val defaultErrorCode: ErrorCode,
) {
    TIMEOUT(ErrorCode.LLM_TIMEOUT),
    RATE_LIMITED(ErrorCode.LLM_RATE_LIMITED),
    AUTHENTICATION(ErrorCode.LLM_AUTHENTICATION_FAILED),
    NETWORK(ErrorCode.LLM_NETWORK_FAILED),
    MALFORMED_RESPONSE(ErrorCode.LLM_MALFORMED_RESPONSE),
    UNAVAILABLE(ErrorCode.LLM_UNAVAILABLE),
    NOT_CONFIGURED(ErrorCode.LLM_NOT_CONFIGURED),
    INVALID_REQUEST(ErrorCode.LLM_INVALID_REQUEST),
    CANCELLED(ErrorCode.LLM_CANCELLED),

    /** A failure the adapter could not classify; never a silent success. */
    OTHER(ErrorCode.LLM_REQUEST_FAILED),
    ;

    companion object {
        /**
         * The reason for [code], or [OTHER] when the code is not one of the
         * LLM failure codes. The fallback is [OTHER], never a fabricated
         * specific reason.
         */
        fun fromErrorCode(code: ErrorCode): LlmFailureReason = entries.firstOrNull { it.defaultErrorCode == code } ?: OTHER
    }
}

/**
 * One event in a streamed LLM response.
 *
 * **Ordering.** A stream is zero or more [Delta]s followed by exactly one
 * terminal event: [Completed], [Cancelled], or [Failed]. Nothing may follow a
 * terminal event. `LlmStreamEventOrder` is the mechanical form of this rule; the
 * deterministic fake applies it to every emission.
 *
 * **Partial response state.** A stream can end before the assistant text is
 * complete. Orchestration cannot recover the interrupted text by itself, so the
 * terminal event carries it: [Cancelled.partialText] and [Failed.partialText] are
 * the deltas received before the stop, concatenated in arrival order. A
 * cancelled or failed request is therefore never persisted as a completed one,
 * and [Completed] alone means the response is whole.
 */
sealed interface LlmStreamEvent {
    /** Incremental assistant text; show it immediately. */
    data class Delta(
        val text: String,
    ) : LlmStreamEvent

    /**
     * The response completed normally.
     *
     * [usage] is `null` when the provider reported no usage, which is distinct
     * from an empty [LlmUsage]. [model] and [reasoning] echo what actually
     * served the request when the provider reports it, so a request that reached
     * a different model than the selection is visible in the trace rather than
     * silently accepted.
     */
    data class Completed(
        val usage: LlmUsage? = null,
        val model: ModelId? = null,
        val reasoning: ReasoningLevel? = null,
    ) : LlmStreamEvent

    /**
     * Generation stopped because the consumer cancelled it. [partialText] is
     * what had arrived.
     *
     * A well-behaved adapter does not emit this: cancellation is delivered by
     * the flow throwing `CancellationException`, which orchestration already
     * handles. It exists for an adapter that must report a *remote* cancellation
     * it learned about from the provider (for example a server-sent abort) while
     * the local collection is still active, and for scripts in tests.
     */
    data class Cancelled(
        val partialText: String,
        val reason: LlmFailureReason = LlmFailureReason.CANCELLED,
    ) : LlmStreamEvent

    /**
     * The request failed; [partialText] is what had arrived before the failure.
     *
     * [reason] classifies the stop and [error] carries the stable code and
     * retryability. They must agree: [error]'s code is [reason]'s code unless
     * the adapter has a more precise one, and the class is always
     * `LANGUAGE_MODEL`.
     */
    data class Failed(
        val error: VoiceAgentError,
        val partialText: String,
        val reason: LlmFailureReason = LlmFailureReason.fromErrorCode(error.code),
    ) : LlmStreamEvent
}

/** True when [this] ends a stream and nothing may follow it. */
val LlmStreamEvent.isTerminal: Boolean
    get() = this !is LlmStreamEvent.Delta

/**
 * Whether [next] may follow [previous] in one stream.
 *
 * A stream is `Delta*` then one terminal event, so:
 * - `null` previous: any event may start a stream;
 * - `Delta` previous: a [LlmStreamEvent.Delta] or any terminal event;
 * - terminal previous: nothing.
 */
fun isLegalStreamTransition(
    previous: LlmStreamEvent?,
    next: LlmStreamEvent,
): Boolean =
    when (previous) {
        null -> true
        is LlmStreamEvent.Delta -> true
        else -> false
    }

/**
 * Throws when [next] may not follow [previous].
 *
 * @throws IllegalArgumentException when the transition is illegal.
 */
fun LlmStreamEvent.requireLegalAfter(previous: LlmStreamEvent?): LlmStreamEvent {
    require(isLegalStreamTransition(previous, this)) { "Illegal LLM stream transition: $previous -> $this" }
    return this
}

/**
 * One event received from an adapter, checked against the stream rules before
 * anything else consumes it.
 *
 * [model] echoes the provider-reported model when the adapter included it, so
 * orchestration can compare it with the selection and surface a mismatch instead
 * of silently accepting a different model ([R-0017]). [completed] is true only
 * for [LlmStreamEvent.Completed], so orchestration can tell a whole response
 * from a partial one without inspecting the subtype.
 */
data class LlmStreamEventEnvelope(
    val event: LlmStreamEvent,
    val model: ModelId? = null,
) {
    /** True when this is a [Delta] carrying incremental text. */
    val isDelta: Boolean get() = event is LlmStreamEvent.Delta

    /** True when the response completed normally. */
    val completed: Boolean get() = event is LlmStreamEvent.Completed

    /** True when this event ended the stream. */
    val isTerminal: Boolean get() = event.isTerminal

    /**
     * Assistant text carried by this event: the delta text, or the partial text
     * of a cancelled/failed stop. Empty for a normal completion.
     */
    val partialText: String
        get() =
            when (val e = event) {
                is LlmStreamEvent.Delta -> e.text
                is LlmStreamEvent.Cancelled -> e.partialText
                is LlmStreamEvent.Failed -> e.partialText
                is LlmStreamEvent.Completed -> ""
            }

    /** The usage the provider reported, if any. */
    val usage: LlmUsage? get() = (event as? LlmStreamEvent.Completed)?.usage

    /** The typed error of a [LlmStreamEvent.Failed] event, if any. */
    val error: VoiceAgentError? get() = (event as? LlmStreamEvent.Failed)?.error
}

/**
 * Validates a whole stream's event sequence.
 *
 * The check is pull-based: omit [events] and the validator joins the stream and
 * returns the verdict, which also proves the order with a single pass.
 */
object LlmStreamValidator {
    /** Validates [events] and returns the first ordering violation, or `null`. */
    fun firstViolation(events: List<LlmStreamEvent>): LlmStreamViolation? {
        var previous: LlmStreamEvent? = null
        events.forEachIndexed { index, event ->
            if (!isLegalStreamTransition(previous, event)) {
                return LlmStreamViolation(index = index, previous = previous, event = event)
            }
            previous = event
        }
        return null
    }

    /** Blocks until [events] completes, then validates the order. */
    suspend fun validate(events: Flow<LlmStreamEvent>): LlmStreamViolation? {
        val collected = mutableListOf<LlmStreamEvent>()
        events.collect { collected += it }
        return firstViolation(collected)
    }

    /** Blocks until [events] completes and throws on the first ordering violation. */
    suspend fun requireValid(events: Flow<LlmStreamEvent>) {
        val violation = validate(events)
        if (violation != null) throw IllegalArgumentException(violation.describe())
    }
}

/** One illegal event, and the index where it arrived. */
data class LlmStreamViolation(
    val index: Int,
    val previous: LlmStreamEvent?,
    val event: LlmStreamEvent,
) {
    /** Human-readable description; never carries response content. */
    fun describe(): String = "event #$index (${event::class.simpleName}) may not follow $previous"
}

/**
 * Provider-neutral streaming language model.
 *
 * **Ownership and lifecycle.** [stream] returns a cold flow for one request;
 * each collection starts exactly one request. The flow completes after a single
 * terminal event ([LlmStreamEvent.Completed], [LlmStreamEvent.Cancelled], or
 * [LlmStreamEvent.Failed]); the terminal event is the only way the consumer
 * learns the response is whole, because a flow that simply ends has no way to say
 * whether the text was complete. Cancelling collection cancels the in-flight
 * request and must not emit further events. Requests run off the main thread, and
 * no credential or raw prompt is logged.
 *
 * **Failure modes.** A provider error should be a typed [LlmStreamEvent.Failed]
 * carrying [LlmFailureReason] and [VoiceAgentError]; an adapter may also throw
 * [com.voicechat.agent.domain.VoiceAgentException], which callers map to the same
 * terminal event. Cancellation is delivered as `CancellationException`, which the
 * consumer handles as an interruption. Nothing is reported as success unless
 * generation actually completed.
 *
 * **Capabilities.** [capabilities] declares what this adapter supports
 * (streaming, usage reporting, reasoning levels). It is adapter-declared because
 * the planned providers are not identical; a caller must check it rather than
 * assume parity.
 */
interface LanguageModel {
    /** The provider this adapter talks to. */
    val providerId: ProviderId

    /**
     * What this adapter supports. Defaults to the conservative
     * [LlmCapabilities.UNVERIFIED] so an adapter must opt in to each claim
     * rather than inherit a capability it never verified.
     */
    val capabilities: LlmCapabilities get() = LlmCapabilities.UNVERIFIED

    /** Streams one request's response. */
    fun stream(request: LlmRequest): Flow<LlmStreamEvent>

    /** Releases transport resources. Idempotent. */
    suspend fun close()
}
