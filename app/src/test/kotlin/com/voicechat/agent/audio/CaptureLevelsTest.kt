package com.voicechat.agent.audio

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

/**
 * Pure-JVM tests for the privacy-safe level statistics used in capture
 * diagnostics. No sample values leave the accumulator.
 */
class CaptureLevelsTest {
    @Test
    fun peakRmsAndClippingAreComputedAcrossFrames() {
        val accumulator = CaptureLevelAccumulator()

        accumulator.add(shortArrayOf(0, 16_384, -16_384))

        assertEquals(0.5f, accumulator.peakLevel, EPSILON)
        val expectedRms = (sqrt(2.0 * 16_384.0 * 16_384.0 / 3.0) / 32_768.0).toFloat()
        assertEquals(expectedRms, accumulator.rmsLevel, EPSILON)
        assertEquals(0L, accumulator.clippedSamples)
        assertEquals(3L, accumulator.observedSamples)
    }

    @Test
    fun fullScaleSamplesAreCountedAsClipped() {
        val accumulator = CaptureLevelAccumulator()

        accumulator.add(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0))

        assertEquals(2L, accumulator.clippedSamples)
        assertEquals(1.0f, accumulator.peakLevel, EPSILON)
    }

    @Test
    fun anEmptyAccumulatorIsSilent() {
        val accumulator = CaptureLevelAccumulator()

        assertEquals(0f, accumulator.peakLevel, EPSILON)
        assertEquals(0f, accumulator.rmsLevel, EPSILON)
        assertEquals(0L, accumulator.clippedSamples)
    }

    @Test
    fun resetClearsEveryStatistic() {
        val accumulator = CaptureLevelAccumulator()
        accumulator.add(shortArrayOf(Short.MAX_VALUE, 1_000))

        accumulator.reset()

        assertEquals(0f, accumulator.peakLevel, EPSILON)
        assertEquals(0f, accumulator.rmsLevel, EPSILON)
        assertEquals(0L, accumulator.clippedSamples)
        assertEquals(0L, accumulator.observedSamples)
    }

    private companion object {
        const val EPSILON = 1e-6f
    }
}
