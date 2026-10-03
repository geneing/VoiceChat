package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.eval.litert.melFilterbank
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin

/**
 * The streaming log-mel front end for Parakeet-EOU-120M (stream 1).
 *
 * A faithful pure-Kotlin port of `MelExtractor` in
 * `scripts/parakeet-eou-host.py`, which is the cross-check oracle. The pipeline
 * is deliberately *not* the Whisper/HF normalization path used elsewhere in the
 * app ([com.voicechat.agent.eval.litert.LogMelSpectrogram]); it reproduces the
 * NeMo EOU contract exactly:
 *
 * 1. pre-emphasis `y[0] = x[0]`, `y[i] = x[i] - preEmphasis * x[i-1]`;
 * 2. **zero** center padding of `nFft / 2` on each side (not reflect, not omit);
 * 3. an [ParakeetEouConfig.winLength]-sample Hann window with denominator
 *    `winLength - 1` (`periodic = false`), centered in an [ParakeetEouConfig.nFft]
 *    frame at offset `(nFft - winLength) / 2`;
 * 4. hop [ParakeetEouConfig.hopLength], making `samples / hop + 1` frames;
 * 5. `rFFT`, power spectrum `re^2 + im^2` (unanormalized);
 * 6. a Slaney mel filterbank ([melFilterbank], `numMelBins + 2` points, area
 *    norm) with `nFft / 2 + 1` input bins;
 * 7. `ln(x + 2^-24)` and **no normalization**.
 *
 * Output layout is `[numMelBins, frames]` flattened mel-major (bin 0 of every
 * frame first), which is the layout [ParakeetEouRuntime.encode] expects.
 */
class StreamingMelFrontend(
    private val config: ParakeetEouConfig,
) {
    /** Number of mel bins in every output row. */
    val numMelBins: Int = config.numMelBins

    /** `(nFft - winLength) / 2`: where the analysis window sits inside an [config.nFft] frame. */
    private val windowOffset: Int = (config.nFft - config.winLength) / 2
    private val window: FloatArray = buildWindow()
    private val filters: Array<FloatArray> = melFilterbank(config.sampleRate, config.nFft, config.numMelBins)
    private val fft = ParakeetFft(config.nFft)

    /** The number of mel frames [extract] returns for an input of [samples] samples. */
    fun frameCount(samples: Int): Int = if (samples <= 0) 0 else samples / config.hopLength + 1

    /**
     * Extracts the log-mel spectrogram for [audio].
     *
     * @return a flat `[numMelBins, frameCount(audio.size)]` array, mel-major.
     *   An empty input yields an empty array.
     */
    fun extract(audio: FloatArray): FloatArray {
        val frames = frameCount(audio.size)
        if (frames <= 0) return FloatArray(0)

        val emphasized = FloatArray(audio.size)
        emphasized[0] = audio[0]
        for (i in 1 until audio.size) emphasized[i] = audio[i] - config.preEmphasis * audio[i - 1]

        val padding = config.nFft / 2
        val padded = FloatArray(padding + emphasized.size + padding)
        emphasized.copyInto(padded, padding)

        val mel = FloatArray(config.numMelBins * frames)
        val frame = FloatArray(config.nFft)
        for (f in 0 until frames) {
            java.util.Arrays.fill(frame, 0f)
            val start = f * config.hopLength + windowOffset
            for (i in window.indices) frame[windowOffset + i] = padded[start + i] * window[i]
            val power = fft.powerSpectrum(frame)
            for (m in 0 until config.numMelBins) {
                var sum = 0f
                val filter = filters[m]
                for (bin in filter.indices) sum += filter[bin] * power[bin]
                mel[m * frames + f] = ln(sum + LOG_GUARD)
            }
        }
        return mel
    }

    /** The Hann window of [ParakeetEouConfig.winLength] samples, `periodic = false`. */
    private fun buildWindow(): FloatArray {
        val length = config.winLength
        val denominator = length - 1
        return FloatArray(length) { i ->
            0.5f - 0.5f * cos(2.0 * PI * i / denominator).toFloat()
        }
    }

    private companion object {
        /** NeMo's `ln(x + 2^-24)` floor; exactly `5.960464477539063e-08`. */
        const val LOG_GUARD: Float = 5.9604645e-8f
    }
}

/**
 * Iterative radix-2 real FFT, ported from the private `Fft` in
 * [com.voicechat.agent.eval.litert.LogMelSpectrogram] (whose implementation is
 * itself a port of the pinned LiteRT sample). It returns the **unanormalized**
 * power spectrum `re^2 + im^2`, matching `numpy.fft.rfft` followed by the host's
 * `spectrum.real ** 2 + spectrum.imag ** 2`.
 */
private class ParakeetFft(
    private val n: Int,
) {
    private val cosTable = FloatArray(n / 2)
    private val sinTable = FloatArray(n / 2)

    init {
        for (i in 0 until n / 2) {
            val angle = -2.0 * PI * i / n
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
    }

    /** Power spectrum `|X|^2` for real [input] of length [n], length `n/2 + 1`. */
    fun powerSpectrum(input: FloatArray): FloatArray {
        val re = FloatArray(n)
        val im = FloatArray(n)
        input.copyInto(re)
        radix2(re, im)
        val power = FloatArray(n / 2 + 1)
        for (k in power.indices) power[k] = re[k] * re[k] + im[k] * im[k]
        return power
    }

    private fun radix2(
        re: FloatArray,
        im: FloatArray,
    ) {
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j or bit
            if (i < j) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                var k = 0
                for (p in i until i + len / 2) {
                    val c = cosTable[k]
                    val s = sinTable[k]
                    val odd = p + len / 2
                    val tRe = re[odd] * c - im[odd] * s
                    val tIm = re[odd] * s + im[odd] * c
                    re[odd] = re[p] - tRe
                    im[odd] = im[p] - tIm
                    re[p] += tRe
                    im[p] += tIm
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
