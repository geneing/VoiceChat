package com.voicechat.agent.audio

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Accumulates privacy-safe level statistics over a capture session.
 *
 * Only derived magnitudes and counts are produced — never the samples
 * themselves — so the values are safe to put in a diagnostic event. All levels
 * are normalized to `[0, 1]` against 16-bit full scale.
 */
class CaptureLevelAccumulator(
    private val clippingThreshold: Int = DEFAULT_CLIPPING_THRESHOLD,
) {
    private var peakMagnitude: Int = 0
    private var sumSquares: Double = 0.0
    private var sampleCount: Long = 0L

    /** Samples at or near full scale seen so far. */
    var clippedSamples: Long = 0L
        private set

    /** Highest sample magnitude seen, normalized to `[0, 1]`. */
    val peakLevel: Float
        get() = peakMagnitude / FULL_SCALE.toFloat()

    /** RMS magnitude over every sample seen, normalized to `[0, 1]`. */
    val rmsLevel: Float
        get() = if (sampleCount == 0L) 0f else (sqrt(sumSquares / sampleCount) / FULL_SCALE).toFloat()

    /** Total samples observed. */
    val observedSamples: Long get() = sampleCount

    /** Folds one frame of samples into the running statistics. */
    fun add(samples: ShortArray) {
        for (sample in samples) {
            val magnitude = abs(sample.toInt())
            if (magnitude > peakMagnitude) peakMagnitude = magnitude
            if (magnitude >= clippingThreshold) clippedSamples++
            val value = sample.toDouble()
            sumSquares += value * value
            sampleCount++
        }
    }

    /** Clears the accumulator so it can be reused for a new session. */
    fun reset() {
        peakMagnitude = 0
        sumSquares = 0.0
        sampleCount = 0L
        clippedSamples = 0L
    }

    companion object {
        /** 16-bit full-scale magnitude. */
        const val FULL_SCALE: Int = 32_768

        /** ~0.999 of full scale; samples at or beyond this are treated as clipped. */
        const val DEFAULT_CLIPPING_THRESHOLD: Int = 32_735
    }
}
