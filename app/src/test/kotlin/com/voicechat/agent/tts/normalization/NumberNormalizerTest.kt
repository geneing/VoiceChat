package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberNormalizerTest {
    private val normalizer = NumberNormalizer()

    @Test
    fun expandsCommaGroupedThousands() {
        assertEquals(
            "There were one thousand two hundred thirty-four people.",
            normalizer.apply("There were 1,234 people."),
        )
        assertEquals(
            "It cost twelve million three hundred forty-five thousand six hundred seventy-eight dollars.",
            normalizer.apply("It cost 12,345,678 dollars."),
        )
    }

    @Test
    fun expandsBareYearsInTheCommonRange() {
        assertEquals(
            "In nineteen ninety-nine, she left.",
            normalizer.apply("In 1999, she left."),
        )
        assertEquals(
            "Copenhagen was founded in eleven sixty-seven.",
            normalizer.apply("Copenhagen was founded in 1167."),
        )
    }

    @Test
    fun expandsDecimals() {
        assertEquals(
            "The value is three point one four.",
            normalizer.apply("The value is 3.14."),
        )
        assertEquals("Add zero point five cups.", normalizer.apply("Add 0.5 cups."))
        assertEquals("It is two point five zero long.", normalizer.apply("It is 2.50 long."))
        assertEquals(
            "A total of one thousand two hundred thirty-four point five six.",
            normalizer.apply("A total of 1,234.56."),
        )
    }

    @Test
    fun expandsNegativeNumbers() {
        assertEquals("The balance is minus forty-two.", normalizer.apply("The balance is -42."))
    }

    @Test
    fun hyphenAfterADigitIsNotASign() {
        // `-5` follows a digit, so the sign lookbehind rejects it. The range
        // rule reads the whole span earlier in the pipeline.
        assertEquals("He ran three-5 miles.", normalizer.apply("He ran 3-5 miles."))
        assertEquals("The 1st and 3D versions.", normalizer.apply("The 1st and 3D versions."))
    }
}
