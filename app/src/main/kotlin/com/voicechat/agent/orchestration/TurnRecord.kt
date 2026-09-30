package com.voicechat.agent.orchestration

import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentError

/**
 * A read-only snapshot of one generation turn, produced by [TurnStateMachine].
 *
 * The snapshot is immutable and platform-free, so a JVM test can assert the
 * exact lifecycle without a UI, a provider, or a device. It keeps *generated*,
 * *queued*, and *delivered* text separate so the persisted turn can only ever
 * claim what was actually produced and spoken.
 */
data class TurnRecord(
    val turnId: TurnId,
    val source: UserTurnSource,
    val phase: TurnPhase,
    val transcript: Transcript,
    val generationState: GenerationState,
    val deliveryState: DeliveryState,
    val generatedText: String,
    val queuedText: String,
    val deliveredText: String,
    val outcome: TurnOutcome?,
    val failure: VoiceAgentError?,
    val queuedUtteranceCount: Int,
    val startedUtteranceCount: Int,
    val deliveredUtteranceCount: Int,
) {
    /** True once the turn reached a terminal phase. */
    val isTerminal: Boolean get() = phase.isTerminal

    /** True when generation produced text playback has not delivered. */
    val hasUndeliveredText: Boolean get() = deliveredText.length < generatedText.length
}

/**
 * A read-only snapshot of a turn that is still being transcribed.
 *
 * Revision handling lives here: [provisional] is replaced only by a strictly
 * newer [Transcript.revision], so a stale interim guess can never overwrite a
 * newer one.
 */
data class ListeningRecord(
    val turnId: TurnId,
    val source: UserTurnSource,
    val provisional: Transcript?,
    val rejection: TranscriptRejection?,
) {
    /** True once the listen ended without a committed turn. */
    val isRejected: Boolean get() = rejection != null
}
