package com.voicechat.agent.stt.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Cross-checks the pure-Kotlin mel front end against the host oracle
 * (`scripts/parakeet-eou-host.py`, `MelExtractor`).
 *
 * The expected constants were produced by running the host script's extractor on
 * a deterministic 1 kHz sine (amplitude 0.5, 5,120 samples at 16 kHz), then
 * frozen here. The host computes in float32 (`numpy.fft.rfft` on a float32
 * frame), so the tolerance is the float32 noise floor: ~1e-4 relative on power,
 * which after `ln` is ~1e-3 absolute on a value of magnitude ~10. Bit equality
 * is deliberately not asserted.
 */
class StreamingMelFrontendTest {
    private val config = ParakeetEouConfig.pinned()
    private val frontend = StreamingMelFrontend(config)

    /** One 5,120-sample deterministic tone: 1 kHz at amplitude 0.5. */
    private fun tone(): FloatArray =
        FloatArray(SAMPLE_COUNT) { i ->
            (0.5 * sin(2.0 * PI * 1000.0 * i / 16_000.0)).toFloat()
        }

    @Test
    fun frameCountMatchesTheHostGeometry() {
        assertEquals(0, frontend.frameCount(0))
        assertEquals(0, frontend.frameCount(-5))
        assertEquals(33, frontend.frameCount(5_120))
        assertEquals(65, frontend.frameCount(10_240))
    }

    @Test
    fun emptyAudioProducesNoFrames() {
        assertTrue(frontend.extract(FloatArray(0)).isEmpty())
    }

    @Test
    fun extractMatchesTheHostReferenceForATone() {
        val frames = frontend.frameCount(SAMPLE_COUNT)
        assertEquals(33, frames)
        val mel = frontend.extract(tone())
        assertEquals(128 * frames, mel.size)

        assertBins(mel, frames, frame = 0, expected = FRAME_0_BINS_0_7)
        assertBins(mel, frames, frame = 1, expected = FRAME_1_BINS_0_7)
        assertBins(mel, frames, frame = 2, expected = FRAME_2_BINS_0_7)
        assertBins(mel, frames, frame = 32, expected = FRAME_32_BINS_0_7)

        assertMel(mel, frames, frame = 0, bin = 64, expected = -5.113523238f)
        assertMel(mel, frames, frame = 0, bin = 100, expected = -7.347775178f)
        assertMel(mel, frames, frame = 0, bin = 127, expected = -8.088248437f)
    }

    private fun assertBins(
        mel: FloatArray,
        frames: Int,
        frame: Int,
        expected: FloatArray,
    ) {
        expected.forEachIndexed { bin, value ->
            assertMel(mel, frames, frame, bin, value)
        }
    }

    private fun assertMel(
        mel: FloatArray,
        frames: Int,
        frame: Int,
        bin: Int,
        expected: Float,
    ) {
        val actual = mel[bin * frames + frame]
        assertEquals(
            "mel[bin=$bin, frame=$frame]",
            expected.toDouble(),
            actual.toDouble(),
            TOLERANCE,
        )
    }

    private companion object {
        const val SAMPLE_COUNT = 5_120

        /** ~1e-4 relative on a value of magnitude ~10. */
        const val TOLERANCE = 1e-3

        val FRAME_0_BINS_0_7 =
            floatArrayOf(
                -9.944605129f,
                -9.764101867f,
                -9.593200984f,
                -8.791845535f,
                -8.799203434f,
                -8.613832796f,
                -8.420529460f,
                -7.719317007f,
            )

        val FRAME_1_BINS_0_7 =
            floatArrayOf(
                -15.313564162f,
                -14.888440929f,
                -14.571511843f,
                -13.615093086f,
                -13.674137816f,
                -13.394552720f,
                -13.134666875f,
                -12.379123633f,
            )

        val FRAME_2_BINS_0_7 =
            floatArrayOf(
                -16.610703293f,
                -16.592412543f,
                -16.573098212f,
                -16.521069464f,
                -16.595674123f,
                -16.612495582f,
                -16.630361147f,
                -16.503719048f,
            )

        val FRAME_32_BINS_0_7 =
            floatArrayOf(
                -6.552700947f,
                -6.545131592f,
                -6.523391744f,
                -6.107896421f,
                -6.469775417f,
                -6.446723525f,
                -6.394956709f,
                -5.968717048f,
            )
    }
}
