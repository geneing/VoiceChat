package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioTest {
    @Test
    fun formatRequiresPositiveValues() {
        assertThrows(IllegalArgumentException::class.java) { AudioFormat(sampleRateHz = 0, channelCount = 1) }
        assertThrows(IllegalArgumentException::class.java) { AudioFormat(sampleRateHz = 16_000, channelCount = 0) }
    }

    @Test
    fun mono16kFormatMatchesTheRecognizerContract() {
        assertEquals(16_000, AudioFormat.MONO_16_KHZ.sampleRateHz)
        assertEquals(1, AudioFormat.MONO_16_KHZ.channelCount)
        assertTrue(AudioFormat.MONO_16_KHZ.isMono)
    }

    @Test
    fun framesCompareBySampleContent() {
        val format = AudioFormat.MONO_16_KHZ
        val a = AudioFrame(format, shortArrayOf(1, 2, 3), capturedAtNanos = 10L)
        val b = AudioFrame(format, shortArrayOf(1, 2, 3), capturedAtNanos = 10L)
        val differentSamples = AudioFrame(format, shortArrayOf(1, 2, 4), capturedAtNanos = 10L)

        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertNotEquals(a, differentSamples)
        assertEquals(3, a.sampleCount)
    }

    @Test
    fun toStringDoesNotRevealSampleValues() {
        val frame = AudioFrame(AudioFormat.MONO_16_KHZ, shortArrayOf(12_345, -6_789))

        val text = frame.toString()
        assertFalse(text.contains("12345"))
        assertFalse(text.contains("-6789"))
        assertTrue(text.contains("sampleCount=2"))
    }
}
