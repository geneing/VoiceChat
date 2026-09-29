package com.voicechat.agent.fake

import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.EngineId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/**
 * Deterministic [SpeechToText] that emits a scripted sequence of results.
 *
 * It consumes (and counts) the input audio, then replays [script] in order.
 * [cancellationCount] increments when collection is cancelled, so tests can
 * assert that recognition stops without emitting a final result.
 */
class FakeSpeechToText(
    override val engineId: EngineId = EngineId("fake-stt"),
    private val script: List<SttEvent> = emptyList(),
) : SpeechToText {
    /** Number of audio frames observed across all sessions. */
    var observedFrameCount: Int = 0
        private set

    /** Number of collections cancelled by their consumer. */
    var cancellationCount: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            audio.collect { observedFrameCount++ }
            script.forEach { emit(it) }
        }.onCompletion { cause ->
            if (cause is CancellationException) cancellationCount++
        }

    override suspend fun close() {
        closed = true
    }
}
