package com.voicechat.agent.turn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * M10 window preparation: the pinned graph's "most recent audio last, zeros at
 * the front" contract, with audio fed as raw PCM values (the graph embeds the
 * normalization).
 */
class SmartTurnWindowTest {
    @Test
    fun aShortWindowIsLeftPaddedWithZerosAndKeepsRawSamples() {
        val result = SmartTurnWindow.prepare(shortArrayOf(1, -2, 3), windowSamples = 6)

        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f, -2f, 3f), result, 0f)
    }

    @Test
    fun anExactLengthWindowIsCopiedUnchanged() {
        val result = SmartTurnWindow.prepare(shortArrayOf(7, 8, 9), windowSamples = 3)

        assertArrayEquals(floatArrayOf(7f, 8f, 9f), result, 0f)
    }

    @Test
    fun aLongerWindowKeepsOnlyTheMostRecentSamples() {
        val result = SmartTurnWindow.prepare(shortArrayOf(1, 2, 3, 4, 5), windowSamples = 3)

        assertArrayEquals(floatArrayOf(3f, 4f, 5f), result, 0f)
    }

    @Test
    fun fullScaleNegativeSamplesArePreservedAsRawValues() {
        val result = SmartTurnWindow.prepare(shortArrayOf(Short.MIN_VALUE, Short.MAX_VALUE), windowSamples = 2)

        assertEquals(Short.MIN_VALUE.toFloat(), result[0], 0f)
        assertEquals(Short.MAX_VALUE.toFloat(), result[1], 0f)
    }

    @Test
    fun anEmptyFrameBecomesAllZeros() {
        val result = SmartTurnWindow.prepare(ShortArray(0), windowSamples = 4)

        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 0f), result, 0f)
    }
}
