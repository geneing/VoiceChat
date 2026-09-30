package com.voicechat.agent.voice

import com.voicechat.agent.domain.TurnId

/**
 * Session-level state of the M24 voice loop, for the dialog and diagnostics.
 *
 * It is deliberately coarser than the per-turn [com.voicechat.agent.domain.TurnPhase]:
 * a single capture session spans many logical turns, so a session state describes
 * what the loop is doing *right now* rather than one turn's lifecycle.
 */
enum class VoiceSessionState {
    /** No session is active. */
    IDLE,

    /** Capture is running and the loop is waiting for or transcribing speech. */
    LISTENING,

    /** A committed utterance is being sent to the provider. */
    WORKING,

    /** Assistant audio is playing (barge-in may cut it short). */
    SPEAKING,

    /** The session was stopped by the user or by capture end. */
    STOPPED,

    /** A typed error ended the current turn; the session is still recoverable. */
    FAILED,
}

/**
 * What happened to the new utterance after a barge-in cut assistant output short.
 *
 * A barge-in detection can turn out to be user speech (a legitimate new turn), a
 * short acknowledgement, or noise/echo that the fast VAD flagged but the
 * recognizer could not turn into a usable transcript. Recording the recovery
 * explicitly is how the app avoids both inventing a turn from noise and silently
 * pretending the interrupted reply was finished.
 */
enum class VoiceInterruptionRecovery {
    /** A usable transcript was recognized and committed as a new user turn. */
    COMMITTED,

    /**
     * The barge-in produced no usable speech (a false/noise interruption). No turn
     * is committed; the interrupted assistant turn keeps only what was delivered.
     */
    NO_USABLE_SPEECH,
}

/**
 * Stop/cancel timing recorded for one barge-in, in monotonic nanoseconds.
 *
 * All fields come from the M04 monotonic clock, never wall time, so durations are
 * robust to clock changes. The record is the evidence behind the
 * "responsive barge-in" requirement (R-0046): onset-to-stop is what the user
 * perceives, and onset-to-capture-resumed proves the loop keeps listening without
 * waiting for the cancellation to settle.
 */
data class BargeInTiming(
    /** The generation turn that was interrupted. */
    val interruptedTurnId: TurnId,
    /** When the fast onset/VAD path reported speech. */
    val onsetAtNanos: Long,
    /** When playback stop was issued (immediately, before any acknowledgement). */
    val stopIssuedAtNanos: Long,
    /** When the next capture/recognition started, or `null` if it never did. */
    val captureResumedAtNanos: Long? = null,
    /** When the interrupted provider/TTS work reached its terminal state, or `null`. */
    val settledAtNanos: Long? = null,
    /** How the new utterance resolved, once known. */
    val recovery: VoiceInterruptionRecovery? = null,
) {
    /** Onset-to-stop latency; the number the user perceives. */
    val onsetToStopNanos: Long get() = stopIssuedAtNanos - onsetAtNanos

    /** Onset-to-capture-resumed latency; proves capture did not wait for cancel. */
    val onsetToCaptureResumedNanos: Long? get() = captureResumedAtNanos?.let { it - onsetAtNanos }

    /** Onset-to-settled latency; the cancellation-acknowledgement bound. */
    val onsetToSettledNanos: Long? get() = settledAtNanos?.let { it - onsetAtNanos }
}
