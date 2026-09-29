package com.voicechat.agent.fake

import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.contracts.VadEvent
import com.voicechat.agent.contracts.VoiceActivityDetector
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Deterministic [VoiceActivityDetector] that reports a scripted sequence.
 *
 * Audio is consumed (and counted) but does not influence the events, so tests
 * control exactly which onset/pause/resume transitions occur.
 */
class FakeVoiceActivityDetector(
    private val events: List<VadEvent> = emptyList(),
) : VoiceActivityDetector {
    /** Number of audio frames observed across all sessions. */
    var observedFrameCount: Int = 0
        private set

    override fun observe(audio: Flow<AudioFrame>): Flow<VadEvent> =
        flow {
            audio.collect { observedFrameCount++ }
            events.forEach { emit(it) }
        }
}

/**
 * Deterministic [TurnCompletionDetector] that returns a fixed decision.
 *
 * [evaluationCount] proves the detector is evaluated once per candidate pause,
 * not once per audio frame. Set [failure] to exercise the unavailable path.
 */
class FakeTurnCompletionDetector(
    var decision: TurnCompletion = TurnCompletion.COMPLETE,
    var failure: VoiceAgentError? = null,
) : TurnCompletionDetector {
    /** Number of [evaluate] calls. */
    var evaluationCount: Int = 0
        private set

    /** Windows passed to [evaluate], for assertions on what was classified. */
    val evaluatedWindows: MutableList<AudioFrame> = mutableListOf()

    override suspend fun evaluate(window: AudioFrame): TurnCompletion {
        evaluationCount++
        evaluatedWindows += window
        failure?.let { throw VoiceAgentException(it) }
        return decision
    }

    override suspend fun close() = Unit
}
