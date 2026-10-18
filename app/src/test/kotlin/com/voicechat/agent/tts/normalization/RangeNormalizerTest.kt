package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeNormalizerTest {
    private val range = RangeNormalizer()

    @Test
    fun punctuationRanges() {
        assertEquals("three to five", range.apply("3-5"))
        assertEquals("nineteen ninety to two thousand", range.apply("1990-2000"))
        assertEquals("one thousand to two thousand", range.apply("1,000-2,000"))
        assertEquals("five to seven", range.apply("5 \u2013 7"))
    }

    @Test
    fun aNegativeIsNotARange() {
        assertEquals("-5", range.apply("-5"))
        assertEquals("x -5", range.apply("x -5"))
    }

    @Test
    fun numbersEmbeddedInWordsAreNotRanges() {
        assertEquals("COVID-19", range.apply("COVID-19"))
        assertEquals("3D", range.apply("3D"))
    }
}
