package com.voicechat.agent.fake

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmFailureReason
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.LlmStreamEventEnvelope
import com.voicechat.agent.contracts.LlmUsage
import com.voicechat.agent.contracts.isLegalStreamTransition
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.onCompletion

/**
 * One scripted item in a [DeterministicLanguageModel] run.
 *
 * The items describe *time and boundary* behavior, not content, so a test reads
 * like the scenario it is exercising: emit a delta after [afterMillis], stall,
 * then throw a typed failure.
 */
sealed interface ScriptedLlmStep {
    /**
     * Waits [afterMillis] of virtual time, then emits [event].
     *
     * The event must be legal where it lands ([isLegalStreamTransition]); an
     * illegal script fails the test instead of producing a stream no real
     * adapter could produce.
     */
    data class Emit(
        val event: LlmStreamEvent,
        val afterMillis: Long = 0L,
    ) : ScriptedLlmStep {
        init {
            require(afterMillis >= 0L) { "afterMillis must not be negative" }
        }
    }

    /**
     * Waits [millis] of virtual time and emits nothing.
     *
     * A stall exercises timeout handling and proves the fake holds the stream
     * open (rather than completing it) when a provider goes quiet.
     */
    data class Stall(
        val millis: Long,
    ) : ScriptedLlmStep {
        init {
            require(millis > 0L) { "a stall must last at least 1 ms" }
        }
    }

    /**
     * Ends collection by throwing [error] after waiting [afterMillis].
     *
     * An adapter that cannot carry a typed terminal event throws instead; this
     * step proves orchestration maps such a throw to the same failure state.
     */
    data class ThrowFailure(
        val error: VoiceAgentError,
        val afterMillis: Long = 0L,
    ) : ScriptedLlmStep {
        init {
            require(afterMillis >= 0L) { "afterMillis must not be negative" }
        }
    }
}

/**
 * Deterministic [LanguageModel] that emits scripted events on virtual time.
 *
 * **Determinism.** All timing uses `kotlinx.coroutines.delay`, which is
 * controlled by the test scheduler, so two runs of the same model and scheduler
 * produce byte-identical event sequences and no wall-clock time passes. Nothing
 * here reads a clock, a random source, a network socket, or a credential, and the
 * fake is in the test source set only, so it can never ship in the app.
 *
 * **Backpressure.** Emissions go through a callback and a bounded channel
 * ([bufferCapacity]) with [BufferOverflow.SUSPEND], so a slow consumer suspends
 * the producer rather than silently dropping or reordering events. A capacity of
 * `1` makes the backpressure observable in a test.
 *
 * **Cancellation and stalls.** [collectStream] suspends the collector until the
 * stream ends, so a test can cancel it mid-stall and assert that
 * [cancellationCount] advanced and no further event arrived. [stream] is also
 * cancellable through normal flow cancellation. In both cases no event is
 * emitted after cancellation.
 *
 * **Preflight.** A request that asks for a capability this adapter did not
 * declare is refused with a typed [LlmStreamEvent.Failed] before any stream
 * starts, which is the "do not assume provider parity" rule made observable.
 */
class DeterministicLanguageModel(
    override val providerId: ProviderId = ProviderId("deterministic-fake"),
    override val capabilities: LlmCapabilities = LlmCapabilities(),
    private val steps: List<ScriptedLlmStep> = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Completed())),
    /** The model reported on a completion; identical to the request unless set. */
    private val reportedModelId: ModelId? = null,
    private val reportedReasoning: ReasoningLevel? = null,
    private val bufferCapacity: Int = DEFAULT_BUFFER_CAPACITY,
) : LanguageModel {
    init {
        require(bufferCapacity >= 1) { "bufferCapacity must be at least 1" }
        // A malformed script is a test bug, not a provider behavior: fail loudly
        // at construction instead of emitting a stream no adapter could produce.
        var previous: LlmStreamEvent? = null
        steps.forEach { step ->
            if (step is ScriptedLlmStep.Emit) {
                require(isLegalStreamTransition(previous, step.event)) {
                    "illegal script: ${step.event} may not follow $previous"
                }
                previous = step.event
            }
        }
    }

    /** Every request [stream] was started with, in order. */
    val requests: MutableList<LlmRequest> = mutableListOf()

    /** Number of times a stream was started. */
    var streamCount: Int = 0
        private set

    /** Number of collections cancelled by their consumer. */
    var cancellationCount: Int = 0
        private set

    /** Number of events emitted (deltas plus terminal events). */
    var emittedEventCount: Int = 0
        private set

    /** Number of requests refused by the capability preflight. */
    var rejectedRequestCount: Int = 0
        private set

    /** Number of [close] calls. */
    var closeCount: Int = 0
        private set

    /** The most recent request, for asserting request construction. */
    val lastRequest: LlmRequest? get() = requests.lastOrNull()

    /**
     * Completes after this model emitted its first event.
     *
     * A cancellation-race test awaits this so it cancels *while* the stream is
     * already emitting, instead of racing the start.
     */
    val emittedFirstEvent: CompletableDeferred<Unit> = CompletableDeferred()

    private var lastTerminalEvent: LlmStreamEvent? = null

    /** The terminal event of the most recent stream, if it reached one. */
    val terminalEvent: LlmStreamEvent? get() = lastTerminalEvent

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        collectStream(request).onCompletion { cause ->
            if (cause is CancellationException) {
                cancellationCount++
                AppLog.d { "llm-fake: cancelled emitted=$emittedEventCount" }
            }
        }

    /**
     * Starts one stream as a hot flow.
     *
     * Emitting begins as soon as this is called, so a test with a suspended
     * scheduler can advance virtual time before the consumer even starts
     * collecting: the stream buffers up to [bufferCapacity] events and blocks
     * after that instead of dropping them. A stream whose script does not reach a
     * terminal event stays open until it is cancelled.
     */
    fun collectStream(request: LlmRequest): Flow<LlmStreamEvent> {
        requests += request
        AppLog.d {
            "llm-fake: stream start provider=${providerId.value} model=${request.model.modelId.value} " +
                "messages=${request.messages.size} chars=${request.characterCount} " +
                "reasoning=${request.reasoning?.name ?: "none"}"
        }

        val preflight = request.preflightFailure()
        if (preflight != null) {
            rejectedRequestCount++
            lastTerminalEvent = preflight
            AppLog.w { "llm-fake: request refused code=${preflight.error.code}" }
            // A refused request never becomes a stream, so streamCount stays put;
            // the typed refusal is the whole outcome.
            return callbackFlow {
                trySend(preflight)
                emittedEventCount++
                emittedFirstEvent.complete(Unit)
                close()
                awaitClose {}
            }.buffer(capacity = bufferCapacity, onBufferOverflow = BufferOverflow.SUSPEND)
        }

        streamCount++
        return callbackFlow {
            try {
                var previous: LlmStreamEvent? = null
                for (step in steps) {
                    when (step) {
                        is ScriptedLlmStep.Stall -> {
                            delay(step.millis)
                        }

                        is ScriptedLlmStep.ThrowFailure -> {
                            delay(step.afterMillis)
                            AppLog.d { "llm-fake: throwing ${step.error.code}" }
                            throw VoiceAgentException(step.error)
                        }

                        is ScriptedLlmStep.Emit -> {
                            delay(step.afterMillis)
                            val event = step.event.adopt(reportedModelId, reportedReasoning)
                            check(isLegalStreamTransition(previous, event)) {
                                "illegal scripted transition: $event after $previous"
                            }
                            previous = event
                            if (event !is LlmStreamEvent.Delta) lastTerminalEvent = event
                            emittedEventCount++
                            emittedFirstEvent.complete(Unit)
                            AppLog.d {
                                "llm-fake: emit ${event::class.simpleName} index=$emittedEventCount " +
                                    "chars=${LlmStreamEventEnvelope(event).partialText.length}"
                            }
                            send(event)
                        }
                    }
                }
            } finally {
                close()
            }
            awaitClose {}
        }.buffer(capacity = bufferCapacity, onBufferOverflow = BufferOverflow.SUSPEND)
    }

    /** The capability violation this request makes, or `null` when it is fine. */
    private fun LlmRequest.preflightFailure(): LlmStreamEvent.Failed? {
        val level = reasoning
        if (level != null && level != ReasoningLevel.NONE && level !in capabilities.reasoningLevels) {
            return LlmStreamEvent.Failed(
                error =
                    VoiceAgentError(
                        code = ErrorCode.LLM_INVALID_REQUEST,
                        detail = "reasoning level is not supported by this fake",
                    ),
                partialText = "",
            )
        }
        return null
    }

    /** Fills in the provider-reported model/reasoning on a completion when configured. */
    private fun LlmStreamEvent.adopt(
        model: ModelId?,
        reasoning: ReasoningLevel?,
    ): LlmStreamEvent =
        if (this is LlmStreamEvent.Completed && (model != null || reasoning != null)) {
            copy(model = model ?: this.model, reasoning = reasoning ?: this.reasoning)
        } else {
            this
        }

    override suspend fun close() {
        closeCount++
        AppLog.d { "llm-fake: closed streams=$streamCount emitted=$emittedEventCount" }
    }

    companion object {
        /** Default buffer; one event ahead keeps backpressure visible without stalling a fast script. */
        const val DEFAULT_BUFFER_CAPACITY: Int = 1

        /** A normal three-delta reply that completes with usage. */
        fun reply(
            text: String,
            usage: LlmUsage? = null,
        ): List<ScriptedLlmStep> =
            text.chunked(4).map { ScriptedLlmStep.Emit(LlmStreamEvent.Delta(it)) } +
                ScriptedLlmStep.Emit(LlmStreamEvent.Completed(usage = usage))

        /** A stream that emits [partial] then fails with [reason]'s default error. */
        fun failingAfter(
            partial: String,
            code: ErrorCode,
            reason: LlmFailureReason = LlmFailureReason.fromErrorCode(code),
        ): List<ScriptedLlmStep> =
            listOf(
                ScriptedLlmStep.Emit(LlmStreamEvent.Delta(partial)),
                ScriptedLlmStep.Emit(
                    LlmStreamEvent.Failed(
                        error = VoiceAgentError(code),
                        partialText = partial,
                        reason = reason,
                    ),
                ),
            )

        /**
         * Emits [deltas] then never reaches a terminal event, so the stream stays
         * open until the collector cancels it.
         */
        fun stallingAfter(deltas: List<String>): List<ScriptedLlmStep> =
            deltas.map { ScriptedLlmStep.Emit(LlmStreamEvent.Delta(it)) } +
                ScriptedLlmStep.Stall(millis = LONG_STALL_MILLIS)

        /** A stall long enough that only an explicit cancellation can end it. */
        const val LONG_STALL_MILLIS: Long = 60_000L
    }
}

/**
 * Suspends until the model emitted at least one event, without real time passing.
 *
 * A cancellation-race test awaits this so it cancels the stream *while* it is
 * already emitting, instead of racing the stream's start-up.
 */
suspend fun DeterministicLanguageModel.awaitFirstEmission(): Unit = emittedFirstEvent.await()
