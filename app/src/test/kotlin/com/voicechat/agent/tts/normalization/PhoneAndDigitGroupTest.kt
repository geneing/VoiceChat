package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneAndDigitGroupTest {
    private val normalizer = DigitGroupNormalizer()

    @Test
    fun phoneNumbersWithHyphens() {
        assertEquals(
            "Call area code four one five five five five one two three four now.",
            normalizer.apply("Call 415-555-1234 now."),
        )
    }

    @Test
    fun phoneNumbersWithParentheses() {
        assertEquals(
            "Call area code four one five five five five one two three four.",
            normalizer.apply("Call (415) 555-1234."),
        )
    }

    @Test
    fun phoneNumbersWithDotsAndSpaces() {
        assertEquals(
            "Call area code four one five five five five one two three four.",
            normalizer.apply("Call 415.555.1234."),
        )
        assertEquals(
            "Call area code four one five five five five one two three four now.",
            normalizer.apply("Call 415 555 1234 now."),
        )
    }

    @Test
    fun phoneNumbersWithAnExtension() {
        assertEquals(
            "Call area code four one five five five five one two three four extension one two.",
            normalizer.apply("Call 415-555-1234 ext. 12."),
        )
    }

    @Test
    fun versionNumbersReadDigitByDigitWithPoints() {
        assertEquals("version one point two point three", normalizer.apply("version 1.2.3"))
        assertEquals("two point zero point one", normalizer.apply("v2.0.1"))
    }

    @Test
    fun longDigitRunsReadDigitByDigit() {
        assertEquals("ID two three four five six seven.", normalizer.apply("ID 234567."))
    }

    @Test
    fun fourDigitYearRangeIsLeftForTheRangeRule() {
        assertEquals("1990-2000", normalizer.apply("1990-2000"))
    }
}
