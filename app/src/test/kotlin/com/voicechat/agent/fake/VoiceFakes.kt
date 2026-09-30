package com.voicechat.agent.fake

import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.orchestration.TurnResult
import com.voicechat.agent.vad.EndpointReason
import com.voicechat.agent.vad.TurnDetectionEvent
import com.voicechat.agent.vad.VadReason
import com.voicechat.agent.voice.BargeInTiming
import com.voicechat.agent.voice.VoiceInterruptionRecovery
import com.voicechat.agent.voice.VoiceSessionListener
import com.voicechat.agent.voice.VoiceSessionState
import com.voicechat.agent.voice.VoiceTurnDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.launch

/**
 * Deterministic [VoiceTurnDetector] the test drives one event at a time (M24).
 *
 * The audio is consumed (and counted) but does not influence the events, so a
 * test controls exactly which onset/pause/endpoint occurs. This is the analogue
 * of `FakeVoiceActivityDetector` for the endpoint policy, and it lets a test
 * interrupt at a precise LLM/TTS point.
 */
class ManualVoiceTurnDetector : VoiceTurnDetector {
    private val events = Channel<TurnDetectionEvent>(Channel.UNLIMITED)

    /** Number of audio frames observed across the session. */
    var observedFrameCount: Int = 0
        private set

    override fun detect(audio: Flow<AudioFrame>): Flow<TurnDetectionEvent> =
        flow {
            coroutineScope {
                launch { audio.collect { observedFrameCount++ } }
                for (event in events) emit(event)
            }
        }

    /** Emits one detection event; suspends only until it is queued. */
    suspend fun send(event: TurnDetectionEvent) {
        events.send(event)
    }

    /** Emits a speech-activity transition. */
    suspend fun speech(
        activity: SpeechActivity,
        offsetMillis: Long = 0L,
    ) = send(TurnDetectionEvent.Activity(activity, VadReason.ONSET_RMS, offsetMillis))

    /** Emits a single logical-turn endpoint. */
    suspend fun endpoint(
        reason: EndpointReason,
        offsetMillis: Long = 0L,
    ) = send(TurnDetectionEvent.Endpointed(reason, offsetMillis))

    /** Ends the detection flow. */
    fun finish() {
        events.close()
    }
}

/**
 * Deterministic [SpeechToText] that emits scripted interim revisions immediately,
 * then a final (or failure) once the caller closes the audio input.
 *
 * Emitting the final only after input end mirrors the real adapter, so the
 * coordinator's "close the turn input, then await the final" path is exercised
 * exactly as in production.
 */
class ScriptedSpeechToText(
    override val engineId: EngineId = EngineId("scripted-stt"),
    private val interims: List<String> = emptyList(),
    /** Final text for the next session; change it between turns to script per-turn results. */
    var finalText: String? = null,
    var finalConfidence: Float? = null,
    /** When set, the next session fails instead of finalizing. */
    var failure: VoiceAgentError? = null,
) : SpeechToText {
    /** Audio frames observed across all sessions. */
    var observedFrameCount: Int = 0
        private set

    /** Number of recognition sessions started. */
    var sessionCount: Int = 0
        private set

    /** Collections cancelled by their consumer. */
    var cancellationCount: Int = 0
        private set

    /** True once [close] was called. */
    var closed: Boolean = false
        private set

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            sessionCount++
            interims.forEachIndexed { index, text ->
                emit(SttEvent.Result(Transcript.interim(text, TranscriptRevision(index))))
            }
            audio.collect { observedFrameCount++ }
            val error = failure
            val final = finalText
            when {
                error != null -> {
                    emit(SttEvent.Failed(error))
                }

                final != null -> {
                    emit(
                        SttEvent.Result(
                            Transcript(
                                text = final,
                                revision = TranscriptRevision(interims.size),
                                isFinal = true,
                                confidence = finalConfidence,
                            ),
                        ),
                    )
                }
            }
        }.onCompletion { cause ->
            if (cause is CancellationException) cancellationCount++
        }

    override suspend fun close() {
        closed = true
    }
}

/**
 * Deterministic [SpeechToText] the test drives one event at a time.
 *
 * It consumes audio concurrently and emits whatever the test sends until a final
 * result closes the channel. This lets a test deliver out-of-order revisions so
 * the coordinator's stale-interim rule can be asserted.
 */
class ManualSpeechToText(
    override val engineId: EngineId = EngineId("manual-stt"),
) : SpeechToText {
    private val events = Channel<SttEvent>(Channel.UNLIMITED)

    /** Audio frames observed across all sessions. */
    var observedFrameCount: Int = 0
        private set

    /** Number of recognition sessions started. */
    var sessionCount: Int = 0
        private set

    /** True once [close] was called. */
    var closed: Boolean = false
        private set

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            sessionCount++
            coroutineScope {
                launch { audio.collect { observedFrameCount++ } }
                for (event in events) emit(event)
            }
        }

    /** Sends one raw result or failure. */
    suspend fun send(event: SttEvent) {
        events.send(event)
    }

    /** Sends an interim hypothesis with an explicit revision. */
    suspend fun interim(
        text: String,
        revision: Int,
    ) = send(SttEvent.Result(Transcript.interim(text, TranscriptRevision(revision))))

    /** Sends the final hypothesis and ends the session flow. */
    suspend fun final(
        text: String,
        revision: Int,
    ) {
        send(SttEvent.Result(Transcript.final(text, TranscriptRevision(revision))))
        events.close()
    }

    override suspend fun close() {
        closed = true
        events.close()
    }
}

/**
 * Deterministic [SpeechToText] that emits an optional early interim, then a late
 * interim **after its input has closed** (mirroring a real engine that finalizes
 * on stop), then a final result.
 *
 * This is how the coordinator's "no stale events applied" rule is asserted: a
 * late interim that arrives while the turn is finalizing must never reach the
 * dialog, only the committed final text.
 */
class LateInterimSpeechToText(
    override val engineId: EngineId = EngineId("late-interim-stt"),
    private val earlyInterim: String? = null,
    private val lateInterim: String,
    private val finalText: String,
) : SpeechToText {
    /** Audio frames observed across all sessions. */
    var observedFrameCount: Int = 0
        private set

    /** Number of recognition sessions started. */
    var sessionCount: Int = 0
        private set

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            sessionCount++
            earlyInterim?.let { emit(SttEvent.Result(Transcript.interim(it, TranscriptRevision(0)))) }
            audio.collect { observedFrameCount++ }
            // Emitted after input end, once the coordinator has already cleared the
            // active listening turn: the late guess is superseded and must be dropped.
            emit(SttEvent.Result(Transcript.interim(lateInterim, TranscriptRevision(1))))
            emit(SttEvent.Result(Transcript.final(finalText, TranscriptRevision(2))))
        }

    override suspend fun close() = Unit
}

/** [AudioInput] that emits a delegate's frames and then stays open until cancelled. */
class KeepAliveAudioInput(
    private val delegate: AudioInput,
) : AudioInput {
    override val format = delegate.format

    override fun frames(): Flow<AudioFrame> =
        flow {
            emitAll(delegate.frames())
            awaitCancellation()
        }

    override suspend fun close() {
        delegate.close()
    }
}

/** [VoiceSessionListener] that records every callback for assertions. */
class RecordingVoiceSessionListener : VoiceSessionListener {
    val sessionStates: MutableList<VoiceSessionState> = mutableListOf()
    val provisional: MutableList<Pair<TurnId, String>> = mutableListOf()
    val committed: MutableList<Pair<TurnId, Transcript>> = mutableListOf()
    val assistantText: MutableList<Pair<TurnId, String>> = mutableListOf()
    val conversations: MutableList<Conversation> = mutableListOf()
    val finished: MutableList<TurnResult> = mutableListOf()
    val bargeIns: MutableList<BargeInTiming> = mutableListOf()
    val recoveries: MutableList<VoiceInterruptionRecovery> = mutableListOf()
    val errors: MutableList<VoiceAgentError> = mutableListOf()

    /** Number of logical turns that started listening. */
    var listeningCount: Int = 0
        private set

    /** Number of turns rejected with no usable speech. */
    var noSpeechCount: Int = 0
        private set

    override fun onSessionState(state: VoiceSessionState) {
        sessionStates += state
    }

    override fun onListeningStarted(turnId: TurnId) {
        listeningCount++
    }

    override fun onProvisionalTranscript(
        turnId: TurnId,
        text: String,
    ) {
        provisional += turnId to text
    }

    override fun onUtteranceCommitted(
        turnId: TurnId,
        transcript: Transcript,
    ) {
        committed += turnId to transcript
    }

    override fun onAssistantText(
        turnId: TurnId,
        text: String,
    ) {
        assistantText += turnId to text
    }

    override fun onConversationChanged(conversation: Conversation) {
        conversations += conversation
    }

    override fun onTurnFinished(result: TurnResult) {
        finished += result
    }

    override fun onBargeIn(timing: BargeInTiming) {
        bargeIns += timing
    }

    override fun onInterruptionRecovered(recovery: VoiceInterruptionRecovery) {
        recoveries += recovery
    }

    override fun onNoSpeech() {
        noSpeechCount++
    }

    override fun onError(error: VoiceAgentError) {
        errors += error
    }
}
