package com.voicechat.agent.replay

import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * A manifest plus its raw PCM samples.
 *
 * Samples are stored as 16-bit signed PCM (see [PcmCodec]) so a committed
 * fixture is the exact byte sequence the capture boundary would deliver. The
 * class compares by content, which makes two replays of the same fixture
 * directly assertable.
 */
class PcmFixture(
    val manifest: FixtureManifest,
    val samples: ShortArray,
) {
    init {
        require(samples.size == manifest.sampleCount) {
            "sample count ${samples.size} does not match manifest ${manifest.sampleCount}"
        }
        require(PcmCodec.sha256Hex(samples) == manifest.pcmSha256) {
            "samples do not match the manifest pcmSha256"
        }
    }

    val format: AudioFormat get() = manifest.format

    /** Slices [samples] into fixed-size frames; the final frame may be shorter. */
    fun sliceFrames(frameSizeSamples: Int = manifest.frameSizeSamples): List<AudioFrame> {
        require(frameSizeSamples > 0) { "frameSizeSamples must be positive" }
        val frames = ArrayList<AudioFrame>((samples.size / frameSizeSamples) + 1)
        var start = 0
        while (start < samples.size) {
            val end = minOf(start + frameSizeSamples, samples.size)
            frames += AudioFrame(format, samples.copyOfRange(start, end), capturedAtNanos = 0L)
            start = end
        }
        return frames
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PcmFixture) return false
        return manifest == other.manifest && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * manifest.hashCode() + samples.contentHashCode()

    override fun toString(): String =
        "PcmFixture(id=${manifest.id}, sampleCount=${samples.size}, transformations=${manifest.transformations.size})"
}

/**
 * [AudioInput] that replays a fixture's frames instead of a microphone.
 *
 * It implements the same contract as live capture, so STT, VAD, and turn
 * detection run unchanged over replayed audio. Replay is byte-deterministic:
 * [AudioFrame.capturedAtNanos] is `0` for every frame (replayed fixtures carry
 * no monotonic capture clock), and the frame sequence depends only on the
 * fixture's samples and frame size.
 */
class ReplayAudioInput(
    private val fixture: PcmFixture,
    private val frameSizeSamples: Int = fixture.manifest.frameSizeSamples,
) : AudioInput {
    init {
        require(frameSizeSamples > 0) { "frameSizeSamples must be positive" }
    }

    override val format: AudioFormat = fixture.manifest.format

    /** Number of times [frames] has been collected. */
    var collectionCount: Int = 0
        private set

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun frames(): Flow<AudioFrame> =
        flow {
            collectionCount++
            fixture.sliceFrames(frameSizeSamples).forEach { frame -> emit(frame) }
        }

    override suspend fun close() {
        closed = true
    }
}
