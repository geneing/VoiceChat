package com.voicechat.assistant.realtime.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class TimeStretcherTest {

    private val sampleRate = 24_000

    private fun sinePcm(frequency: Double, seconds: Double, amplitude: Int = 8_000): ByteArray {
        val frames = (sampleRate * seconds).toInt()
        val bytes = ByteArray(frames * 2)
        for (index in 0 until frames) {
            val value = (amplitude * sin(2.0 * PI * frequency * index / sampleRate)).toInt()
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun stretch(pcm: ByteArray, speed: Float, chunkBytes: Int = 960): ByteArray {
        val stretcher = TimeStretcher(sampleRate, speed)
        val out = java.io.ByteArrayOutputStream()
        var offset = 0
        while (offset < pcm.size) {
            val end = minOf(offset + chunkBytes, pcm.size)
            out.write(stretcher.process(pcm.copyOfRange(offset, end)))
            offset = end
        }
        return out.toByteArray()
    }

    private fun dominantFrequency(pcm: ByteArray): Double {
        var crossings = 0
        var previous = 0
        var index = 0
        while (index + 1 < pcm.size) {
            val value = ((pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)).toShort().toInt()
            if (previous < 0 && value >= 0) crossings++
            if (value != 0) previous = value
            index += 2
        }
        val seconds = (pcm.size / 2).toDouble() / sampleRate
        return crossings / seconds
    }

    @Test
    fun `faster playback shortens the stream`() {
        val input = sinePcm(440.0, 1.0)
        val output = stretch(input, 1.25f)
        val ratio = (output.size / 2).toDouble() / (input.size / 2)
        assertEquals(0.8, ratio, 0.06)
    }

    @Test
    fun `slower playback lengthens the stream`() {
        val input = sinePcm(440.0, 1.0)
        val output = stretch(input, 0.75f)
        val ratio = (output.size / 2).toDouble() / (input.size / 2)
        assertEquals(1.33, ratio, 0.1)
    }

    @Test
    fun `pitch is preserved`() {
        val input = sinePcm(440.0, 1.0)
        val output = stretch(input, 1.25f)
        val frequency = dominantFrequency(output)
        assertTrue("frequency was $frequency", abs(frequency - 440.0) < 45.0)
    }

    @Test
    fun `normal speed is a passthrough`() {
        val input = sinePcm(440.0, 0.2)
        val stretcher = TimeStretcher(sampleRate, 1f)
        assertTrue(stretcher.passthrough)
        assertTrue(stretcher.process(input).contentEquals(input))
    }

    @Test
    fun `reset drops buffered audio`() {
        val stretcher = TimeStretcher(sampleRate, 1.25f)
        val input = sinePcm(440.0, 0.5)
        stretcher.process(input)
        stretcher.reset()
        assertTrue(stretcher.process(input).isNotEmpty())
    }
}
