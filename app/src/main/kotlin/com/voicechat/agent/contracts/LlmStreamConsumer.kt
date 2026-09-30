package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow

/**
 * Accumulated, provider-neutral result of consuming one streamed response.
 *
 * Everything a consumer needs to make a truthful decision is here, so no caller
 * has to re-implement streaming bookkeeping:
 *
 * - [text] is the assistant text that actually arrived;
 * - [completed] is true **only** for a stream that ended with
 *   [LlmStreamEvent.Completed], so a stream that stopped early can never be
 *   mistaken for a whole response;
 * - [model] and [reportedReasoning] are what the provider said it served, which
 *   is how a silent model switch becomes visible ([R-0017]).
 */
data class LlmStreamResult(
    val text: String,
    val terminal: LlmStreamEvent?,
    val deltaCount: Int,
    val usage: LlmUsage? = null,
    val model: ModelId? = null,
    val reportedReasoning: ReasoningLevel? = null,
) {
    /** True only when the provider completed the response normally. */
    val completed: Boolean get() = terminal is LlmStreamEvent.Completed

    /** The typed error of a failed stream, or `null`. */
    val error: VoiceAgentError?
        get() = (terminal as? LlmStreamEvent.Failed)?.error

    /** The classified stop reason when the stream did not complete normally. */
    val failureReason: LlmFailureReason?
        get() =
            when (val t = terminal) {
                is LlmStreamEvent.Failed -> t.reason
                is LlmStreamEvent.Cancelled -> t.reason
                else -> null
            }

    /** True when text arrived but the response was not completed. */
    val isPartial: Boolean get() = text.isNotEmpty() && !completed
}

/**
 * Consumes one [LanguageModel] stream into a single accumulated
 * [LlmStreamResult].
 *
 * This is the reference consumer for the M12 contract and the shape
 * orchestration (M21) reuses: it folds the event stream instead of branching on
 * subtypes, and it is where the contract's streaming rules are actually
 * *enforced* on the consumer side rather than only documented.
 *
 * **Guarantees.**
 * - Deltas accumulate in arrival order; a terminal event ends the fold.
 * - An event that arrives after a terminal event is ignored and logged, so a
 *   misbehaving adapter cannot corrupt the persisted turn.
 * - A provider that throws [VoiceAgentException] is mapped to the same
 *   accumulated result as an emitted [LlmStreamEvent.Failed].
 * - Cancellation propagates as `CancellationException` after the partial text is
 *   recorded, so the caller persists an interrupted — never completed — turn.
 * - Usage is recorded only when the provider reported it; a `null` count is
 *   never written as `0`.
 * - Only provider/model identity, counts, and stable codes are logged. Prompt
 *   text, delta text, and credentials never are.
 */
suspend fun LanguageModel.consume(
    request: LlmRequest,
    trace: TurnStreamTrace = TurnStreamTrace.NONE,
): LlmStreamResult {
    val text = StringBuilder()
    var terminal: LlmStreamEvent? = null
    var deltaCount = 0
    var usage: LlmUsage? = null
    var reportedModel: ModelId? = null
    var reportedReasoning: ReasoningLevel? = null

    AppLog.d {
        "llm: request provider=${request.model.providerId.value} model=${request.model.modelId.value} " +
            "messages=${request.messages.size} chars=${request.characterCount} " +
            "reasoning=${request.reasoning?.name ?: "none"} streaming=${capabilities.streaming}"
    }

    try {
        stream(request).collect { event ->
            if (terminal != null) {
                // A well-behaved adapter stops at the terminal event; if one does
                // not, drop the late event instead of appending to a finished turn.
                AppLog.w { "llm: ignored ${event::class.simpleName} after terminal=${terminal!!::class.simpleName}" }
                return@collect
            }
            when (event) {
                is LlmStreamEvent.Delta -> {
                    deltaCount++
                    text.append(event.text)
                    trace.onDelta(deltaCount, event.text.length, event.text)
                }

                is LlmStreamEvent.Completed -> {
                    terminal = event
                    usage = event.usage
                    reportedModel = event.model
                    reportedReasoning = event.reasoning
                    trace.onCompleted(event)
                }

                is LlmStreamEvent.Cancelled -> {
                    terminal = event
                    // The event's own partial text is authoritative; fall back to
                    // what was accumulated only when the adapter sent none.
                    if (event.partialText.isNotEmpty()) {
                        text.clear()
                        text.append(event.partialText)
                    }
                    trace.onEnded(event.reason.name, text.length)
                }

                is LlmStreamEvent.Failed -> {
                    terminal = event
                    if (event.partialText.isNotEmpty()) {
                        text.clear()
                        text.append(event.partialText)
                    }
                    trace.onEnded(event.reason.name, text.length)
                }
            }
        }
    } catch (cancellation: CancellationException) {
        trace.onEnded(LlmFailureReason.CANCELLED.name, text.length)
        throw cancellation
    } catch (failure: VoiceAgentException) {
        terminal = LlmStreamEvent.Failed(error = failure.error, partialText = text.toString())
        trace.onEnded(LlmFailureReason.fromErrorCode(failure.error.code).name, text.length)
    }

    val result =
        LlmStreamResult(
            text = text.toString(),
            terminal = terminal,
            deltaCount = deltaCount,
            usage = usage,
            model = reportedModel,
            reportedReasoning = reportedReasoning,
        )
    AppLog.d {
        "llm: stream end completed=${result.completed} deltas=$deltaCount chars=${result.text.length} " +
            "terminal=${terminal?.let { it::class.simpleName } ?: "none"} " +
            "reason=${result.failureReason?.name ?: "none"} usage=${usage?.let { "reported" } ?: "none"} " +
            "model=${reportedModel?.value ?: "unreported"}"
    }
    return result
}

/**
 * Optional, privacy-safe hooks a consumer can use to record stream progress.
 *
 * The default [NONE] records nothing, so a caller that only needs the result
 * does not have to care about tracing. Orchestration (M21) passes an
 * implementation backed by the M04 `TurnTraceRecorder`; only counts, stable
 * reason names, and reported token counts are passed in, never delta text.
 */
interface TurnStreamTrace {
    /**
     * One delta arrived. [text] is the delta's content, passed so a live UI can
     * render it; implementations on a tracing path must use only [index] and
     * [characterCount] and must not log or persist [text].
     */
    fun onDelta(
        index: Int,
        characterCount: Int,
        text: String,
    ) = Unit

    /** The response completed. */
    fun onCompleted(event: LlmStreamEvent.Completed) = Unit

    /** The stream ended without completing; [reason] is a [LlmFailureReason] name. */
    fun onEnded(
        reason: String,
        characterCount: Int,
    ) = Unit

    /** Records nothing. */
    object NONE : TurnStreamTrace
}
