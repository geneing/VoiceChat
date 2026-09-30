package com.voicechat.agent.vad

import com.voicechat.agent.domain.AudioFrame
import kotlin.math.sqrt

/**
 * Measured, privacy-safe features of one audio frame.
 *
 * These are the only quantities the measured-audio detector uses, so no raw
 * sample value ever leaves this type. Both values are normalized to `[0, 1]`:
 * [rms] against 16-bit full scale and [zeroCrossingRate] as crossings per
 * sample interval.
 */
data class AudioFrameFeatures(
    val rms: Float,
    val zeroCrossingRate: Float,
) {
    companion object {
        private const val FULL_SCALE = 32_768f

        /** Computes the features of a raw sample block without retaining it. */
        fun of(samples: ShortArray): AudioFrameFeatures {
            if (samples.isEmpty()) return AudioFrameFeatures(rms = 0f, zeroCrossingRate = 0f)

            var sumSquares = 0.0
            var crossings = 0
            var previousNegative = samples[0] < 0
            for (index in samples.indices) {
                val value = samples[index].toDouble()
                sumSquares += value * value
                if (index > 0) {
                    val negative = samples[index] < 0
                    if (negative != previousNegative) crossings++
                    previousNegative = negative
                }
            }
            val rms = (sqrt(sumSquares / samples.size) / FULL_SCALE).toFloat()
            val zeroCrossingRate = crossings.toFloat() / (samples.size - 1)
            return AudioFrameFeatures(rms = rms, zeroCrossingRate = zeroCrossingRate)
        }

        /** Computes the features of one frame. */
        fun of(frame: AudioFrame): AudioFrameFeatures = of(frame.samples)
    }
}
