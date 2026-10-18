package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class SymbolAndUnitTest {
    private val symbols = SymbolNormalizer()
    private val units = UnitAndSymbolNormalizer()
    private val fractions = FractionNormalizer()
    private val hygiene = HygieneNormalizer()

    @Test
    fun symbolsBecomeWords() {
        assertEquals("salt and pepper", normalizeSpacing(symbols.apply("salt & pepper")))
        assertEquals("copyright 2007", normalizeSpacing(symbols.apply("© 2007")))
        assertEquals("two plus two equals four", normalizeSpacing(symbols.apply("2 + 2 = 4")))
    }

    @Test
    fun abbreviationPeriodFallsInLowercaseChainsOnly() {
        assertEquals("eg this and ie that", symbols.apply("e.g this and i.e that"))
        assertEquals("am and pm", symbols.apply("a.m and p.m"))
        assertEquals("U.S and U.K", symbols.apply("U.S and U.K"))
        assertEquals("He saw Dr. X. Then he left.", symbols.apply("He saw Dr. X. Then he left."))
    }

    @Test
    fun unitsExpandAfterANumber() {
        assertEquals("five kilometers", normalizeSpacing(units.apply("5 km")))
        assertEquals("six feet.", normalizeSpacing(units.apply("6 ft.")))
        assertEquals("seventy miles per hour", normalizeSpacing(units.apply("70 mph")))
        assertEquals("twenty degrees Celsius", normalizeSpacing(units.apply("20 °C")))
        assertEquals("ten kilograms", normalizeSpacing(units.apply("10 kg")))
        assertEquals("twenty-five degrees", normalizeSpacing(units.apply("25°")))
        assertEquals("two times three", normalizeSpacing(units.apply("2 x 3")))
    }

    @Test
    fun unitsAndNumbersCombineThroughThePipeline() {
        assertEquals("five kilometers", TextNormalizer.English.normalize("5 km"))
        assertEquals("six feet.", TextNormalizer.English.normalize("6 ft."))
    }

    @Test
    fun shortUnitsRequireMeasurementContext() {
        assertEquals("five meters", TextNormalizer.English.normalize("5 m"))
        assertEquals("three inches.", TextNormalizer.English.normalize("3 in."))
        assertEquals("two liters", TextNormalizer.English.normalize("2 l"))
        assertEquals("four grams", TextNormalizer.English.normalize("4 g"))
        assertEquals("six hours", TextNormalizer.English.normalize("6 h"))
        assertEquals(
            "one thousand two hundred thirty-four in nineteen ninety-nine",
            TextNormalizer.English.normalize("1,234 in 1999"),
        )
    }

    @Test
    fun fractions() {
        assertEquals("three quarters", fractions.apply("3/4"))
        assertEquals("two quarters", fractions.apply("2/4"))
        assertEquals("three tenths", fractions.apply("3/10"))
        assertEquals("two twenty-sevenths", fractions.apply("2/27"))
    }

    @Test
    fun hygieneNormalizesWhitespaceAndPunctuation() {
        assertEquals("a b c", hygiene.apply("a\u00A0b\u2009c"))
        assertEquals("What?", hygiene.apply("What???"))
        assertEquals("etc...", hygiene.apply("etc\u2026"))
    }

    @Test
    fun hygieneRepairsLineBreakHyphenation() {
        assertEquals("example", hygiene.apply("exam-\nple"))
    }

    @Test
    fun hygieneDecodesEntitiesAndSmartQuotes() {
        assertEquals("\"It's here\"", hygiene.apply("\u201CIt\u2019s here\u201D"))
        assertEquals("Mark - Twain", hygiene.apply("Mark\u2014Twain"))
        assertEquals("Tom & Jerry", hygiene.apply("Tom &amp; Jerry"))
    }

    /** Symbol/unit words carry their own spaces, so compare on collapsed spacing. */
    private fun normalizeSpacing(text: String) = text.replace(Regex("\\s+"), " ").trim()
}
