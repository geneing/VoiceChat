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
import com.voicechat.agent.stt.parakeet.EndOfUtteranceSignal
import com.voicechat.agent.vad.EndpointReason
import com.voicechat.agent.vad.TurnDetectionEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
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
 * STT-only voice session: microphone capture → VAD/onset → streaming STT →
 * bounded VAD/Smart Turn endpointing → transcript persistence.
 *
 * This coordinator deliberately has no language-model, provider, or TTS
 * dependency. A finalized transcript is saved as a user turn and sent to the
 * listener; no assistant turn is generated. TTS remains implemented elsewhere
 * but is not initialized or connected to this session.
 *
 * The semantic endpoint detector is evaluated only by [VoiceTurnDetector] after
 * a VAD-confirmed pause. Speech onset stays on the fast path and never waits for
 * Smart Turn inference.
 */
class VoiceSessionCoordinator(
    private val audioInput: AudioInput,
    private val repository: ConversationRepository,
    private val speechToText: SpeechToText,
    private val turnDetector: VoiceTurnDetector,
    private val endOfUtterance: EndOfUtteranceSignal? = null,
    private val listener: VoiceSessionListener = VoiceSessionListener.NONE,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val turnIdFactory: () -> TurnId = { TurnId("voice-${UUID.randomUUID()}") },
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val wallClock: () -> Long = System::currentTimeMillis,
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
    private var parakeetEouOwner: ListeningTurn? = null

    @Volatile
    private var detectorJob: Job? = null

    /**
     * Endpoint finalization is kept separate from detection so a recognizer that
     * is finishing one turn cannot block VAD from seeing the next turn.
     */
    @Volatile
    private var finalizationJob: Job? = null

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
            val eouMonitor =
                endOfUtterance?.let { signal ->
                    scope.launch(dispatcher, start = CoroutineStart.UNDISPATCHED) {
                        var lastObservation = signal.eouEvents.value
                        signal.eouEvents.collect { observation ->
                            if (
                                observation.sequence > lastObservation.sequence ||
                                observation.offsetMillis != lastObservation.offsetMillis
                            ) {
                                // StateFlow may conflate a reset followed immediately by
                                // an EOU event. A sequence gap on a non-empty observation
                                // means the current listening turn owns that EOU.
                                if (
                                    observation.offsetMillis != NO_EOU_OFFSET &&
                                    observation.sequence > lastObservation.sequence + 1
                                ) {
                                    parakeetEouOwner = activeListening
                                }
                                lastObservation = observation
                                onParakeetEouObservation(observation.offsetMillis)
                            }
                        }
                    }
                }
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
                // The endpoint policy may emit CAPTURE_ENDED as its input closes.
                // Allow that final STT completion its bounded window before teardown.
                finalizationJob?.let { job ->
                    if (withTimeoutOrNull(STT_COMPLETION_TIMEOUT_MILLIS) { job.join() } == null) {
                        AppLog.d { "voice: capture ended before the pending transcript finalized" }
                    }
                }
                finalizationJob?.cancel()
                finalizationJob = null
                eouMonitor?.cancel()
                parakeetEouOwner = null
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
        setState(VoiceSessionState.STOPPED)
    }

    private suspend fun pumpCapture(input: Channel<AudioFrame>) {
        try {
            audioInput.frames().collect { frame ->
                input.send(frame)
                activeListening?.let { turn ->
                    try {
                        turn.input.send(frame)
                    } catch (ignored: ClosedSendChannelException) {
                        // The endpoint arrived while this frame was in flight.
                    }
                }
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

    /** Closes one recognition input and lets the detector continue collecting. */
    private fun onEndpointed(event: TurnDetectionEvent.Endpointed) {
        val turn = activeListening
        if (turn == null) {
            if (event.reason == EndpointReason.EMPTY_NO_SPEECH) listener.onNoSpeech()
            return
        }
        turn.vadEndpointOffsetMillis = (event.atOffsetMillis - turn.startedAtOffsetMillis).coerceAtLeast(0L)
        publishEndpointTiming(turn)
        activeListening = null
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

    /** Associates each utterance-scoped EOU observation with its active STT turn. */
    private fun onParakeetEouObservation(offsetMillis: Long) {
        if (offsetMillis == NO_EOU_OFFSET) {
            parakeetEouOwner = activeListening
            return
        }
        val turn = parakeetEouOwner ?: return
        turn.eouOffsetMillis = offsetMillis
        publishEndpointTiming(turn)
    }

    private fun publishEndpointTiming(turn: ListeningTurn) {
        if (endOfUtterance == null) return
        listener.onParakeetEndpointTiming(
            turn.turnId,
            ParakeetEndpointTiming(
                eouOffsetMillis = turn.eouOffsetMillis,
                vadEndpointOffsetMillis = turn.vadEndpointOffsetMillis,
            ),
        )
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

            is SttEvent.Failed -> {
                AppLog.v { "voice: stt event turn=${turn.turnId.value} failed code=${event.error.code}" }
                turn.failure = event.error
            }
        }
    }

    /** Joins an EOU-committed segment to the next Parakeet segment for one VAD turn. */
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
                commitTranscript(turn.turnId, final)
            }

            else -> {
                rejectTurn()
            }
        }
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
        var job: Job? = null
        var provisional: Transcript? = null
        var final: Transcript? = null
        var failure: VoiceAgentError? = null
        var eouOffsetMillis: Long? = null
        var vadEndpointOffsetMillis: Long? = null
    }

    private companion object {
        /** Frames buffered for endpoint detection while STT finalizes. */
        const val DETECT_BUFFER = 256

        /** Frames queued for the recognizer. */
        const val STT_BUFFER = 128

        /** Maximum wait after endpoint for the recognizer's final result. */
        const val STT_COMPLETION_TIMEOUT_MILLIS = 5_000L

        /** Sentinel emitted when Parakeet starts a new utterance input. */
        const val NO_EOU_OFFSET = Long.MIN_VALUE
    }
}

private fun Throwable.toVoiceAgentError(): VoiceAgentError =
    (this as? VoiceAgentException)?.error ?: VoiceAgentError(ErrorCode.UNKNOWN, retryable = false)
