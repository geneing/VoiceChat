package com.voicechat.agent.replay

import java.lang.StrictMath

/**
 * Small deterministic DSP helpers shared by the fixture generator and the
 * audio transformations.
 *
 * Every transcendental call goes through [StrictMath] (fdlibm) rather than
 * `kotlin.math`/`Math`, whose intrinsics may differ in the last bit between
 * JVMs and platforms. Bit-identical output across machines is what makes a
 * frozen fixture byte-stable and reproducible.
 */
internal object Dsp {
    /** Rounds to the nearest [Short], clamping instead of wrapping on overflow. */
    fun clampToShort(value: Double): Short =
        when {
            value >= Short.MAX_VALUE -> Short.MAX_VALUE
            value <= Short.MIN_VALUE -> Short.MIN_VALUE
            else -> StrictMath.round(value).toInt().toShort()
        }

    fun rms(samples: ShortArray): Double = rms(samples.map { it.toFloat() }.toFloatArray())

    fun rms(samples: FloatArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            val value = sample.toDouble()
            sum += value * value
        }
        return StrictMath.sqrt(sum / samples.size)
    }

    fun toFloats(samples: ShortArray): FloatArray = FloatArray(samples.size) { samples[it].toFloat() }

    /** One-pole low-pass filter; [cutoffHz] must be well below the Nyquist rate. */
    fun lowPass(
        input: FloatArray,
        cutoffHz: Double,
        sampleRateHz: Int,
    ): FloatArray {
        val alpha = 1.0 - StrictMath.exp(-2.0 * StrictMath.PI * cutoffHz / sampleRateHz)
        val output = FloatArray(input.size)
        var previous = 0.0
        input.forEachIndexed { index, value ->
            previous += alpha * (value - previous)
            output[index] = previous.toFloat()
        }
        return output
    }

    /** Weighted sum of equally sized components; weights are applied element-wise. */
    fun mix(
        components: List<Pair<FloatArray, Double>>,
        size: Int,
    ): FloatArray {
        val output = FloatArray(size)
        for ((samples, weight) in components) {
            for (index in 0 until size) {
                output[index] += samples[index] * weight.toFloat()
            }
        }
        return output
    }
}
