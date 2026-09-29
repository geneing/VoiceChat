package com.voicechat.agent.stt

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The recognizer's custom-audio path requires raw little-endian 16-bit mono
 * PCM at 16 kHz. These tests pin the byte encoding and the format guard, so the
 * audio path used on a Pixel 10 run is exactly what the adapter documents.
 */
class PcmFramingTest {
    @Test
    fun samplesAreEncodedLittleEndian16Bit() {
        // 0x0102 -> 02 01; -1 (0xFFFF) -> FF FF; 0 -> 00 00; -32768 (0x8000) -> 00 80.
        val samples = shortArrayOf(0x0102, -1, 0, Short.MIN_VALUE)

        val bytes = PcmFraming.toLittleEndianPcm(samples)

        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x80.toByte()),
            bytes,
        )
    }

    @Test
    fun encodingUsesTwoBytesPerSample() {
        val samples = ShortArray(320) { it.toShort() }

        assertEquals(640, PcmFraming.toLittleEndianPcm(samples).size)
    }

    @Test
    fun anEmptyFrameEncodesToNoBytes() {
        assertEquals(0, PcmFraming.toLittleEndianPcm(AudioFrame(AudioFormat.MONO_16_KHZ, ShortArray(0))).size)
    }

    @Test
    fun aFrameInTheRequiredFormatIsAccepted() {
        val frame = AudioFrame(AudioFormat.MONO_16_KHZ, shortArrayOf(1, 2, 3))

        assertEquals(6, PcmFraming.toLittleEndianPcm(frame).size)
        assertEquals(AudioFormat.MONO_16_KHZ, PcmFraming.REQUIRED_FORMAT)
    }

    @Test
    fun aFrameInTheWrongFormatIsRejectedLoudly() {
        val wrongFormat = AudioFrame(AudioFormat(sampleRateHz = 48_000, channelCount = 2), shortArrayOf(1))

        val thrown =
            assertThrows(IllegalArgumentException::class.java) {
                PcmFraming.toLittleEndianPcm(wrongFormat)
            }

        assert(thrown.message!!.contains("sampleRateHz=16000"))
    }
}
