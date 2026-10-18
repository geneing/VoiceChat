package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class RomanNumeralNormalizerTest {
    private val normalizer = RomanNumeralNormalizer()

    @Test
    fun titleLabelsDisambiguateRomanNumerals() {
        val cases =
            listOf(
                "Chapter I" to "Chapter one",
                "Chapter IV" to "Chapter four",
                "Book V" to "Book five",
                "Part IX" to "Part nine",
                "Volume XL" to "Volume forty",
                "Vol. XC" to "Vol. ninety",
                "Section CD" to "Section four hundred",
                "Act CM" to "Act nine hundred",
                "Scene M" to "Scene one thousand",
                "Appendix MMXX" to "Appendix two thousand twenty",
                "chapter iv" to "chapter four",
                "Chapter xii" to "Chapter twelve",
            )
        cases.forEach { (source, expected) ->
            assertEquals(source, expected, normalizer.apply(source))
        }
    }

    @Test
    fun knownRegnalNamesTakeOrdinalReadings() {
        val cases =
            listOf(
                "Henry VIII" to "Henry the Eighth",
                "Elizabeth II" to "Elizabeth the Second",
                "Louis XIV" to "Louis the Fourteenth",
                "Henry viii" to "Henry the Eighth",
            )
        cases.forEach { (source, expected) ->
            assertEquals(source, expected, normalizer.apply(source))
        }
    }

    @Test
    fun ambiguousRomanShapedWordsAndInitialismsAreLeftAlone() {
        val cases =
            listOf(
                "I think",
                "a V-shaped valley",
                "Vitamin C",
                "X-ray",
                "50 M",
                "CD player",
                "the MI5",
                "MD",
                "MI",
                "DC",
                "V for Vendetta",
                "I",
                "V",
                "X",
                "M",
                "L",
                "D",
                "C",
                "XIV",
                "MMXX",
            )
        cases.forEach { source ->
            assertEquals(source, source, normalizer.apply(source))
        }
    }

    @Test
    fun malformedRomanStringsAreNotExpandedEvenInStrongContext() {
        val cases = listOf("Chapter IC", "Chapter VX", "Chapter IIII", "Chapter MMMM")
        cases.forEach { source ->
            assertEquals(source, source, normalizer.apply(source))
        }
        assertEquals("Chapter V-shaped", normalizer.apply("Chapter V-shaped"))
        assertEquals("Henry V-shaped", normalizer.apply("Henry V-shaped"))
    }
}
