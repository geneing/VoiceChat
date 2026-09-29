package com.voicechat.agent.audio

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.VoiceAgentException

/**
 * Configuration for one microphone capture path.
 *
 * The default is 16 kHz mono 16-bit PCM in 20 ms frames, which is exactly the
 * frame contract the replay harness produces (`AudioFormat.MONO_16_KHZ`), so a
 * live capture and a fixture are interchangeable downstream.
 */
data class MicrophoneCaptureConfig(
    val format: AudioFormat = AudioFormat.MONO_16_KHZ,
    /** Samples per emitted frame. 320 samples = 20 ms at 16 kHz. */
    val frameSizeSamples: Int = DEFAULT_FRAME_SIZE_SAMPLES,
    /** Frames the capture pipeline may queue before it starts dropping. */
    val bufferFrameCapacity: Int = DEFAULT_BUFFER_FRAMES,
    /** Emit a level/clip/drop diagnostic every this many frames. */
    val diagnosticIntervalFrames: Int = DEFAULT_DIAGNOSTIC_INTERVAL_FRAMES,
    /** Re-check the permission every this many frames so revocation is caught. */
    val permissionRecheckIntervalFrames: Int = DEFAULT_PERMISSION_RECHECK_FRAMES,
) {
    init {
        require(format.sampleRateHz > 0 && format.channelCount > 0) { "format must be positive" }
        require(frameSizeSamples > 0) { "frameSizeSamples must be positive" }
        require(bufferFrameCapacity > 0) { "bufferFrameCapacity must be positive" }
        require(diagnosticIntervalFrames > 0) { "diagnosticIntervalFrames must be positive" }
        require(permissionRecheckIntervalFrames > 0) { "permissionRecheckIntervalFrames must be positive" }
    }

    companion object {
        const val DEFAULT_FRAME_SIZE_SAMPLES: Int = 320

        /**
         * 64 frames ≈ 1.28 s at 20 ms/frame. A bounded queue keeps capture
         * latency predictable: rather than let the hardware buffer overflow
         * invisibly, a slow consumer makes the pipeline drop frames that are
         * counted in diagnostics.
         */
        const val DEFAULT_BUFFER_FRAMES: Int = 64
        const val DEFAULT_DIAGNOSTIC_INTERVAL_FRAMES: Int = 25
        const val DEFAULT_PERMISSION_RECHECK_FRAMES: Int = 50
    }
}

/**
 * A blocking, platform-neutral reader over one capture stream.
 *
 * Implementations wrap `AudioRecord` (see [AndroidPcmRecorderFactory]); tests
 * supply a fake. [read] is intentionally blocking and returns raw platform
 * error codes so the capture loop can translate them into typed
 * [com.voicechat.agent.domain.VoiceAgentError]s in one place.
 *
 * **Ownership.** One engine backs exactly one capture session. The creator must
 * [release] it; [stop] may be called from another thread to unblock an
 * in-flight [read] during shutdown.
 */
interface PcmRecorderEngine {
    /** Format every sample read from this engine uses. */
    val format: AudioFormat

    /** Allocates the stream and starts reading. Throws [VoiceAgentException] on failure. */
    fun start()

    /**
     * Reads up to [size] samples into [buffer] at [offset], blocking until some
     * are available. Returns the number of samples read (0 for a transient empty
     * read) or a negative platform error code.
     */
    fun read(
        buffer: ShortArray,
        offset: Int,
        size: Int,
    ): Int

    /** Stops the stream. Idempotent; safe to call from another thread. */
    fun stop()

    /** Releases the platform resource. Idempotent. */
    fun release()
}

/**
 * Creates an opened-but-not-started [PcmRecorderEngine].
 *
 * Permission is checked by the capture loop *before* [create], so an
 * implementation may assume it is allowed to open the device. It throws
 * [VoiceAgentException] with a typed error when 16 kHz mono PCM is unavailable.
 */
fun interface PcmRecorderFactory {
    fun create(): PcmRecorderEngine
}
