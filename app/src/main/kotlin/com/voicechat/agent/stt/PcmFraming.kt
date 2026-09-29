package com.voicechat.agent.stt

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame

/**
 * Converts captured PCM frames to the byte stream the selected recognizer
 * accepts for custom audio.
 *
 * ML Kit GenAI Speech Recognition's `AudioSource.fromPfd` requires raw,
 * headerless, little-endian 16-bit PCM, mono, at 16 kHz (`docs/decisions.md`
 * §2.1). The app's capture boundary already produces that format
 * ([AudioFormat.MONO_16_KHZ]), so this is a format-checked re-encoding, not a
 * resample. Any other input format is rejected loudly rather than silently
 * mis-encoded; resampling belongs to the capture boundary (M07), not here.
 */
object PcmFraming {
    /** Required sample format for the recognizer's custom-audio path. */
    val REQUIRED_FORMAT: AudioFormat = AudioFormat.MONO_16_KHZ

    /**
     * Encodes [frame] as little-endian 16-bit PCM.
     *
     * @throws IllegalArgumentException if the frame is not mono 16 kHz.
     */
    fun toLittleEndianPcm(frame: AudioFrame): ByteArray {
        require(frame.format == REQUIRED_FORMAT) {
            "recognizer requires $REQUIRED_FORMAT but frame is ${frame.format}"
        }
        return toLittleEndianPcm(frame.samples)
    }

    /** Encodes 16-bit signed [samples] as little-endian bytes. */
    fun toLittleEndianPcm(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * BYTES_PER_SAMPLE)
        var index = 0
        for (sample in samples) {
            val value = sample.toInt()
            bytes[index++] = (value and 0xFF).toByte()
            bytes[index++] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private const val BYTES_PER_SAMPLE = 2
}
