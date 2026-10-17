package com.voicechat.agent.fake

import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Deterministic [AudioInput] for contract tests and the replay harness.
 *
 * Emits [frames] in order, then optionally fails with [failure], so tests can
 * exercise the normal and error paths without a microphone.
 */
class FakeAudioInput(
    override val format: AudioFormat = AudioFormat.MONO_16_KHZ,
    private val frames: List<AudioFrame> = emptyList(),
    private val failure: VoiceAgentError? = null,
) : AudioInput {
    /** Number of times [frames] has been collected. */
    var collectionCount: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun frames(): Flow<AudioFrame> =
        flow {
            collectionCount++
            frames.forEach { emit(it) }
            failure?.let { throw VoiceAgentException(it) }
        }

    override suspend fun close() {
        closed = true
    }
}

/**
 * Deterministic [AudioInput] the test drives one frame at a time.
 *
 * Unlike [FakeAudioInput] (a fixed list emitted at once), this lets a test emit
 * frames **before** and **after** a detection event, so the coordinator's
 * pre-roll seeding can be asserted: frames captured before onset must reach the
 * recognizer once a turn starts.
 */
class ManualAudioInput(
    override val format: AudioFormat = AudioFormat.MONO_16_KHZ,
) : AudioInput {
    private val frames = Channel<AudioFrame>(Channel.UNLIMITED)

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun frames(): Flow<AudioFrame> = frames.receiveAsFlow()

    /** Queues one frame for the capture pump. */
    suspend fun emit(frame: AudioFrame) {
        frames.send(frame)
    }

    /** Ends the capture stream. */
    fun finish() {
        frames.close()
    }

    override suspend fun close() {
        closed = true
        frames.close()
    }
}
