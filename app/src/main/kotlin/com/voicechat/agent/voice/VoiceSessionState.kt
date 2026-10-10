package com.voicechat.agent.voice

import com.voicechat.agent.domain.TurnId

/** Session-level state for the STT-only voice capture path. */
enum class VoiceSessionState {
    /** No session is active. */
    IDLE,

    /** Capture is running and the loop is waiting for or transcribing speech. */
    LISTENING,

    /** The user stopped the session. */
    STOPPED,

    /** A typed capture, recognition, or persistence error surfaced. */
    FAILED,
}

/** Legacy barge-in metrics retained for the evaluation schema; STT sessions do not emit them. */
enum class VoiceInterruptionRecovery {
    COMMITTED,
    NO_USABLE_SPEECH,
}

/** Historical barge-in timing record retained for evaluation compatibility. */
data class BargeInTiming(
    val interruptedTurnId: TurnId,
    val onsetAtNanos: Long,
    val stopIssuedAtNanos: Long,
    val captureResumedAtNanos: Long? = null,
    val settledAtNanos: Long? = null,
    val recovery: VoiceInterruptionRecovery? = null,
) {
    val onsetToStopNanos: Long get() = stopIssuedAtNanos - onsetAtNanos
    val onsetToCaptureResumedNanos: Long? get() = captureResumedAtNanos?.let { it - onsetAtNanos }
    val onsetToSettledNanos: Long? get() = settledAtNanos?.let { it - onsetAtNanos }
}
