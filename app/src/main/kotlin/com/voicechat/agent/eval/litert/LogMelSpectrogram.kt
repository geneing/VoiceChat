/*
 * Port of the pinned LiteRT speech-recognition sample's log-Mel front end
 * (MelSpectroProcessor.kt + jlibrosa defaults) into pure Kotlin, so the corpus
 * runner can preprocess audio on-device without the sample's jlibrosa JAR or a
 * host-side dependency. Original sample source is Apache-2.0:
 * https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition
 *
 * The mel filterbank uses librosa's "slaney" scale and area norm. Window length,
 * Hann periodicity, center padding, and output layout are explicit per model so
 * this implementation can match the Hugging Face Parakeet feature extractor.
 */
package com.voicechat.agent.eval.litert

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin

/** Log-Mel configuration for one model family (from model_metadata.json). */
internal data class LogMelConfig(
    val sampleRate: Int = 16_000,
    val nFft: Int,
    val nMels: Int,
    val hopLength: Int = 160,
    val nFrames: Int,
    val transpose: Boolean,
    val preemphasis: Float,
    val normType: String = "standard",
    /** Analysis window length before symmetric zero-padding to [nFft]. */
    val winLength: Int = nFft,
    /** PyTorch/NeMo's non-periodic Hann uses a denominator of `winLength - 1`. */
    val periodicWindow: Boolean = true,
    /** librosa defaults to reflect; the HF Parakeet extractor uses constant center padding. */
    val reflectCenterPadding: Boolean = true,
    /** Maximum input samples before STFT; lets a fixed output include padded frames. */
    val inputSampleLimit: Int? = null,
) {
    init {
        require(winLength in 2..nFft) { "winLength must be in 2..nFft" }
        require(inputSampleLimit == null || inputSampleLimit >= 0) { "inputSampleLimit cannot be negative" }
    }

    companion object {
        /** HF Parakeet TDT-v3 feature layout and inference-time feature parameters. */
        fun parakeetTdt(): LogMelConfig =
            LogMelConfig(
                nFft = 512,
                nMels = 128,
                nFrames = 500,
                transpose = false,
                preemphasis = 0.97f,
                winLength = 400,
                periodicWindow = false,
                reflectCenterPadding = false,
                // The frozen (1, 128, 500) tensor has 499 valid hops and a
                // zero-padded final frame; keep this 4.99 s analysis window.
                inputSampleLimit = 499 * 160,
            )
    }
}

/** Anything that turns a waveform window into model input features. */
internal interface FeatureSource {
    fun process(audio: FloatArray): FloatArray
}

/**
 * Pure-Kotlin log-Mel spectrogram with model-configurable feature extraction:
 * preemphasis -> windowed STFT power spectrum -> slaney mel filterbank -> ln ->
 * per-mel mean/std normalization over valid frames -> pad/layout.
 */
internal class LogMelSpectrogram(
    private val config: LogMelConfig,
) : FeatureSource {
    private val window =
        FloatArray(config.nFft) { fftIndex ->
            val windowIndex = fftIndex - (config.nFft - config.winLength) / 2
            if (windowIndex !in 0 until config.winLength) {
                0f
            } else {
                val denominator = if (config.periodicWindow) config.winLength else config.winLength - 1
                0.5f - 0.5f * cos(2f * PI.toFloat() * windowIndex / denominator)
            }
        }
    private val melFilters = melFilterbank(config.sampleRate, config.nFft, config.nMels)
    private val fft = Fft(config.nFft)

    /** Converts a raw [-1,1] waveform window into the model's feature vector. */
    override fun process(audio: FloatArray): FloatArray {
        val inputAudio = config.inputSampleLimit?.let { audio.copyOf(minOf(audio.size, it)) } ?: audio
        if (inputAudio.isEmpty()) return FloatArray(if (config.nFrames > 0) config.nFrames * config.nMels else 0)
        val emphasizedAudio = if (config.preemphasis > 0f) preemphasize(inputAudio) else inputAudio
        // Both reference profiles center the STFT at frame * hopLength; their
        // edge padding mode is selected by [LogMelConfig.reflectCenterPadding].
        val padding = config.nFft / 2
        val centered = centerPad(emphasizedAudio, padding)
        val frameCount = inputAudio.size / config.hopLength
        val padded = FloatArray(config.nFft)
        val melSpec = Array(config.nMels) { FloatArray(frameCount) }

        for (frame in 0 until frameCount) {
            val start = frame * config.hopLength
            java.util.Arrays.fill(padded, 0f)
            val usable = min(config.nFft, centered.size - start)
            for (i in 0 until usable) padded[i] = centered[start + i] * window[i]
            val power = fft.powerSpectrum(padded)
            for (mel in 0 until config.nMels) {
                var sum = 0f
                val filter = melFilters[mel]
                for (bin in filter.indices) sum += filter[bin] * power[bin]
                melSpec[mel][frame] = ln(sum + LOG_ZERO_GUARD)
            }
        }

        if (config.normType == "whisper") {
            normalizeWhisper(melSpec, frameCount)
        } else {
            normalizeStandard(melSpec, frameCount)
        }

        val nFrames =
            if (config.nFrames > 0) {
                config.nFrames
            } else if (frameCount > 0) {
                frameCount
            } else {
                1
            }
        val result = FloatArray(nFrames * config.nMels)
        for (mel in 0 until config.nMels) {
            for (frame in 0 until min(nFrames, frameCount)) {
                val value = melSpec[mel][frame]
                if (config.transpose) {
                    result[frame * config.nMels + mel] = value
                } else {
                    result[mel * nFrames + frame] = value
                }
            }
        }
        return result
    }

    private fun preemphasize(audio: FloatArray): FloatArray {
        val result = FloatArray(audio.size)
        result[0] = audio[0]
        for (i in 1 until audio.size) result[i] = audio[i] - config.preemphasis * audio[i - 1]
        return result
    }

    /** Center padding used by the reference STFT (reflect for librosa, zero for HF Parakeet). */
    private fun centerPad(
        audio: FloatArray,
        padding: Int,
    ): FloatArray {
        if (padding <= 0) return audio
        val result = FloatArray(audio.size + 2 * padding)
        if (!config.reflectCenterPadding) {
            audio.copyInto(result, padding)
            return result
        }
        if (audio.size <= 1) return result
        for (i in 0 until padding) {
            val index = padding - i
            result[i] = if (index < audio.size) audio[index] else audio[0]
        }
        audio.copyInto(result, padding)
        for (i in 0 until padding) {
            val index = audio.size - 2 - i
            result[padding + audio.size + i] = if (index >= 0) audio[index] else audio[audio.size - 1]
        }
        // Reflect padding is bounded by the signal length; the loop above mirrors
        // fully for short inputs, which matches librosa for audio >= nFft.
        return result
    }

    private fun normalizeStandard(
        melSpec: Array<FloatArray>,
        frames: Int,
    ) {
        if (frames <= 0) return
        for (mel in melSpec.indices) {
            var sum = 0.0
            for (f in 0 until frames) sum += melSpec[mel][f]
            val mean = (sum / frames).toFloat()
            var sq = 0.0
            for (f in 0 until frames) {
                val diff = melSpec[mel][f] - mean
                sq += diff.toDouble() * diff
            }
            val std = (kotlin.math.sqrt(sq / maxOf(1, frames - 1))).toFloat() + EPSILON
            for (f in 0 until frames) melSpec[mel][f] = (melSpec[mel][f] - mean) / std
        }
    }

    private fun normalizeWhisper(
        melSpec: Array<FloatArray>,
        frames: Int,
    ) {
        if (frames <= 0) return
        var maxVal = Float.NEGATIVE_INFINITY
        for (mel in melSpec.indices) {
            for (f in 0 until frames) {
                melSpec[mel][f] /= LN_10
                if (melSpec[mel][f] > maxVal) maxVal = melSpec[mel][f]
            }
        }
        val clip = maxVal - 8f
        for (mel in melSpec.indices) {
            for (f in 0 until frames) {
                melSpec[mel][f] = (maxOf(melSpec[mel][f], clip) + 4f) / 4f
            }
        }
    }

    private class Fft(
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

        /** Power spectrum (|X|^2 / n) for the real input, length n/2+1. */
        fun powerSpectrum(input: FloatArray): FloatArray {
            val re = FloatArray(n)
            val im = FloatArray(n)
            input.copyInto(re)
            radix2(re, im)
            val half = n / 2 + 1
            val power = FloatArray(n / 2 + 1)
            for (k in 0 until half) {
                power[k] = re[k] * re[k] + im[k] * im[k]
            }
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
                        val even = p
                        val odd = p + len / 2
                        val tRe = re[odd] * c - im[odd] * s
                        val tIm = re[odd] * s + im[odd] * c
                        re[odd] = re[even] - tRe
                        im[odd] = im[even] - tIm
                        re[even] += tRe
                        im[even] += tIm
                        k += step
                    }
                    i += len
                }
                len = len shl 1
            }
        }
    }

    internal companion object {
        const val LOG_ZERO_GUARD = 5.9604645e-8f
        const val EPSILON = 1e-5f
        val LN_10 = ln(10f)

        /** JVM-test hook for the FFT; the corpus path never reaches this. */
        internal fun fftPower(
            n: Int,
            input: FloatArray,
        ): FloatArray = Fft(n).powerSpectrum(input)
    }
}

/** librosa-compatible slaney mel filterbank, [nMels][nFft/2+1]. */
internal fun melFilterbank(
    sampleRate: Int,
    nFft: Int,
    nMels: Int,
): Array<FloatArray> {
    val fMax = sampleRate / 2.0
    val fSp = 200.0 / 3.0
    val minLogHz = 1000.0
    val minLogMel = minLogHz / fSp
    val logStep = ln(6.4) / 27.0

    fun hzToMel(hz: Double): Double = if (hz < minLogHz) hz / fSp else minLogMel + ln(hz / minLogHz) / logStep

    fun melToHz(mel: Double): Double = if (mel < minLogMel) mel * fSp else minLogHz * kotlin.math.exp(logStep * (mel - minLogMel))

    // nMels+2 equally spaced points on the mel scale, including both edges.
    val melPoints = DoubleArray(nMels + 2) { hzToMel(0.0) + (hzToMel(fMax) - hzToMel(0.0)) * it / (nMels + 1) }
    val hzPoints = DoubleArray(nMels + 2) { melToHz(melPoints[it]) }

    // librosa: fftfreqs uses n_fft points over [0, sr], then band edges are
    // rounded to the nearest FFT bin.
    val fftFreqs = DoubleArray(nFft / 2 + 1) { it.toDouble() * sampleRate / nFft }
    val filters = Array(nMels) { FloatArray(nFft / 2 + 1) }
    for (mel in 0 until nMels) {
        val lower = hzPoints[mel]
        val center = hzPoints[mel + 1]
        val upper = hzPoints[mel + 2]
        for (bin in fftFreqs.indices) {
            val freq = fftFreqs[bin]
            val rising = (freq - lower) / (center - lower)
            val falling = (upper - freq) / (upper - center)
            filters[mel][bin] = maxOf(0.0, minOf(rising, falling)).toFloat()
        }
        // Slaney area normalization: 2 / (upper - lower) over the filter's span.
        val enorm = 2.0 / (upper - lower)
        if (enorm.isFinite() && enorm > 0) {
            for (bin in filters[mel].indices) filters[mel][bin] = (filters[mel][bin] * enorm).toFloat()
        }
    }
    return filters
}
