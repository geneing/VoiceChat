package com.voicechat.agent.voice

import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.orchestration.TurnObserver
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.orchestration.TurnRequest
import com.voicechat.agent.orchestration.TurnResult
import com.voicechat.agent.vad.EndpointReason
import com.voicechat.agent.vad.TurnDetectionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The M24 voice session coordinator: the pure-Kotlin core that wires capture →
 * VAD/onset → STT → turn completion → [TurnOrchestrator] → TTS with responsive
 * barge-in.
 *
 * **Two independent decisions.** The fast onset path (M09 activity) starts a new
 * logical turn and stops assistant playback; the bounded endpoint policy decides
 * when a turn is finished. The coordinator never runs a semantic model on the
 * onset path, so a barge-in does not wait for end-of-turn inference.
 *
 * **Barge-in does not wait for cancellation.** On a speech onset while a
 * generation is active, the coordinator immediately calls
 * [TurnOrchestrator.interrupt] and stops audible playback, then starts a new
 * capture/recognition turn *without* joining the interrupted work. The
 * interrupted turn settles asynchronously and is persisted with only the
 * delivered prefix. Onset-to-stop and onset-to-capture-resumed are recorded as
 * [BargeInTiming] and as privacy-safe diagnostics.
 *
 * **Turn IDs drop stale events.** Interim STT revisions are delivered only for
 * the active listening turn, and live assistant text only for the active
 * generation turn, so a late event from a superseded turn can never update the
 * dialog. Inside the turn, the M21 [com.voicechat.agent.orchestration.TurnStateMachine]
 * applies the same rule to provider and TTS events.
 *
 * **Truthful history.** The coordinator adopts only the orchestrator's persisted
 * [TurnResult.conversation]; an interrupted reply is stored with the delivered
 * prefix, never the unheard suffix. A barge-in that turns out to be noise commits
 * no turn and leaves the interrupted reply interrupted
 * ([VoiceInterruptionRecovery.NO_USABLE_SPEECH]).
 *
 * The class is `android.*`-free and JVM-testable; platform capture, STT, and TTS
 * enter only through the M02 contracts.
 */
class VoiceSessionCoordinator(
    private val audioInput: AudioInput,
    private val speechToText: SpeechToText,
    private val textToSpeech: TextToSpeech?,
    private val turnDetector: VoiceTurnDetector,
    private val orchestratorFactory: () -> TurnOrchestrator,
    private val providerSource: VoiceTurnProviderSource,
    private val listener: VoiceSessionListener = VoiceSessionListener.NONE,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val turnIdFactory: () -> TurnId = { TurnId("voice-${UUID.randomUUID()}") },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : VoiceSessionController {
    private val _state = MutableStateFlow(VoiceSessionState.IDLE)

    /** Observable session state, for the dialog. */
    override val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

    private var conversation: Conversation? = null

    @Volatile
    private var sessionScope: CoroutineScope? = null

    @Volatile
    private var captureJob: Job? = null

    @Volatile
    private var detectInput: Channel<AudioFrame>? = null

    @Volatile
    private var activeListening: ListeningTurn? = null

    @Volatile
    private var detectorJob: Job? = null

    /**
     * The most recent endpoint's STT finalization, tracked separately from the
     * detector collector so finalizing one turn never blocks detection of the
     * next (CODE_REVIEW P1, R-0225). Each finalization joins the previous one
     * before committing, so turns persist in spoken order even if a stalled
     * recognizer's timeout fires out of order.
     */
    @Volatile
    private var finalizationJob: Job? = null

    /**
     * The single pending/running generation, carrying the turn id, its job, and
     * the orchestrator once one is attached (CODE_REVIEW P1, R-0226). A job may
     * be active before its orchestrator exists (it is queued behind the previous
     * turn), so "job active" is not by itself proof of an interruptible provider
     * request.
     */
    private var activeGeneration: ActiveGeneration? = null

    @Volatile
    private var stopped = false

    private val bargeInRecords = mutableListOf<MutableBargeIn>()

    /**
     * Guards the generation identity fields ([activeGeneration], [conversation])
     * and the listening-turn/finalization handoff. Those fields are written by
     * the generation and finalization coroutines and read by the
     * detection/capture coroutines, so a plain field read is a data race. The
     * lock is held only for short field reads/writes — never across a suspension
     * or a listener callback — so it cannot stall the onset/barge-in path.
     */
    private val stateLock = Any()

    /** Barge-in timings recorded so far, oldest first. */
    val bargeIns: List<BargeInTiming> get() = synchronized(bargeInRecords) { bargeInRecords.map { it.snapshot() } }

    override suspend fun run(conversation: Conversation) {
        synchronized(stateLock) { this.conversation = conversation }
        stopped = false
        coroutineScope {
            val scope = this
            sessionScope = scope
            setState(VoiceSessionState.LISTENING)
            val input = Channel<AudioFrame>(capacity = DETECT_BUFFER)
            detectInput = input
            captureJob =
                scope.launch(dispatcher) {
                    pumpCapture(scope, input)
                }
            val detector =
                scope.launch(dispatcher) {
                    turnDetector.detect(input.receiveAsFlow()).collect { handleDetection(it) }
                }
            detectorJob = detector
            try {
                detector.join()
            } finally {
                detector.cancel()
                detectorJob = null
                captureJob?.cancel()
                captureJob = null
                detectInput = null
                activeListening?.let { turn ->
                    turn.input.close()
                    turn.job?.cancel()
                }
                activeListening = null
                finalizationJob?.cancel()
                finalizationJob = null
                val inFlightGeneration =
                    synchronized(stateLock) {
                        activeGeneration.also { activeGeneration = null }
                    }
                inFlightGeneration?.job?.cancel()
                sessionScope = null
                setState(if (stopped) VoiceSessionState.STOPPED else VoiceSessionState.IDLE)
            }
        }
    }

    override fun stop() {
        stopped = true
        detectorJob?.cancel()
        detectInput?.close()
        captureJob?.cancel()
        finalizationJob?.cancel()
        activeListening?.let { it.input.close() }
        val generation = synchronized(stateLock) { activeGeneration }
        generation?.orchestrator?.interrupt(onsetAtNanos = null)
        generation?.job?.cancel()
    }

    // region capture

    private suspend fun pumpCapture(
        scope: CoroutineScope,
        input: Channel<AudioFrame>,
    ) {
        try {
            audioInput.frames().collect { frame ->
                input.send(frame)
                activeListening?.let { turn ->
                    try {
                        turn.input.send(frame)
                    } catch (ignored: ClosedSendChannelException) {
                        // The turn ended while this frame was in flight; drop it.
                    }
                }
            }
        } finally {
            input.close()
            activeListening?.let { it.input.close() }
        }
    }

    // endregion

    // region detection

    private suspend fun handleDetection(event: TurnDetectionEvent) {
        when (event) {
            is TurnDetectionEvent.Failed -> {
                fail(event.error)
            }

            is TurnDetectionEvent.Held -> {
                Unit
            }

            is TurnDetectionEvent.Activity -> {
                when (event.activity) {
                    SpeechActivity.SPEECH_STARTED, SpeechActivity.SPEECH_RESUMED -> onSpeechActive()
                    SpeechActivity.CANDIDATE_PAUSE -> Unit
                }
            }

            is TurnDetectionEvent.Endpointed -> {
                onEndpointed(event)
            }
        }
    }

    private fun onSpeechActive() {
        val bargeIn = if (isGenerating()) bargeIn() else null
        if (activeListening == null) {
            beginListening()
        }
        // Capture/recognition for the new utterance is now running. Stamping and
        // notifying here (not inside bargeIn) means the reported timing proves the
        // loop restarted capture without waiting for the cancellation to settle.
        bargeIn?.let { record ->
            if (record.captureResumedAtNanos == null) record.captureResumedAtNanos = clock.nanoTime()
            listener.onBargeIn(record.snapshot())
        }
    }

    /**
     * Ends the active listening turn and hands its completion to an owned
     * finalization job, so the detector collector keeps draining audio and
     * events while the recognizer finishes (CODE_REVIEW P1, R-0225).
     */
    private fun onEndpointed(event: TurnDetectionEvent.Endpointed) {
        val turn = activeListening
        if (turn == null) {
            if (event.reason == EndpointReason.EMPTY_NO_SPEECH) listener.onNoSpeech()
            return
        }
        activeListening = null
        turn.input.close()
        val scope = sessionScope ?: return
        val previous = synchronized(stateLock) { finalizationJob }
        val job =
            scope.launch(dispatcher) {
                // Commit in spoken order: a later endpoint waits for the earlier
                // finalization to settle, so two overlapping turns cannot persist
                // out of order when one recognizer stalls.
                previous?.join()
                finalizeTurn(turn)
            }
        synchronized(stateLock) { finalizationJob = job }
    }

    /**
     * Waits, bounded, for the recognizer to finish after its input closed, then
     * commits, rejects, or fails the turn. The bound is a final cap on the STT
     * job — never time spent blocking detection.
     */
    private suspend fun finalizeTurn(turn: ListeningTurn) {
        val completed =
            withTimeoutOrNull(STT_COMPLETION_TIMEOUT_MILLIS) {
                turn.job?.join()
                true
            } ?: false
        if (!completed) {
            turn.failure =
                VoiceAgentError(
                    ErrorCode.STT_RECOGNITION_FAILED,
                    "the recognizer did not finish within ${STT_COMPLETION_TIMEOUT_MILLIS} ms of input end",
                )
            turn.job?.cancel()
            diagnostics.record(
                DiagnosticEvent(
                    stage = DiagnosticStage.SPEECH_TO_TEXT,
                    outcome = DiagnosticOutcome.CANCELLED,
                    monotonicTimeNanos = clock.nanoTime(),
                    turnId = turn.turnId,
                    attributes = mapOf(DiagnosticAttribute.REQUEST_STATE to "completion_timeout"),
                ),
            )
        }
        finishTurn(turn)
    }

    private fun isGenerating(): Boolean = synchronized(stateLock) { activeGeneration?.job?.isActive == true }

    // endregion

    // region listening turn

    private fun beginListening() {
        val scope = sessionScope ?: return
        val turnId = turnIdFactory()
        val input = Channel<AudioFrame>(capacity = STT_BUFFER)
        val turn = ListeningTurn(turnId, input)
        turn.job =
            scope.launch(dispatcher) {
                try {
                    speechToText.transcribe(input.receiveAsFlow()).collect { event -> onSttEvent(turn, event) }
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    turn.failure = failure.toVoiceAgentError()
                } finally {
                    input.close()
                }
            }
        activeListening = turn
        listener.onListeningStarted(turnId)
        setState(VoiceSessionState.LISTENING)
        AppLog.d { "voice: listening turn start" }
    }

    private fun onSttEvent(
        turn: ListeningTurn,
        event: SttEvent,
    ) {
        when (event) {
            is SttEvent.Result -> {
                if (event.transcript.isFinal) {
                    turn.final = event.transcript
                } else {
                    // Interim revisions must be strictly newer; a stale guess is
                    // dropped, never applied (mirrors the M21 revision rule).
                    val previous = turn.provisional
                    if (previous != null && event.transcript.revision.value <= previous.revision.value) return
                    turn.provisional = event.transcript
                    // Only the active turn's interim text reaches the dialog; a late
                    // revision from a superseded turn is dropped, never applied.
                    if (activeListening === turn) {
                        listener.onProvisionalTranscript(turn.turnId, event.transcript.text)
                    }
                }
            }

            is SttEvent.Failed -> {
                turn.failure = event.error
            }
        }
    }

    private suspend fun finishTurn(turn: ListeningTurn) {
        val failure = turn.failure
        val final = turn.final
        when {
            failure != null -> {
                AppLog.w { "voice: listening turn failed code=${failure.code}" }
                fail(failure)
            }

            final != null && final.text.isNotBlank() -> {
                commitTurn(turn.turnId, final)
            }

            else -> {
                rejectTurn(turn)
            }
        }
    }

    private fun rejectTurn(turn: ListeningTurn) {
        if (pendingBargeIn() != null) {
            // The onset that stopped playback did not become usable speech: a
            // false/noise interruption. No turn is committed and the interrupted
            // reply stays interrupted with only its delivered prefix.
            resolveBargeIn(VoiceInterruptionRecovery.NO_USABLE_SPEECH)
            listener.onInterruptionRecovered(VoiceInterruptionRecovery.NO_USABLE_SPEECH)
        }
        AppLog.d { "voice: turn rejected (no usable speech)" }
        listener.onNoSpeech()
        setState(if (activeListening != null) VoiceSessionState.LISTENING else VoiceSessionState.IDLE)
    }

    // endregion

    // region generation

    private suspend fun commitTurn(
        turnId: TurnId,
        transcript: Transcript,
    ) {
        val scope = sessionScope ?: return
        val base = synchronized(stateLock) { conversation } ?: return
        val provider = providerSource.providerFor(base)
        resolveBargeIn(VoiceInterruptionRecovery.COMMITTED)
        listener.onInterruptionRecovered(VoiceInterruptionRecovery.COMMITTED)
        listener.onUtteranceCommitted(turnId, transcript)
        setState(VoiceSessionState.WORKING)
        val previous = synchronized(stateLock) { activeGeneration }
        val generation = ActiveGeneration(turnId)
        val job =
            scope.launch(dispatcher) {
                try {
                    // Serialize persistence: the new turn's context must include the
                    // interrupted turn's stored truth, and two saves must not race.
                    previous?.job?.join()
                    val convo = synchronized(stateLock) { conversation } ?: return@launch
                    val request =
                        TurnRequest(
                            conversation = convo,
                            userTurn = UserTurn(id = turnId, transcript = transcript, source = UserTurnSource.VOICE),
                            selection = provider.selection,
                            reasoning = provider.reasoning,
                        )
                    val orchestrator = orchestratorFactory()
                    // Attach the interruptible request atomically: a barge-in in the
                    // window before this point finds no orchestrator and cancels the
                    // queued job instead (CODE_REVIEW P1, R-0226).
                    generation.orchestrator = orchestrator
                    orchestrator.run(
                        request = request,
                        observer = coordinatorObserver(turnId),
                        languageModel = provider.languageModel,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    fail(failure.toVoiceAgentError())
                } finally {
                    synchronized(stateLock) {
                        if (activeGeneration === generation) activeGeneration = null
                    }
                }
            }
        generation.job = job
        synchronized(stateLock) { activeGeneration = generation }
    }

    private fun coordinatorObserver(turnId: TurnId): TurnObserver =
        object : TurnObserver {
            override fun onUserTurnCommitted(conversation: Conversation) {
                synchronized(stateLock) { this@VoiceSessionCoordinator.conversation = conversation }
                listener.onConversationChanged(conversation)
            }

            override fun onLiveAssistantText(text: String) {
                if (currentGenerationTurnId() != turnId) return
                listener.onAssistantText(turnId, text)
                if (textToSpeech != null && _state.value == VoiceSessionState.WORKING) {
                    setState(VoiceSessionState.SPEAKING)
                }
            }

            override fun onTurnFinished(result: TurnResult) {
                synchronized(stateLock) {
                    this@VoiceSessionCoordinator.conversation = result.conversation
                }
                settleBargeIn(turnId)
                listener.onTurnFinished(result)
                // A turn that settles after a newer generation started must not
                // clobber the newer turn's session state (stale-state rule).
                val active = currentGenerationTurnId()
                if (active == null || active == turnId) {
                    setState(if (activeListening != null) VoiceSessionState.LISTENING else VoiceSessionState.IDLE)
                }
            }
        }

    private fun currentGenerationTurnId(): TurnId? = synchronized(stateLock) { activeGeneration?.turnId }

    // endregion

    // region barge-in

    private fun bargeIn(): MutableBargeIn? {
        // One atomic read of the pending/running generation, so the onset cannot
        // observe a job and an orchestrator from different turns (CODE_REVIEW P1).
        val generation = synchronized(stateLock) { activeGeneration }
        val onset = clock.nanoTime()
        val orchestrator = generation?.orchestrator
        if (orchestrator == null) {
            // A job can be active before a provider request exists (it is queued
            // behind the previous turn). "Job active" is not proof of an
            // interruptible request: the superseded queued turn is replaced by
            // the new utterance without a false barge-in record. There is no
            // audible playback to stop yet.
            generation?.job?.cancel()
            return null
        }
        orchestrator.interrupt(onset)
        val stopIssued = clock.nanoTime()
        val interruptedTurnId = generation.turnId
        val record =
            MutableBargeIn(
                interruptedTurnId = interruptedTurnId,
                onsetAtNanos = onset,
                stopIssuedAtNanos = stopIssued,
            )
        synchronized(bargeInRecords) { bargeInRecords += record }
        recordDiagnostic(stage = DiagnosticStage.TURN, outcome = DiagnosticOutcome.CANCELLED, record = record)
        AppLog.d { "voice: barge-in onset->stop=${record.onsetToStopNanos()}ns" }
        // Stop audible playback at once, from another coroutine, and do not wait.
        sessionScope?.launch(dispatcher) {
            try {
                textToSpeech?.stop()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (ignored: Throwable) {
                AppLog.w(ignored) { "voice: stopping playback after barge-in failed" }
            }
        }
        return record
    }

    private fun resolveBargeIn(recovery: VoiceInterruptionRecovery) {
        val record = pendingBargeIn() ?: return
        if (record.recovery == null) {
            record.recovery = recovery
            recordDiagnostic(stage = DiagnosticStage.TURN, outcome = DiagnosticOutcome.PROGRESS, record = record)
        }
    }

    private fun settleBargeIn(turnId: TurnId) {
        val record =
            synchronized(bargeInRecords) {
                bargeInRecords.lastOrNull { it.interruptedTurnId == turnId && it.settledAtNanos == null }
            } ?: return
        record.settledAtNanos = clock.nanoTime()
        recordDiagnostic(stage = DiagnosticStage.TURN, outcome = DiagnosticOutcome.COMPLETED, record = record)
    }

    private fun pendingBargeIn(): MutableBargeIn? = synchronized(bargeInRecords) { bargeInRecords.lastOrNull { it.recovery == null } }

    private fun recordDiagnostic(
        stage: DiagnosticStage,
        outcome: DiagnosticOutcome,
        record: MutableBargeIn,
    ) {
        val attributes =
            buildMap {
                put(DiagnosticAttribute.BARGE_IN, "true")
                put(DiagnosticAttribute.BARGE_IN_STOP_MILLIS, millis(record.onsetToStopNanos()).toString())
                record.captureResumedAtNanos?.let {
                    put(DiagnosticAttribute.BARGE_IN_CAPTURE_RESUMED_MILLIS, millis(it - record.onsetAtNanos).toString())
                }
            }
        diagnostics.record(
            DiagnosticEvent(
                stage = stage,
                outcome = outcome,
                monotonicTimeNanos = clock.nanoTime(),
                turnId = record.interruptedTurnId,
                attributes = attributes,
            ),
        )
    }

    // endregion

    private fun setState(state: VoiceSessionState) {
        // Once the user (or capture end) stopped the session, STOPPED is terminal:
        // a turn that settles asynchronously after stop must not clobber it back to
        // IDLE/LISTENING. The interrupted turn is still persisted truthfully.
        if (stopped && state != VoiceSessionState.STOPPED) return
        _state.value = state
        listener.onSessionState(state)
    }

    private fun fail(error: VoiceAgentError) {
        AppLog.w { "voice: session error code=${error.code}" }
        listener.onError(error)
        setState(VoiceSessionState.FAILED)
    }

    private fun millis(nanos: Long): Long = nanos / NANOS_PER_MILLI

    private class ListeningTurn(
        val turnId: TurnId,
        val input: Channel<AudioFrame>,
    ) {
        var job: Job? = null
        var provisional: Transcript? = null
        var final: Transcript? = null
        var failure: VoiceAgentError? = null
    }

    /**
     * One pending or running generation: its turn, its job, and — once attached —
     * the orchestrator that owns the interruptible provider request. The
     * orchestrator is `@Volatile` because it is attached by the generation
     * coroutine and read by the onset path.
     */
    private class ActiveGeneration(
        val turnId: TurnId,
    ) {
        @Volatile
        var job: Job? = null

        @Volatile
        var orchestrator: TurnOrchestrator? = null
    }

    private class MutableBargeIn(
        val interruptedTurnId: TurnId,
        val onsetAtNanos: Long,
        val stopIssuedAtNanos: Long,
        var captureResumedAtNanos: Long? = null,
        var settledAtNanos: Long? = null,
        var recovery: VoiceInterruptionRecovery? = null,
    ) {
        fun onsetToStopNanos(): Long = stopIssuedAtNanos - onsetAtNanos

        fun snapshot(): BargeInTiming =
            BargeInTiming(
                interruptedTurnId = interruptedTurnId,
                onsetAtNanos = onsetAtNanos,
                stopIssuedAtNanos = stopIssuedAtNanos,
                captureResumedAtNanos = captureResumedAtNanos,
                settledAtNanos = settledAtNanos,
                recovery = recovery,
            )
    }

    private companion object {
        /** Frames the detector pipeline may hold; keeps capture non-blocking while a turn finalizes. */
        const val DETECT_BUFFER = 256

        /** Frames one listening turn may queue for the recognizer. */
        const val STT_BUFFER = 128

        /**
         * Bound on waiting for the recognizer to finish after its input closes.
         * A stalled engine must not hold the session in listening state; on
         * timeout the turn fails with a typed error and the session recovers.
         */
        const val STT_COMPLETION_TIMEOUT_MILLIS = 5_000L

        const val NANOS_PER_MILLI = 1_000_000L
    }
}

private fun Throwable.toVoiceAgentError(): VoiceAgentError =
    (this as? VoiceAgentException)?.error ?: VoiceAgentError(ErrorCode.UNKNOWN, retryable = false)
