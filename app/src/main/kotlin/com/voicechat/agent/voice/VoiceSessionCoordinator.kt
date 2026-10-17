package com.voicechat.agent.voice

import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The voice conversation loop: microphone capture → VAD/onset → streaming STT →
 * bounded VAD/Smart Turn endpointing → transcript persistence → assistant reply
 * (LLM + TTS) → capture continues.
 *
 * The assistant is an optional seam ([assistant]). With `null` the coordinator is
 * the STT-only loop of M24: a finalized transcript is saved as a user turn and no
 * assistant turn is generated. With a [VoiceAssistant] wired, the assistant owns
 * both turns through M21 orchestration, and this loop owns the conversational
 * sequencing the guidelines for this milestone require:
 *
 * 1. **Capture never stops** while the assistant speaks; VAD and STT run against
 *    the same incoming audio, so the next turn starts being recognized while the
 *    reply is still being delivered.
 * 2. **VAD onset interrupts immediately.** [VoiceAssistant.interrupt] is called
 *    from the detection path at the moment speech activity starts — before any
 *    transcript exists and before Smart Turn runs — so playback stops and the
 *    in-flight request is cancelled without waiting for a recognition result.
 *    Orchestration records how much audio was actually audible, and the
 *    interrupted assistant turn is persisted with that delivered prefix.
 * 3. **STT partials keep updating the transcript** for the new turn; the
 *    recognizer's own end-of-speech signal stays corroborating only.
 * 4. **Smart Turn runs after a VAD-confirmed pause**, never on every frame and
 *    never as the barge-in detector, and the bounded silence cap remains the
 *    fallback when STT or the detector stalls.
 *
 * The semantic endpoint detector is evaluated only by [VoiceTurnDetector] after a
 * VAD-confirmed pause. The recognizer's own end-of-speech signal
 * ([SttEvent.EndOfSpeech]) is surfaced for timing but never ends a turn: the
 * bounded VAD/Smart Turn endpoint stays the authority, so an early engine signal
 * cannot truncate a still-speaking user.
 */
class VoiceSessionCoordinator(
    private val audioInput: AudioInput,
    private val repository: ConversationRepository,
    private val speechToText: SpeechToText,
    private val turnDetector: VoiceTurnDetector,
    /**
     * The assistant reply path (LLM + TTS), or `null` for the STT-only loop. A
     * wired assistant owns persisting the user and assistant turns; with `null`
     * this coordinator persists the transcript itself.
     */
    private val assistant: VoiceAssistant? = null,
    private val listener: VoiceSessionListener = VoiceSessionListener.NONE,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val turnIdFactory: () -> TurnId = { TurnId("voice-${UUID.randomUUID()}") },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /**
     * Frames of capture kept as a pre-roll and handed to a new turn before the
     * live audio. VAD onset fires a few frames after speech starts, so without
     * this the opening word(s) never reach the recognizer. 50 frames ≈ 1.0 s at
     * the 20 ms capture frame.
     */
    private val preRollFrames: Int = DEFAULT_PRE_ROLL_FRAMES,
) : VoiceSessionController {
    private val _state = MutableStateFlow(VoiceSessionState.IDLE)

    /** Recent capture frames, seeded into each new turn before its live audio. */
    private val preRoll = CapturePreRoll(preRollFrames)

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
     * Endpoint finalization is kept separate from detection so a recognizer that
     * is finishing one turn cannot block VAD from seeing the next turn.
     */
    @Volatile
    private var finalizationJob: Job? = null

    /** The in-flight assistant reply, if any; joined before the next commit. */
    @Volatile
    private var assistantJob: Job? = null

    /** True while an assistant reply is being generated or spoken. */
    @Volatile
    private var assistantActive: Boolean = false

    /** Monotonic time of the speech onset that interrupted the assistant, if any. */
    @Volatile
    private var assistantOnsetAtNanos: Long? = null

    @Volatile
    private var stopped = false

    /** Guards the conversation snapshot and finalization handoff. */
    private val stateLock = Any()

    override suspend fun run(conversation: Conversation) {
        synchronized(stateLock) { this.conversation = conversation }
        stopped = false
        coroutineScope {
            val scope = this
            sessionScope = scope
            setState(VoiceSessionState.LISTENING)
            val input = Channel<AudioFrame>(capacity = DETECT_BUFFER)
            detectInput = input
            captureJob = scope.launch(dispatcher) { pumpCapture(input) }
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
                // Stop any audible reply before waiting for the pending turn, so
                // the interrupted assistant turn is persisted with exactly what the
                // user heard.
                if (assistantActive) {
                    assistant?.interrupt(clock.nanoTime())
                }
                // The endpoint policy may emit CAPTURE_ENDED as its input closes.
                // Allow that final STT completion its bounded window before teardown.
                finalizationJob?.let { job ->
                    if (withTimeoutOrNull(STT_COMPLETION_TIMEOUT_MILLIS) { job.join() } == null) {
                        AppLog.d { "voice: capture ended before the pending transcript finalized" }
                    }
                }
                finalizationJob?.cancel()
                finalizationJob = null
                assistantActive = false
                assistantOnsetAtNanos = null
                sessionScope = null
                setState(if (stopped) VoiceSessionState.STOPPED else VoiceSessionState.IDLE)
            }
        }
    }

    override fun stop() {
        stopped = true
        // Stop playback before cancelling the turn: the assistant's interrupted
        // turn is still persisted (M21 does that under NonCancellable) and must
        // record only the audio that was actually delivered.
        if (assistantActive) {
            assistant?.interrupt(clock.nanoTime())
        }
        detectorJob?.cancel()
        detectInput?.close()
        captureJob?.cancel()
        finalizationJob?.cancel()
        activeListening?.let { it.input.close() }
        setState(VoiceSessionState.STOPPED)
    }

    private suspend fun pumpCapture(input: Channel<AudioFrame>) {
        try {
            audioInput.frames().collect { frame ->
                input.send(frame)
                val turn = activeListening
                if (turn != null) {
                    // A turn that just started gets the audio captured before its
                    // onset, so the recognizer does not lose the opening words. The
                    // pre-roll is flushed once, before this frame, keeping the order.
                    if (turn.preRollPending.compareAndSet(true, false)) {
                        val preRollFrames = preRoll.snapshot()
                        if (preRollFrames.isNotEmpty()) {
                            AppLog.d { "voice: seeding turn with ${preRollFrames.size} pre-roll frame(s)" }
                            for (buffered in preRollFrames) {
                                try {
                                    turn.input.send(buffered)
                                } catch (ignored: ClosedSendChannelException) {
                                    break
                                }
                            }
                        }
                    }
                    try {
                        turn.input.send(frame)
                    } catch (ignored: ClosedSendChannelException) {
                        // The endpoint arrived while this frame was in flight.
                    }
                }
                // Recorded after the flush so the current frame is not part of it.
                preRoll.record(frame)
            }
        } finally {
            input.close()
            activeListening?.let { it.input.close() }
        }
    }

    private suspend fun handleDetection(event: TurnDetectionEvent) {
        when (event) {
            is TurnDetectionEvent.Failed -> {
                fail(event.error)
            }

            is TurnDetectionEvent.Held -> {}

            is TurnDetectionEvent.Activity -> {
                when (event.activity) {
                    SpeechActivity.SPEECH_STARTED, SpeechActivity.SPEECH_RESUMED -> {
                        // Barge-in first: the assistant stops now, before any
                        // transcript or Smart Turn decision exists. The same onset
                        // then starts the next turn's recognition below, on the
                        // audio that is already being captured.
                        interruptAssistantAtOnset()
                        beginListeningIfNeeded(event.atOffsetMillis)
                    }

                    SpeechActivity.CANDIDATE_PAUSE -> {}
                }
            }

            is TurnDetectionEvent.Endpointed -> {
                onEndpointed(event)
            }
        }
    }

    private fun beginListeningIfNeeded(atOffsetMillis: Long) {
        if (activeListening == null) beginListening(atOffsetMillis)
    }

    /**
     * Stops the assistant at VAD speech onset and records the onset→stop edge.
     *
     * This is the barge-in fast path described in the loop's documentation: it
     * never waits for a transcript, a recognizer result, or Smart Turn, because
     * the user's speech is already the decision. The assistant is left to persist
     * what it actually delivered; only the state returns to listening here, so a
     * long reply cannot delay the next turn.
     */
    private fun interruptAssistantAtOnset() {
        val active = assistant ?: return
        if (!assistantActive) return
        val onsetAtNanos = clock.nanoTime()
        assistantOnsetAtNanos = onsetAtNanos
        setState(VoiceSessionState.LISTENING)
        AppLog.d { "voice: barge-in onset; interrupting the assistant" }
        active.interrupt(onsetAtNanos)
        diagnostics.record(
            DiagnosticEvent(
                stage = DiagnosticStage.TTS_PLAYBACK,
                outcome = DiagnosticOutcome.PROGRESS,
                monotonicTimeNanos = clock.nanoTime(),
                attributes =
                    mapOf(
                        DiagnosticAttribute.STREAM_STATE to "barge-in-onset",
                    ),
            ),
        )
    }

    /** Closes one recognition input and lets the detector continue collecting. */
    private fun onEndpointed(event: TurnDetectionEvent.Endpointed) {
        val turn = activeListening
        if (turn == null) {
            if (event.reason == EndpointReason.EMPTY_NO_SPEECH) listener.onNoSpeech()
            return
        }
        turn.vadEndpointOffsetMillis = (event.atOffsetMillis - turn.startedAtOffsetMillis).coerceAtLeast(0L)
        activeListening = null
        // Do not let the next turn inherit this utterance's tail as its pre-roll.
        preRoll.clear()
        turn.input.close()
        val scope = sessionScope ?: return
        val previous = synchronized(stateLock) { finalizationJob }
        val job =
            scope.launch(dispatcher) {
                previous?.join()
                finalizeTurn(turn)
            }
        synchronized(stateLock) { finalizationJob = job }
    }

    /** Waits, bounded, for STT to finish after its input has closed. */
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

    private fun beginListening(startedAtOffsetMillis: Long) {
        val scope = sessionScope ?: return
        val turnId = turnIdFactory()
        val input = Channel<AudioFrame>(capacity = STT_BUFFER)
        val turn = ListeningTurn(turnId, input, startedAtOffsetMillis)
        activeListening = turn
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
                AppLog.v {
                    "voice: stt event turn=${turn.turnId.value} final=${event.transcript.isFinal} " +
                        "revision=${event.transcript.revision.value} chars=${event.transcript.text.length} " +
                        "confidence=${event.transcript.confidence}"
                }
                if (event.transcript.isFinal) {
                    turn.final =
                        event.transcript.copy(
                            text = appendTranscriptSegment(turn.final?.text, event.transcript.text),
                        )
                    turn.provisional = null
                    if (activeListening === turn) {
                        listener.onProvisionalTranscript(turn.turnId, turn.final?.text.orEmpty())
                    }
                } else {
                    val previous = turn.provisional
                    if (previous != null && event.transcript.revision.value <= previous.revision.value) {
                        AppLog.v { "voice: stale STT interim revision dropped" }
                        return
                    }
                    turn.provisional = event.transcript
                    if (activeListening === turn) {
                        listener.onProvisionalTranscript(
                            turn.turnId,
                            appendTranscriptSegment(turn.final?.text, event.transcript.text),
                        )
                    }
                }
            }

            SttEvent.EndOfSpeech -> {
                AppLog.i { "voice: recognizer end of speech turn=${turn.turnId.value}" }
                if (activeListening === turn) {
                    listener.onSttEndOfSpeech(turn.turnId)
                }
            }

            is SttEvent.Failed -> {
                AppLog.v { "voice: stt event turn=${turn.turnId.value} failed code=${event.error.code}" }
                turn.failure = event.error
            }
        }
    }

    /** Joins a committed segment to the next recognizer segment for one VAD turn. */
    private fun appendTranscriptSegment(
        prefix: String?,
        segment: String,
    ): String {
        val completed = prefix?.trimEnd().orEmpty()
        val next = segment.trimStart()
        if (completed.isEmpty()) return next
        if (next.isEmpty()) return completed
        val startsWithPunctuation = next.first() in ".,!?;:%)]}’”'"
        return if (startsWithPunctuation) completed + next else "$completed $next"
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
                if (assistant == null) {
                    commitTranscript(turn.turnId, final)
                } else {
                    runAssistantTurn(turn.turnId, final)
                }
            }

            else -> {
                rejectTurn()
            }
        }
    }

    /**
     * Runs one assistant reply for a finalized transcript.
     *
     * The conversation snapshot is taken **before** the user turn, because the
     * assistant persists the user and assistant turns itself through M21
     * orchestration. That is what keeps the saved order truthful after a
     * barge-in, and it is why this path never writes the transcript itself.
     *
     * A provider, TTS, or persistence failure is surfaced through
     * [VoiceSessionListener.onError] and the loop keeps listening: a failed reply
     * is visible and recoverable, never a dead session and never a silent
     * fallback to another model.
     */
    private suspend fun runAssistantTurn(
        turnId: TurnId,
        transcript: Transcript,
    ) {
        val active = assistant ?: return
        val base = synchronized(stateLock) { conversation } ?: return
        val userTurn = UserTurn(id = turnId, transcript = transcript, source = UserTurnSource.VOICE)
        assistantActive = true
        assistantOnsetAtNanos = null
        setState(VoiceSessionState.SPEAKING)
        val attempt =
            try {
                AssistantAttempt.Replied(active.reply(VoiceAssistantRequest(conversation = base, userTurn = userTurn), listener))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AssistantAttempt.Threw(failure.toVoiceAgentError())
            } finally {
                assistantActive = false
            }
        when (attempt) {
            is AssistantAttempt.Threw -> {
                AppLog.w { "voice: assistant reply threw code=${attempt.error.code}" }
                listener.onError(attempt.error)
                setState(VoiceSessionState.LISTENING)
            }

            is AssistantAttempt.Replied -> {
                val reply = attempt.reply
                synchronized(stateLock) { conversation = reply.conversation }
                AppLog.d {
                    "voice: assistant reply done outcome=${reply.outcome?.let { it::class.simpleName } ?: "none"} " +
                        "generated=${reply.generatedText.length} delivered=${reply.deliveredText.length}"
                }
                listener.onAssistantReplySpoken(reply.generatedText, reply.deliveredText)
                listener.onConversationChanged(reply.conversation)
                reply.failure?.let { error ->
                    AppLog.w { "voice: assistant reply failed code=${error.code}" }
                    listener.onError(error)
                }
                setState(VoiceSessionState.LISTENING)
            }
        }
    }

    /** The outcome of one assistant attempt: a reply, or a thrown runtime failure. */
    private sealed interface AssistantAttempt {
        data class Replied(
            val reply: VoiceAssistantReply,
        ) : AssistantAttempt

        data class Threw(
            val error: VoiceAgentError,
        ) : AssistantAttempt
    }

    private suspend fun commitTranscript(
        turnId: TurnId,
        transcript: Transcript,
    ) {
        val base = synchronized(stateLock) { conversation } ?: return
        val userTurn = UserTurn(id = turnId, transcript = transcript, source = UserTurnSource.VOICE)
        val updated =
            base.copy(
                updatedAtEpochMillis = wallClock(),
                turns = base.turns + userTurn,
            )
        try {
            repository.saveTurn(updated, userTurn)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            fail(VoiceAgentError(ErrorCode.PERSISTENCE_FAILED, "the recognized transcript could not be saved"))
            return
        }
        synchronized(stateLock) { conversation = updated }
        listener.onUtteranceCommitted(turnId, transcript)
        listener.onConversationChanged(updated)
        setState(VoiceSessionState.LISTENING)
    }

    private fun rejectTurn() {
        AppLog.d { "voice: turn rejected (no usable transcript)" }
        listener.onNoSpeech()
        setState(VoiceSessionState.LISTENING)
    }

    private fun setState(state: VoiceSessionState) {
        if (stopped && state != VoiceSessionState.STOPPED) return
        _state.value = state
        listener.onSessionState(state)
    }

    private fun fail(error: VoiceAgentError) {
        AppLog.w { "voice: session error code=${error.code}" }
        listener.onError(error)
        setState(VoiceSessionState.FAILED)
    }

    private class ListeningTurn(
        val turnId: TurnId,
        val input: Channel<AudioFrame>,
        val startedAtOffsetMillis: Long,
    ) {
        /** True until the capture pump has seeded this turn with the pre-roll. */
        val preRollPending = AtomicBoolean(true)
        var job: Job? = null
        var provisional: Transcript? = null
        var final: Transcript? = null
        var failure: VoiceAgentError? = null
        var vadEndpointOffsetMillis: Long? = null
    }

    private companion object {
        /** Frames buffered for endpoint detection while STT finalizes. */
        const val DETECT_BUFFER = 256

        /** Frames queued for the recognizer. */
        const val STT_BUFFER = 128

        /**
         * Capture frames kept as a pre-roll (≈1.0 s at the 20 ms frame), so a turn
         * that starts at VAD onset still receives the audio captured just before
         * it and does not clip the user's first word(s).
         */
        const val DEFAULT_PRE_ROLL_FRAMES = 50

        /** Maximum wait after endpoint for the recognizer's final result. */
        const val STT_COMPLETION_TIMEOUT_MILLIS = 5_000L
    }
}

private fun Throwable.toVoiceAgentError(): VoiceAgentError =
    (this as? VoiceAgentException)?.error ?: VoiceAgentError(ErrorCode.UNKNOWN, retryable = false)
