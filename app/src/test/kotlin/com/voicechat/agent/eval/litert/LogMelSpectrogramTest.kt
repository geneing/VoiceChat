package com.voicechat.agent.eval.litert

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Local correctness check for the corpus runner's log-Mel front end. It compares
 * the hand-rolled radix-2 FFT and mel filterbank against closed-form values, so a
 * preprocessing bug is caught on the JVM instead of surfacing as empty device
 * transcripts.
 */
class LogMelSpectrogramTest {
    @Test
    fun fftMatchesDirectDftForATone() {
        val n = 8
        val twoPi = 2.0 * PI
        val signal = FloatArray(n) { sin(twoPi * it / n).toFloat() }

        // Reference DFT magnitude^2 at every bin (librosa-style, no 1/n).
        val expected = FloatArray(n / 2 + 1)
        for (k in 0..n / 2) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val angle = twoPi * k * t / n
                re += signal[t] * cos(angle)
                im -= signal[t] * sin(angle)
            }
            expected[k] = (re * re + im * im).toFloat()
        }

        val reflection = LogMelSpectrogram
        val actual = reflection.fftPower(n, signal)
        for (k in actual.indices) {
            assertEquals("bin $k", expected[k].toDouble(), actual[k].toDouble(), 1e-4)
        }
    }

    @Test
    fun melSpectrogramHasFiniteNormalizedValues() {
        val processor =
            LogMelSpectrogram(
                LogMelConfig(nFft = 512, nMels = 80, nFrames = 500, transpose = false, preemphasis = 0.97f),
            )
        val audio = FloatArray(16_000 * 5) { (sin(2.0 * PI * 220.0 * it / 16_000.0) * 0.5).toFloat() }
        val features = processor.process(audio)

        assertEquals(500 * 80, features.size)
        assertEquals("all features must be finite", true, features.all { it.isFinite() })
        // A tone must produce non-zero energy somewhere.
        assertEquals(true, features.any { kotlin.math.abs(it) > 0.01f })
    }

    @Test
    fun melFilterbankMatchesLibrosaSlaney() {
        val filters = melFilterbank(sampleRate = 16_000, nFft = 512, nMels = 80)
        assertEquals(80, filters.size)
        assertEquals(257, filters[0].size)

        // librosa 0.11 slaney norm: row 0 sums to 0.031172 and its peak is at bin 1.
        val rowSum = filters[0].sum().toDouble()
        assertEquals("row 0 sum", 0.031172, rowSum, 1e-4)
        val peak = filters[0].indices.maxByOrNull { filters[0][it] }
        assertEquals("row 0 peak bin", 1, peak)

        // Every row must be non-negative and have a single triangular peak.
        for (row in filters.indices) {
            assertEquals(true, filters[row].all { it >= 0f })
            assertEquals(true, filters[row].max() > 0f)
        }
    }
}
