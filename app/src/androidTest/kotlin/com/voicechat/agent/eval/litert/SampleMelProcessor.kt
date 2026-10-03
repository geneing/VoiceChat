package com.voicechat.agent.eval.litert

import com.jlibrosa.audio.JLibrosa
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Port of the pinned sample's log-Mel front end (`MelSpectroProcessor.kt`),
 * including jlibrosa defaults. Those defaults use a 512-sample window and
 * frame-major TDT output; they do not match the HF Parakeet TDT profile's
 * 400-sample symmetric Hann window or mel-major input layout, so the TDT corpus
 * path intentionally uses [LogMelSpectrogram] instead.
 *
 * Keep this implementation as the pinned-sample/CTC comparison and diagnostic
 * fallback. Presence of the jlibrosa JAR must not select it for TDT.
 */
internal class SampleMelProcessor(
    private val samplingRate: Int,
    private val nFft: Int,
    private val nMels: Int,
    private val hopLength: Int,
    private val nFrames: Int,
    private val transpose: Boolean,
    private val preemphasis: Float,
    private val normType: String,
) : FeatureSource {
    override fun process(audio: FloatArray): FloatArray {
        if (audio.isEmpty()) return FloatArray(0)
        val emphasizedAudio = if (preemphasis > 0f) applyPreemphasis(audio) else audio

        val paddedNFFT = smallestPowerOfTwoAtLeast(nFft)
        val melSpec = generateMelSpectrogram(emphasizedAudio, samplingRate, paddedNFFT, nMels, hopLength)
        val validFrames = audio.size / hopLength

        if (normType == "whisper") {
            var maxVal = Float.NEGATIVE_INFINITY
            for (m in melSpec.indices) {
                for (f in 0 until validFrames) {
                    melSpec[m][f] /= LN_10
                    if (melSpec[m][f] > maxVal) maxVal = melSpec[m][f]
                }
            }
            val clipVal = maxVal - 8.0f
            for (m in melSpec.indices) {
                for (f in 0 until validFrames) melSpec[m][f] = (max(melSpec[m][f], clipVal) + 4.0f) / 4.0f
            }
        } else {
            val means = FloatArray(melSpec.size)
            for (m in melSpec.indices) {
                var sum = 0.0f
                for (f in 0 until validFrames) sum += melSpec[m][f]
                means[m] = sum / validFrames
            }
            for (m in melSpec.indices) {
                var sumSq = 0.0f
                for (f in 0 until validFrames) {
                    val diff = melSpec[m][f] - means[m]
                    sumSq += diff * diff
                }
                val std = sqrt(sumSq / max(1, validFrames - 1)) + EPSILON
                for (f in 0 until validFrames) melSpec[m][f] = (melSpec[m][f] - means[m]) / std
            }
        }

        val frames = if (nFrames > 0) nFrames else melSpec[0].size
        val result = FloatArray(frames * nMels)
        for (m in melSpec.indices) {
            for (f in melSpec[m].indices) {
                if (f >= frames) break
                val value = if (f < validFrames) melSpec[m][f] else 0.0f
                if (transpose) {
                    result[f * nMels + m] = value
                } else {
                    result[m * frames + f] = value
                }
            }
        }
        return result
    }

    private fun applyPreemphasis(audio: FloatArray): FloatArray {
        val coeff = preemphasis
        if (audio.isEmpty()) return FloatArray(0)
        val result = FloatArray(audio.size)
        result[0] = audio[0]
        for (i in 1 until audio.size) result[i] = audio[i] - coeff * audio[i - 1]
        return result
    }

    private fun smallestPowerOfTwoAtLeast(n: Int): Int {
        if (n > 0 && (n and (n - 1)) == 0) return n
        var p = 1
        while (p < n) p = p shl 1
        return p
    }

    private companion object {
        val LOG_ZERO_GUARD = 2.0f.pow(-24)
        const val EPSILON = 1e-5f
        val LN_10 = ln(10.0f)
        val jLibrosa by lazy { JLibrosa() }

        fun generateMelSpectrogram(
            waveform: FloatArray,
            samplingRate: Int,
            nFft: Int,
            nMels: Int,
            hopLength: Int,
        ): Array<FloatArray> {
            val melSpec = jLibrosa.generateMelSpectroGram(waveform, samplingRate, nFft, nMels, hopLength)
            for (m in melSpec.indices) {
                for (f in melSpec[m].indices) melSpec[m][f] = ln(melSpec[m][f] + LOG_ZERO_GUARD)
            }
            return melSpec
        }
    }
}
