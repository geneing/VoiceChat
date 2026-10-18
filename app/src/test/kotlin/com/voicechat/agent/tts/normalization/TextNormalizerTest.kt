package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TextNormalizerTest {
    private val normalizer = TextNormalizer.English

    @Test
    fun combinesAbbreviationAndNumberRules() {
        assertEquals(
            "Mister Smith paid one thousand two hundred thirty-four in nineteen ninety-nine.",
            normalizer.normalize("Mr. Smith paid 1,234 in 1999."),
        )
    }

    @Test
    fun romanNumeralsUseTitleAndRegnalContextThroughTheFullPipeline() {
        assertEquals(
            "Chapter four, then Henry the Eighth.",
            normalizer.normalize("Chapter IV, then Henry VIII."),
        )
        assertEquals("Vol. four", normalizer.normalize("Vol. IV"))
        assertEquals("Vitamin C is essential.", normalizer.normalize("Vitamin C is essential."))
    }

    @Test
    fun sentenceWithManyShapesIsNormalized() {
        assertEquals(
            "On January thirteenth two thousand seven, Mister Smith paid " +
                "one thousand two hundred thirty-four dollars and fifty cents " +
                "for three point five kilograms of coffee.",
            normalizer.normalize(
                "On Jan. 13, 2007, Mr. Smith paid $1,234.50 for 3.5 kg of coffee.",
            ),
        )
    }

    @Test
    fun rangesPercentagesAndTimes() {
        assertEquals(
            "nineteen ninety to two thousand and one thousand five hundred percent " +
                "and nine thirty in the morning",
            normalizer.normalize("1990-2000 and 1500% and 9:30 a.m."),
        )
    }

    @Test
    fun yearAndQuantityContextsStayDistinct() {
        assertEquals("nineteen ninety-nine", normalizer.normalize("1999"))
        assertEquals("one thousand five hundred percent", normalizer.normalize("1500%"))
        assertEquals("one thousand five hundred dollars", normalizer.normalize("\$1500"))
        assertEquals("one thousand five hundred kilograms", normalizer.normalize("1500 kg"))
        assertEquals("in fifteen hundred", normalizer.normalize("in 1500"))
    }

    @Test
    fun urlsAndFractions() {
        assertEquals(
            "three quarters of one half",
            normalizer.normalize("3/4 of 1/2"),
        )
        assertEquals(
            "e x a m p l e dot c o m slash p a t h and u s e r at e x a m p l e dot c o m",
            normalizer.normalize("example.com/path and user@example.com"),
        )
    }

    @Test
    fun normalizationIsIdempotent() {
        val corpus =
            listOf(
                "Mr. Smith paid $1,234.56 on 02/03/2007 at 9:30 a.m.",
                "1500% is 5 m and 3 in.",
                "In 1999, the value was 1,234.50.",
                "Visit example.com/path or user@example.com, then https://a.b.",
                "Versions 1.2.3 and 415-555-1234 are listed.",
                "3/4 of 1/2, 1990-2000, and 7:00 p.m.",
                "Dr. Jones paid $1500 for 10 kg.",
                "© 2007 Readium & friends.",
            )
        corpus.forEach { source ->
            val once = normalizer.normalize(source)
            assertEquals("Normalization was not idempotent for: $source", once, normalizer.normalize(once))
        }
    }

    @Test
    fun idempotenceCoversMixedSpeechShapes() {
        val corpus =
            listOf(
                "Feb. 13, 2007", // Date
                "\$12.34", // Currency
                "50%", // Percentage
                "3-5", // Range
                "Chapter IV", // Contextual Roman numeral
                "Dr. Smith", // Abbreviation
                "NASA and WHO", // Pronounceable acronym and spelled initialism
                "https://example.com/path", // URL
                "3/4", // Fraction
            )
        corpus.forEach { source ->
            val once = normalizer.normalize(source)
            assertEquals("Normalization was not idempotent for: $source", once, normalizer.normalize(once))
        }
    }

    @Test
    fun laterOverlappingRulesDoNotReExpandEarlierOutput() {
        val number = NumberNormalizer()
        val symbol = SymbolNormalizer()
        val abbreviation = AbbreviationNormalizer()
        val nonProse = NonProseNormalizer()
        val percentage = PercentageNormalizer()
        val currency = CurrencyNormalizer()
        val units = UnitAndSymbolNormalizer()
        val cases: List<Triple<TextNormalizationRule, TextNormalizationRule, String>> =
            listOf(
                Triple(number, symbol, "1999"),
                Triple(percentage, number, "1500%"),
                Triple(currency, number, "\$1500"),
                Triple(units, number, "1500 kg"),
                Triple(abbreviation, symbol, "a.m."),
                Triple(nonProse, number, "example.com/path"),
            )
        cases.forEach { (first, later, source) ->
            val expanded = first.apply(source)
            assertEquals("Later rule re-expanded output for: $source", expanded, later.apply(expanded))
        }

        // This is the one intentional intermediate handoff in the pipeline:
        // dotted-number output is consumed once, then remains final speech.
        val grouped = DigitGroupNormalizer().apply("1.2.3")
        val spoken = DottedNumberNormalizer().apply(grouped)
        assertEquals("one point two point three", spoken)
        assertEquals(spoken, DottedNumberNormalizer().apply(spoken))
    }

    @Test
    fun nonProseHostsWinWithoutCapturingNumericAndAbbreviationNearMisses() {
        val nonProseCases =
            listOf(
                "example.com/path" to "e x a m p l e dot c o m slash p a t h",
                "user@example.com" to "u s e r at e x a m p l e dot c o m",
                "https://a.b" to "h t t p s colon slash slash a dot b",
            )
        nonProseCases.forEach { (source, expected) ->
            assertEquals(source, expected, NonProseNormalizer().apply(source))
        }

        val proseCases =
            listOf(
                "1,234.50" to "one thousand two hundred thirty-four point five zero",
                "1.2.3" to "one point two point three",
                "02/03/2007" to "March second, two thousand seven",
                "a.m." to "A M",
            )
        proseCases.forEach { (source, expected) ->
            assertEquals(source, expected, normalizer.normalize(source))
        }
    }

    @Test
    fun emptyTextStaysEmpty() {
        assertEquals("", normalizer.normalize(""))
    }

    @Test
    fun unknownLanguageFallsBackToEnglish() {
        assertEquals(
            "one thousand two hundred thirty-four",
            TextNormalizer.forLanguageTag(null).normalize("1,234"),
        )
        assertEquals(
            "one thousand two hundred thirty-four",
            TextNormalizer.forLanguageTag("en-GB").normalize("1,234"),
        )
    }

    @Test
    fun nonEnglishIsPassedThroughUnchanged() {
        assertEquals(
            "M. Dupont a payé 1 234 euros.",
            TextNormalizer.forLanguageTag("fr").normalize(
                "M. Dupont a payé 1 234 euros.",
            ),
        )
    }

    @Test
    fun languageTagGateSelectsEnglishOrPassesThrough() {
        assertSame(TextNormalizer.English, TextNormalizer.forLanguageTag("en-US"))
        assertSame(TextNormalizer.English, TextNormalizer.forLanguageTag(null))

        val german = "Herr Müller kaufte 1,234 Bücher im Jahr 1999."
        assertEquals(german, TextNormalizer.forLanguageTag("de").normalize(german))
    }
}
