package com.voicechat.agent.orchestration

import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.LlmStreamResult
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.contracts.TurnStreamTrace
import com.voicechat.agent.contracts.consume
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.diagnostics.TraceSpan
import com.voicechat.agent.diagnostics.TurnTraceFactory
import com.voicechat.agent.diagnostics.TurnTraceRecorder
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.domain.context.ContextMessage
import com.voicechat.agent.domain.context.ContextRole
import com.voicechat.agent.domain.context.ModelContextBuilder
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * Runs one conversation turn: persist the finalized user turn, stream one
 * provider request through the M12 contract, feed complete text chunks to
 * on-device TTS, and persist only the assistant state that is truthful.
 *
 * It is the M21 implementation of the "turn orchestration" boundary in
 * `docs/architecture.md` and the replacement for the M06 state holder's inline
 * request handling (R-0032). All ordering, late-event rejection, delivery
 * accounting, and terminal classification run through the pure
 * [TurnStateMachine]; this class only wires contracts to it and performs the
 * side effects (persistence, TTS, tracing).
 *
 * **Turn identity.** One `run` call owns exactly one turn ID. Provider and TTS
 * callbacks are tagged with it, and [TurnStateMachine] drops any event that does
 * not belong to the current turn, so a late delta or a completion from a
 * cancelled/superseded request can never mutate a newer turn.
 *
 * **No silent fallback.** A provider failure or a TTS failure is surfaced as a
 * typed outcome; the orchestrator never retries against a different provider or
 * model and never persists a partial reply as a completed one. When speech is
 * enabled, delivered text is clamped to what TTS confirmed audible.
 *
 * **Tracing.** The per-turn M04 [TurnTraceRecorder] is driven here with the
 * finalized [LlmRequestState] vocabulary; only identities, counts, and stable
 * codes reach it, never prompt, transcript, or delta content (R-0028, R-0068).
 *
 * @param textToSpeech the on-device TTS path, or `null` for a text-only turn.
 * @param assistantTurnId supplies the ID for the persisted assistant turn.
 * @param deliveryTimeoutMillis bounds the wait for outstanding TTS utterances so
 *   a wedged engine cannot hang a turn forever.
 */
class TurnOrchestrator(
    private val repository: ConversationRepository,
    private val languageModel: LanguageModel,
    private val textToSpeech: TextToSpeech? = null,
    diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val assistantTurnId: () -> TurnId = { TurnId("assistant-${UUID.randomUUID()}") },
    private val contextBuilder: ModelContextBuilder = ModelContextBuilder(),
    private val deliveryTimeoutMillis: Long = DEFAULT_DELIVERY_TIMEOUT_MILLIS,
) {
    private val traceFactory = TurnTraceFactory(clock, diagnostics)

    @Volatile
    private var interruptRequested: Boolean = false

    @Volatile
    private var interruptOnsetAtNanos: Long? = null

    @Volatile
    private var activeStreamJob: Job? = null

    @Volatile
    private var activeTtsJobs: MutableList<Job>? = null

    /**
     * Requests that the in-flight turn be interrupted by detected user speech.
     *
     * Unlike a caller cancellation (delivered as `CancellationException` by
     * cancelling the running coroutine), this stops the provider request and the
     * TTS jobs from another coroutine and lets the run finish as
     * [TurnOutcome.Interrupted], preserving the delivered prefix.
     */
    fun interrupt(onsetAtNanos: Long? = null) {
        interruptRequested = true
        interruptOnsetAtNanos = onsetAtNanos
        activeStreamJob?.cancel()
        activeTtsJobs?.forEach { it.cancel() }
    }

    /** Resets the interrupt flag so the instance can run a subsequent turn. */
    fun resetInterrupt() {
        interruptRequested = false
        interruptOnsetAtNanos = null
    }

    /**
     * Runs [request] to a terminal state and returns what was persisted.
     *
     * @throws CancellationException when the caller cancels the turn; the
     *   interrupted turn is still persisted truthfully, in a `NonCancellable`
     *   block, before the exception propagates.
     */
    suspend fun run(
        request: TurnRequest,
        observer: TurnObserver = TurnObserver.NONE,
        languageModel: LanguageModel = this.languageModel,
    ): TurnResult {
        val turnId = request.userTurn.id
        val machine = TurnStateMachine(speechEnabled = textToSpeech != null)
        machine.beginGeneration(turnId, request.userTurn.source, request.userTurn.transcript)
        val trace = traceFactory.start(turnId, request.selection, reasoningLevel = request.reasoning?.name)

        val withUser = persistUserTurn(request, trace, observer)
        if (withUser == null) {
            val failure =
                TurnResult(
                    conversation = request.conversation,
                    record = machine.record(),
                    userTurnPersisted = false,
                    persistenceFailure = VoiceAgentError(ErrorCode.PERSISTENCE_FAILED),
                )
            observer.onTurnFinished(failure)
            return failure
        }

        AppLog.d {
            "orchestration: turn start source=${request.userTurn.source} provider=${request.selection.providerId.value} " +
                "model=${request.selection.modelId.value} speech=${textToSpeech != null}"
        }

        val conversation =
            try {
                execute(request, withUser, machine, trace, observer, languageModel)
            } catch (cancellation: CancellationException) {
                withContext(NonCancellable) {
                    stopSpeech()
                    if (interruptRequested) {
                        machine.reduce(TurnEvent.InterruptRequested(turnId, interruptOnsetAtNanos))
                    } else {
                        machine.reduce(TurnEvent.CancelRequested(turnId))
                    }
                    observer.onTurnFinished(persistAssistant(machine, withUser, trace))
                }
                throw cancellation
            } catch (failure: Throwable) {
                // An unexpected adapter/runtime throw is still a typed failure, never
                // a silent success and never a reason to lose the persisted turn.
                withContext(NonCancellable) {
                    stopSpeech()
                    machine.reduce(
                        TurnEvent.ProviderFailed(turnId, failure.toVoiceAgentError(), machine.generatedText),
                    )
                }
                return persistAssistant(machine, withUser, trace).also(observer::onTurnFinished)
            }
        return persistAssistant(machine, conversation, trace).also(observer::onTurnFinished)
    }

    // region pipeline

    private suspend fun execute(
        request: TurnRequest,
        conversation: Conversation,
        machine: TurnStateMachine,
        trace: TurnTraceRecorder,
        observer: TurnObserver,
        languageModel: LanguageModel,
    ): Conversation {
        val chunker = TtsTextChunker()
        val ttsJobs = mutableListOf<Job>()
        var utteranceSequence = 0
        var streamResult: LlmStreamResult? = null

        val requestSpan = trace.start(DiagnosticStage.LLM_REQUEST)
        trace.requestSelected(
            request.selection.providerId.value,
            request.selection.modelId.value,
            request.reasoning?.name,
        )
        trace.markStreamStarted()

        val llmRequest = buildRequest(request, conversation)
        AppLog.d {
            "orchestration: request messages=${llmRequest.messages.size} chars=${llmRequest.characterCount} " +
                "reasoning=${request.reasoning?.name ?: "none"}"
        }

        try {
            coroutineScope {
                activeTtsJobs = ttsJobs
                val streamTrace =
                    OrchestrationStreamTrace(
                        machine = machine,
                        turnId = request.userTurn.id,
                        trace = trace,
                        clock = clock,
                        observer = observer,
                        chunker = chunker,
                        enqueue = { chunk ->
                            utteranceSequence++
                            val utteranceId = UtteranceId("${request.userTurn.id.value}-u$utteranceSequence")
                            enqueueSpeech(this, chunk, utteranceId, machine, request.userTurn.id, trace, ttsJobs)
                        },
                    )
                val job =
                    launch {
                        streamResult = languageModel.consume(llmRequest, streamTrace)
                    }
                activeStreamJob = job
                job.join()
                activeStreamJob = null

                if (interruptRequested) {
                    machine.reduce(TurnEvent.InterruptRequested(request.userTurn.id, interruptOnsetAtNanos))
                    stopSpeech()
                } else {
                    val result = streamResult
                    chunker.flush()?.let(streamTrace::speak)
                    applyTerminal(machine, request.userTurn.id, result, requestSpan, trace)
                    recordReportedModel(request, result, trace)
                }
                awaitSpeech(ttsJobs, machine, request.userTurn.id)
            }
        } catch (cancellation: CancellationException) {
            ttsJobs.forEach { it.cancel() }
            throw cancellation
        } finally {
            activeStreamJob = null
            activeTtsJobs = null
        }
        return conversation
    }

    private fun applyTerminal(
        machine: TurnStateMachine,
        turnId: TurnId,
        result: LlmStreamResult?,
        requestSpan: TraceSpan,
        trace: TurnTraceRecorder,
    ) {
        when (val terminal = result?.terminal) {
            is LlmStreamEvent.Completed -> {
                requestSpan.succeed()
                machine.reduce(TurnEvent.ProviderCompleted(turnId))
            }

            is LlmStreamEvent.Failed -> {
                requestSpan.fail()
                machine.reduce(TurnEvent.ProviderFailed(turnId, terminal.error, result.text))
            }

            is LlmStreamEvent.Cancelled -> {
                requestSpan.cancel()
                machine.reduce(TurnEvent.ProviderCancelled(turnId, result.text))
            }

            // A flow that ended without a terminal event cannot say whether the
            // text was whole, so it is a typed failure, never a completion.
            null, is LlmStreamEvent.Delta -> {
                requestSpan.fail()
                trace.requestState(LlmRequestState.ENDED.wireName)
                trace.requestEndReason(ErrorCode.LLM_MALFORMED_RESPONSE.name)
                machine.reduce(
                    TurnEvent.ProviderFailed(
                        turnId = turnId,
                        error = VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE),
                        partialText = result?.text ?: machine.generatedText,
                    ),
                )
            }
        }
    }

    private suspend fun awaitSpeech(
        jobs: List<Job>,
        machine: TurnStateMachine,
        turnId: TurnId,
    ) {
        if (jobs.isEmpty()) return
        val completed = withTimeoutOrNull(deliveryTimeoutMillis) { jobs.joinAll() }
        if (completed == null) {
            AppLog.w { "orchestration: delivery wait timed out; stopping playback" }
            stopSpeech()
            jobs.forEach { it.cancel() }
            machine.reduce(TurnEvent.InterruptRequested(turnId, interruptOnsetAtNanos))
        }
    }

    private suspend fun stopSpeech() {
        try {
            textToSpeech?.stop()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            AppLog.w(ignored) { "orchestration: stopping playback failed" }
        }
    }

    // endregion

    // region persistence and tracing

    private suspend fun persistUserTurn(
        request: TurnRequest,
        trace: TurnTraceRecorder,
        observer: TurnObserver,
    ): Conversation? {
        val withUser =
            request.conversation.copy(
                updatedAtEpochMillis = wallClock(),
                turns = request.conversation.turns + request.userTurn,
            )
        val span = trace.start(DiagnosticStage.PERSISTENCE)
        return try {
            repository.save(withUser)
            span.succeed()
            observer.onUserTurnCommitted(withUser)
            withUser
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            span.fail()
            AppLog.e(failure) { "orchestration: persisting user turn failed" }
            null
        }
    }

    private suspend fun persistAssistant(
        machine: TurnStateMachine,
        conversation: Conversation,
        trace: TurnTraceRecorder,
    ): TurnResult {
        recordDelivery(trace, machine)
        finishTrace(trace, machine)
        val assistant: AssistantTurn? = machine.assistantTurn(assistantTurnId())
        if (assistant == null) {
            return TurnResult(
                conversation = conversation,
                record = machine.record(),
                assistantPersisted = false,
            )
        }
        val updated =
            conversation.copy(
                updatedAtEpochMillis = wallClock(),
                turns = conversation.turns + assistant,
            )
        val span = trace.start(DiagnosticStage.PERSISTENCE)
        return try {
            repository.save(updated)
            span.succeed()
            TurnResult(
                conversation = updated,
                record = machine.record(),
                assistantPersisted = true,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (throwable: Throwable) {
            span.fail()
            AppLog.e(throwable) { "orchestration: persisting assistant turn failed" }
            TurnResult(
                conversation = conversation,
                record = machine.record(),
                assistantPersisted = false,
                persistenceFailure = throwable.toVoiceAgentError(),
            )
        }
    }

    private fun finishTrace(
        trace: TurnTraceRecorder,
        machine: TurnStateMachine,
    ) {
        when (machine.outcome) {
            is TurnOutcome.Completed -> {
                trace.turnCompleted()
            }

            is TurnOutcome.ProviderError -> {
                trace.error(DiagnosticStage.LLM_REQUEST, machine.failure?.code?.name ?: ErrorCode.LLM_REQUEST_FAILED.name)
                trace.turnEnded(DiagnosticOutcome.FAILED)
            }

            is TurnOutcome.TtsFailure -> {
                trace.error(DiagnosticStage.TTS_PLAYBACK, machine.failure?.code?.name ?: ErrorCode.TTS_PLAYBACK_FAILED.name)
                trace.turnEnded(DiagnosticOutcome.FAILED)
            }

            is TurnOutcome.Interrupted -> {
                trace.turnEnded(DiagnosticOutcome.CANCELLED)
            }

            is TurnOutcome.Cancelled -> {
                trace.turnEnded(DiagnosticOutcome.CANCELLED)
            }

            is TurnOutcome.PersistenceFailure -> {
                trace.turnEnded(DiagnosticOutcome.FAILED)
            }

            is TurnOutcome.NoSpeech, is TurnOutcome.EmptyTranscript, null -> {}
        }
    }

    private fun recordReportedModel(
        request: TurnRequest,
        result: LlmStreamResult?,
        trace: TurnTraceRecorder,
    ) {
        val reported = result?.model ?: return
        if (reported.value == request.selection.modelId.value) return
        // A provider that served a different model than the selection is visible
        // in the trace; orchestration never reroutes or accepts it silently.
        AppLog.w { "orchestration: provider reported a different model than selected" }
        trace.progress(
            DiagnosticStage.LLM_REQUEST,
            mapOf(DiagnosticAttribute.REPORTED_MODEL_ID to reported.value),
        )
    }

    private fun recordDelivery(
        trace: TurnTraceRecorder,
        machine: TurnStateMachine,
    ) {
        if (textToSpeech == null) return
        val generated = machine.generatedText
        val delivered = machine.deliveredText
        if (generated.isEmpty() && delivered.isEmpty()) return
        trace.playbackDelivered(
            deliveredCharacterCount = delivered.length,
            totalCharacterCount = generated.length,
            interrupted = machine.outcome == TurnOutcome.Interrupted || machine.outcome == TurnOutcome.Cancelled,
        )
    }

    private fun buildRequest(
        request: TurnRequest,
        conversation: Conversation,
    ): LlmRequest =
        LlmRequest(
            model = request.selection,
            messages = contextBuilder.build(conversation).messages.map { it.toLlmMessage() },
            reasoning = request.reasoning,
        )

    // endregion

    private fun enqueueSpeech(
        scope: CoroutineScope,
        chunk: String,
        utteranceId: UtteranceId,
        machine: TurnStateMachine,
        turnId: TurnId,
        trace: TurnTraceRecorder,
        jobs: MutableList<Job>,
    ) {
        val speech = textToSpeech ?: return
        if (chunk.isBlank()) return
        // Account the queued chunk synchronously, before the speak flow is
        // collected, so a completed generation cannot settle as COMPLETED while a
        // chunk is still waiting to be spoken.
        machine.reduce(TurnEvent.TtsQueued(turnId, utteranceId, chunk))
        jobs +=
            scope.launch {
                speech.speak(chunk, utteranceId).collect { event ->
                    when (event) {
                        // Already accounted at enqueue time.
                        is TtsEvent.Queued -> {}

                        is TtsEvent.Started -> {
                            machine.reduce(TurnEvent.TtsStarted(turnId, utteranceId, event.text))
                            trace.playbackStarted(clock.nanoTime())
                        }

                        is TtsEvent.Delivered -> {
                            machine.reduce(TurnEvent.TtsDelivered(turnId, utteranceId, event.text))
                        }

                        is TtsEvent.Interrupted -> {
                            machine.reduce(TurnEvent.TtsInterrupted(turnId, utteranceId, event.deliveredText))
                        }

                        is TtsEvent.Failed -> {
                            machine.reduce(TurnEvent.TtsFailed(turnId, utteranceId, event.error))
                        }
                    }
                }
            }
    }

    private companion object {
        const val DEFAULT_DELIVERY_TIMEOUT_MILLIS: Long = 60_000L
    }
}

/** Bridges the streamed deltas to the UI, the state machine, and the TTS queue. */
private class OrchestrationStreamTrace(
    private val machine: TurnStateMachine,
    private val turnId: TurnId,
    private val trace: TurnTraceRecorder,
    private val clock: MonotonicClock,
    private val observer: TurnObserver,
    private val chunker: TtsTextChunker,
    private val enqueue: (String) -> Unit,
) : TurnStreamTrace {
    fun speak(text: String) {
        if (machine.phase.isTerminal) return
        enqueue(text)
    }

    override fun onDelta(
        index: Int,
        characterCount: Int,
        text: String,
    ) {
        machine.reduce(TurnEvent.ProviderDelta(turnId, text))
        // Only the delta index and its length reach the trace; never the text.
        trace.llmDelta(clock.nanoTime(), characterCount)
        observer.onLiveAssistantText(machine.generatedText)
        chunker.append(text).forEach(::speak)
    }

    override fun onCompleted(event: LlmStreamEvent.Completed) {
        trace.requestState(LlmRequestState.COMPLETED.wireName)
        trace.requestEndReason(LlmRequestState.COMPLETED.wireName)
        trace.requestUsage(
            promptTokens = event.usage?.promptTokens,
            completionTokens = event.usage?.completionTokens,
            totalTokens = event.usage?.totalTokens,
        )
    }

    override fun onEnded(
        reason: String,
        characterCount: Int,
    ) {
        trace.requestState(LlmRequestState.ENDED.wireName)
        trace.requestEndReason(reason)
    }
}

private fun ContextMessage.toLlmMessage(): LlmMessage =
    LlmMessage(
        role =
            when (role) {
                ContextRole.USER -> LlmRole.USER
                ContextRole.ASSISTANT -> LlmRole.ASSISTANT
            },
        content = text,
    )

private fun Throwable.toVoiceAgentError(): VoiceAgentError =
    (this as? VoiceAgentException)?.error ?: VoiceAgentError(ErrorCode.UNKNOWN, retryable = false)
