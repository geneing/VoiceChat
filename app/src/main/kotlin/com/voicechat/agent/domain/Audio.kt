package com.voicechat.agent.domain

/**
 * Format of a block of captured or synthetic PCM audio.
 *
 * The project's capture path is 16-bit signed, mono PCM at 16 kHz because that
 * is the format the selected on-device recognizer accepts for custom audio
 * (see `docs/decisions.md` §2.1). [AudioFormat.MONO_16_KHZ] is the default the
 * capture and replay boundaries should use.
 */
data class AudioFormat(
    val sampleRateHz: Int,
    val channelCount: Int,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(channelCount > 0) { "channelCount must be positive" }
    }

    val isMono: Boolean get() = channelCount == 1

    companion object {
        /** 16 kHz mono, the sample rate the STT and turn-detection contracts expect. */
        val MONO_16_KHZ: AudioFormat = AudioFormat(sampleRateHz = 16_000, channelCount = 1)
    }
}

/**
 * One bounded block of 16-bit signed PCM samples.
 *
 * This is the frame shape shared by live capture and the deterministic replay
 * harness, so no platform audio type (for example `android.media.AudioRecord`)
 * crosses the contract boundary. Equality and hashing compare sample content,
 * which makes frames safe to use in assertions.
 *
 * [capturedAtNanos] is a monotonic capture timestamp for latency accounting. It
 * is measured on a monotonic clock (never wall time) and is `0` when the source
 * does not provide one, such as replayed fixtures.
 */
class AudioFrame(
    val format: AudioFormat,
    val samples: ShortArray,
    val capturedAtNanos: Long = 0L,
) {
    val sampleCount: Int get() = samples.size

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AudioFrame) return false
        return format == other.format &&
            capturedAtNanos == other.capturedAtNanos &&
            samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int {
        var result = format.hashCode()
        result = 31 * result + capturedAtNanos.hashCode()
        result = 31 * result + samples.contentHashCode()
        return result
    }

    /**
     * Never includes sample values: audio content must not reach logs or
     * crash reports by default (see `docs/privacy-and-security.md`).
     */
    override fun toString(): String = "AudioFrame(format=$format, sampleCount=$sampleCount, capturedAtNanos=$capturedAtNanos)"
}
