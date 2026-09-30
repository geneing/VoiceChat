package com.voicechat.agent.vad

import com.voicechat.agent.audio.AudioRouteType
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.takeWhile
import kotlin.math.ceil

/** Why a candidate pause did not finalize the logical user turn. */
enum class EndpointHoldReason {
    /** No semantic detector is configured or it is unavailable; the VAD-only policy applies. */
    VAD_ONLY,

    /** A semantic detector said the thought is incomplete, so the turn stays open. */
    SEMANTIC_INCOMPLETE,
}

/** Why a logical user turn produced its single final endpoint. */
enum class EndpointReason {
    /** An optional semantic detector said the thought is complete. */
    SEMANTIC_COMPLETE,

    /** The bounded maximum-silence cap elapsed (VAD-only or after a semantic hold). */
    SILENCE_CAP,

    /** Capture ended while a turn was still open; the trailing turn is finalized. */
    CAPTURE_ENDED,

    /** Capture ended without any speech; an explicit empty-turn endpoint. */
    EMPTY_NO_SPEECH,
}

/** One observation from [BoundedTurnEndpointPolicy], on the audio timeline. */
sealed interface TurnDetectionEvent {
    /** Offset from capture start, in milliseconds. */
    val atOffsetMillis: Long

    /** Fast speech activity, independent of whether a turn is committed. */
    data class Activity(
        val activity: SpeechActivity,
        val reason: VadReason,
        override val atOffsetMillis: Long,
    ) : TurnDetectionEvent

    /** A candidate pause that did not commit the turn; the same turn stays open. */
    data class Held(
        val reason: EndpointHoldReason,
        override val atOffsetMillis: Long,
    ) : TurnDetectionEvent

    /** The single final endpoint of a logical user turn. */
    data class Endpointed(
        val reason: EndpointReason,
        override val atOffsetMillis: Long,
    ) : TurnDetectionEvent

    /** Detection could not continue (for example a non-mono frame). */
    data class Failed(
        val error: VoiceAgentError,
        override val atOffsetMillis: Long,
    ) : TurnDetectionEvent
}

/**
 * The M09 bounded VAD-only endpoint policy.
 *
 * It separates the two decisions the architecture requires:
 *
 * 1. **Activity** — onset, candidate pause, and resume come from the fast
 *    measured-audio state machine and are emitted unconditionally, so barge-in
 *    never waits on a completion model.
 * 2. **Completion** — a logical user turn finalizes exactly once: either an
 *    optional [TurnCompletionDetector] says `COMPLETE` at a candidate pause, or
 *    the configurable [VadConfig.maxSilenceMillis] cap elapses. When no detector
 *    is configured (or it reports `UNAVAILABLE`), the bounded cap alone ends the
 *    turn. The contract `docs/decisions.md` §2.3 requires this so a trailing
 *    turn can never hang open without Smart Turn.
 *
 * **One logical turn across a pause and resume.** A candidate pause emits
 * [TurnDetectionEvent.Held] and keeps the turn open; resumed speech emits
 * [SpeechActivity.SPEECH_RESUMED] and joins the same turn. Only after a pause is
 * *not* resumed does the cap produce a single [TurnDetectionEvent.Endpointed].
 * Once a turn is endpointed, later speech begins a new logical turn.
 *
 * **Empty/no-speech behavior.** If capture ends without any detected speech, the
 * policy emits exactly one [EndpointReason.EMPTY_NO_SPEECH] endpoint, so callers
 * can tell an empty capture from a real turn rather than treating silence as a
 * committed utterance.
 *
 * **M10 extension point.** Swapping in Smart Turn v3.2 needs no audio-path
 * change: pass its [TurnCompletionDetector] to [semanticDetector] and the policy
 * evaluates it once per candidate pause over the recent-audio window.
 *
 * The implementation is pure Kotlin and runs wherever the collecting coroutine
 * runs; callers collect it off the main thread like the capture flow itself.
 */
class BoundedTurnEndpointPolicy(
    private val config: VadConfig = VadConfig.default(),
    private val semanticDetector: TurnCompletionDetector? = null,
    sink: DiagnosticsSink = NoOpDiagnosticsSink,
    clock: MonotonicClock = SystemMonotonicClock,
    route: AudioRouteType? = null,
    traceId: TraceId? = null,
    turnId: TurnId? = null,
) {
    private val diagnostics = TurnDetectionDiagnostics(sink, clock, route, traceId, turnId)

    /** The configured thresholds, for tests and diagnostics. */
    val configuration: VadConfig get() = config

    /**
     * Consumes one capture session and emits its activity, holds, and the single
     * endpoint of each logical turn. The flow completes when [audio] completes.
     */
    fun observe(audio: Flow<AudioFrame>): Flow<TurnDetectionEvent> =
        flow {
            val machine = VadStateMachine(config)
            val window = RecentAudioWindow(config.semanticWindowMillis)
            var speechStarted = false
            var endpointedThisTurn = false
            var pausePending = false
            var silenceFrames = 0L
            var failure: VoiceAgentError? = null

            audio
                .takeWhile { frame ->
                    if (frame.format.isMono) {
                        true
                    } else {
                        failure =
                            VoiceAgentError(
                                ErrorCode.TURN_DETECTION_FAILED,
                                "endpoint detection requires mono audio",
                            )
                        false
                    }
                }.collect { frame ->
                    window.add(frame)
                    val transitions = machine.process(frame)
                    val rms = if (transitions.isEmpty()) 0f else AudioFrameFeatures.of(frame).rms

                    for (transition in transitions) {
                        diagnostics.speechActivity(transition.activity, transition.reason, rms, transition.atOffsetMillis)
                        emit(TurnDetectionEvent.Activity(transition.activity, transition.reason, transition.atOffsetMillis))

                        when (transition.activity) {
                            SpeechActivity.SPEECH_STARTED -> {
                                speechStarted = true
                                endpointedThisTurn = false
                                pausePending = false
                                silenceFrames = 0
                            }

                            SpeechActivity.CANDIDATE_PAUSE -> {
                                pausePending = true
                                silenceFrames = 0
                                when (evaluateSemantic(window)) {
                                    TurnCompletion.COMPLETE -> {
                                        endpointedThisTurn = true
                                        pausePending = false
                                        diagnostics.endpointed(EndpointReason.SEMANTIC_COMPLETE, transition.atOffsetMillis)
                                        emit(TurnDetectionEvent.Endpointed(EndpointReason.SEMANTIC_COMPLETE, transition.atOffsetMillis))
                                    }

                                    TurnCompletion.INCOMPLETE -> {
                                        diagnostics.held(EndpointHoldReason.SEMANTIC_INCOMPLETE, transition.atOffsetMillis)
                                        emit(TurnDetectionEvent.Held(EndpointHoldReason.SEMANTIC_INCOMPLETE, transition.atOffsetMillis))
                                    }

                                    TurnCompletion.UNAVAILABLE -> {
                                        diagnostics.held(EndpointHoldReason.VAD_ONLY, transition.atOffsetMillis)
                                        emit(TurnDetectionEvent.Held(EndpointHoldReason.VAD_ONLY, transition.atOffsetMillis))
                                    }
                                }
                            }

                            SpeechActivity.SPEECH_RESUMED -> {
                                pausePending = false
                                silenceFrames = 0
                                if (endpointedThisTurn) {
                                    // The previous turn already finalized, so resumed
                                    // speech begins a new logical turn.
                                    endpointedThisTurn = false
                                    speechStarted = true
                                }
                            }
                        }
                    }

                    if (pausePending) {
                        silenceFrames++
                        if (silenceFrames >= capFrames(frame)) {
                            pausePending = false
                            endpointedThisTurn = true
                            val offset = machine.currentOffsetMillis
                            diagnostics.endpointed(EndpointReason.SILENCE_CAP, offset)
                            emit(TurnDetectionEvent.Endpointed(EndpointReason.SILENCE_CAP, offset))
                        }
                    }
                }

            val failureEvent = failure
            when {
                failureEvent != null -> {
                    diagnostics.failed(failureEvent.code)
                    emit(TurnDetectionEvent.Failed(failureEvent, machine.currentOffsetMillis))
                }

                !endpointedThisTurn -> {
                    val reason = if (speechStarted) EndpointReason.CAPTURE_ENDED else EndpointReason.EMPTY_NO_SPEECH
                    val offset = machine.currentOffsetMillis
                    diagnostics.endpointed(reason, offset)
                    emit(TurnDetectionEvent.Endpointed(reason, offset))
                }
            }
        }

    /**
     * Evaluates the optional semantic detector once at a candidate pause.
     *
     * A missing detector, an empty window, or a detector failure all resolve to
     * [TurnCompletion.UNAVAILABLE] so the bounded VAD-only policy still ends the
     * turn; a failure is recorded and never crashes the audio path.
     */
    private suspend fun evaluateSemantic(window: RecentAudioWindow): TurnCompletion {
        val detector = semanticDetector ?: return TurnCompletion.UNAVAILABLE
        val frame = window.snapshot() ?: return TurnCompletion.UNAVAILABLE
        return try {
            detector.evaluate(frame)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: VoiceAgentException) {
            diagnostics.failed(failure.error.code)
            TurnCompletion.UNAVAILABLE
        } catch (failure: Exception) {
            diagnostics.failed(ErrorCode.TURN_DETECTION_FAILED)
            TurnCompletion.UNAVAILABLE
        }
    }

    /** Silence frames after a candidate pause before the cap finalizes the turn. */
    private fun capFrames(frame: AudioFrame): Long {
        val frameMillis = frame.sampleCount.toLong() * 1000L / frame.format.sampleRateHz
        if (frameMillis <= 0L) return Long.MAX_VALUE
        return ceil(config.maxSilenceMillis.toDouble() / frameMillis.toDouble()).toLong().coerceAtLeast(1L)
    }
}
