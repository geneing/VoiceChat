package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberSpellerTest {
    @Test
    fun cardinalSpellsQuantities() {
        assertEquals("zero", NumberSpeller.cardinal(0))
        assertEquals("seven", NumberSpeller.cardinal(7))
        assertEquals("thirteen", NumberSpeller.cardinal(13))
        assertEquals("twenty-one", NumberSpeller.cardinal(21))
        assertEquals("one hundred", NumberSpeller.cardinal(100))
        assertEquals("one hundred one", NumberSpeller.cardinal(101))
        assertEquals("nine hundred ninety-nine", NumberSpeller.cardinal(999))
        assertEquals("one thousand", NumberSpeller.cardinal(1000))
        assertEquals(
            "one thousand two hundred thirty-four",
            NumberSpeller.cardinal(1234),
        )
        assertEquals("one million", NumberSpeller.cardinal(1_000_000))
        assertEquals(
            "one million two hundred thirty-four thousand five hundred sixty-seven",
            NumberSpeller.cardinal(1_234_567),
        )
    }

    @Test
    fun yearUsesTheSpokenForm() {
        assertEquals("one thousand", NumberSpeller.year(1000))
        assertEquals("ten sixty-six", NumberSpeller.year(1066))
        assertEquals("nineteen hundred", NumberSpeller.year(1900))
        assertEquals("nineteen oh five", NumberSpeller.year(1905))
        assertEquals("nineteen sixty-six", NumberSpeller.year(1966))
        assertEquals("nineteen ninety-nine", NumberSpeller.year(1999))
        assertEquals("two thousand", NumberSpeller.year(2000))
        assertEquals("two thousand five", NumberSpeller.year(2005))
        assertEquals("twenty ten", NumberSpeller.year(2010))
        assertEquals("twenty thirteen", NumberSpeller.year(2013))
        assertEquals("twenty ninety-nine", NumberSpeller.year(2099))
    }

    @Test
    fun tokenReadingContextIsExplicit() {
        assertEquals(
            "nineteen ninety-nine",
            NumberSpeller.spellToken("1999", NumberSpeller.Reading.YEAR),
        )
        assertEquals(
            "one thousand five hundred",
            NumberSpeller.spellToken("1500", NumberSpeller.Reading.QUANTITY),
        )
        assertEquals("one thousand five hundred", NumberSpeller.quantity("1500"))
    }

    @Test
    fun ordinalSpelling() {
        assertEquals("twenty-first", NumberSpeller.ordinal(21))
        assertEquals("fourth", NumberSpeller.ordinal(4))
        assertEquals("twelfth", NumberSpeller.ordinal(12))
        assertEquals("one hundredth", NumberSpeller.ordinal(100))
        assertEquals("one hundred first", NumberSpeller.ordinal(101))
        assertEquals("one thousandth", NumberSpeller.ordinal(1000))
        assertEquals("one millionth", NumberSpeller.ordinal(1_000_000))
    }

    @Test
    fun decadeSpelling() {
        assertEquals("nineteen nineties", NumberSpeller.decade(1990))
        assertEquals("eighties", NumberSpeller.decade(80))
        assertEquals("two thousands", NumberSpeller.decade(2000))
    }

    @Test
    fun decimalsReadDigitByDigitAfterPoint() {
        assertEquals("three point one four", NumberSpeller.decimal(3, "14"))
        assertEquals("zero point five", NumberSpeller.decimal(0, "5"))
        assertEquals(
            "one thousand two hundred thirty-four point five zero",
            NumberSpeller.decimal(1234, "50"),
        )
        assertEquals("zero zero seven", NumberSpeller.digits("007"))
    }
}
