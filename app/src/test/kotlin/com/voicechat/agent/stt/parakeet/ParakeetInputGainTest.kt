package com.voicechat.agent.stt.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for [ParakeetInputGain], the bounded peak normalizer that keeps quiet
 * microphone audio in the range the pinned Parakeet-EOU model was evaluated at.
 */
class ParakeetInputGainTest {
    // Use a 1.0 target so gains read as simple ratios.
    private fun normalizer(
        target: Float = 1f,
        maxGain: Float = 16f,
        minGain: Float = 0.25f,
        floor: Float = 0.012f,
    ) = ParakeetInputGain(targetPeak = target, maxGain = maxGain, minGain = minGain, signalFloor = floor)

    @Test
    fun aQuietChunkIsBoostedTowardTheTarget() {
        val gain = normalizer()
        val chunk = floatArrayOf(0.1f, -0.05f, 0.02f)

        assertEquals(10f, gain.gainFor(chunk), 1e-4f)
        gain.apply(chunk)
        assertEquals(1f, chunk.maxOf { kotlin.math.abs(it) }, 1e-4f)
    }

    @Test
    fun aLoudChunkIsAttenuatedInsteadOfClipping() {
        val gain = normalizer()
        val chunk = floatArrayOf(1.5f, -1.2f)

        assertEquals(1f / 1.5f, gain.gainFor(chunk), 1e-4f)
        gain.apply(chunk)
        assertEquals(1f, chunk.maxOf { kotlin.math.abs(it) }, 1e-4f)
    }

    @Test
    fun aSilentChunkIsLeftUnchanged() {
        val gain = normalizer()
        val chunk = floatArrayOf(0f, 0.001f, -0.0005f)

        assertEquals(1f, gain.gainFor(chunk), 0f)
        val before = chunk.copyOf()
        gain.apply(chunk)
        assertTrue(chunk.contentEquals(before))
    }

    @Test
    fun boostIsClampedToTheConfiguredMaximum() {
        val gain = normalizer(maxGain = 8f)
        val chunk = floatArrayOf(0.02f)

        assertEquals(8f, gain.gainFor(chunk), 1e-4f)
    }

    @Test
    fun passthroughLeavesEveryChunkUnchanged() {
        val loud = floatArrayOf(1f, -1f)
        val quiet = floatArrayOf(0.02f, -0.01f)

        assertEquals(1f, ParakeetInputGain.PASSTHROUGH.gainFor(loud), 0f)
        assertEquals(1f, ParakeetInputGain.PASSTHROUGH.gainFor(quiet), 0f)
        val before = quiet.copyOf()
        ParakeetInputGain.PASSTHROUGH.apply(quiet)
        assertTrue(quiet.contentEquals(before))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonPositiveTargetIsRejected() {
        ParakeetInputGain(targetPeak = 0f)
    }
}
