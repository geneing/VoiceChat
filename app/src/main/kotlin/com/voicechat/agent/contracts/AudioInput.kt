package com.voicechat.agent.contracts

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.flow.Flow

/**
 * Source of captured PCM audio, replaceable by the deterministic replay harness.
 *
 * **Ownership and lifecycle.** The implementation owns the capture resource.
 * [frames] is a cold flow tied to one capture session: each collection starts
 * capture, and cancelling collection (or a collector failing) stops it and
 * releases the resource. The flow is single-collector; callers must not collect
 * it concurrently. [close] is idempotent and is called when the audio pipeline
 * is torn down. Implementations must run capture off the main thread and must
 * not retain raw audio beyond the emitted frames.
 *
 * The implementation may throw [com.voicechat.agent.domain.VoiceAgentException]
 * from [close]; capture problems after start surface as a failed [frames] flow.
 */
interface AudioInput {
    /** Format every frame from this source uses. */
    val format: AudioFormat

    /** Cold flow of bounded PCM frames for one capture session. */
    fun frames(): Flow<AudioFrame>

    /** Stops capture and releases resources. Idempotent. */
    suspend fun close()
}
