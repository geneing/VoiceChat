package com.voicechat.agent.tts.normalization

/**
 * Spells Roman numerals only when a nearby title or known regnal name makes
 * their numeric reading sufficiently clear. Roman letters are common words and
 * initialisms, so isolated Roman-shaped tokens are deliberately left alone.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class RomanNumeralNormalizer : TextNormalizationRule {
    private val titledNumeral =
        Regex(
            "(?i)(?<![\\p{L}\\p{N}])" +
                "(Chapter|Book|Part|Volume|Vol\\.?|Section|Act|Scene|Article|Appendix|Psalm|Canto|Lesson)" +
                "(\\s+)([MDCLXVI]+)(?![\\p{L}\\p{N}/-])",
        )

    /**
     * This allowlist prevents capitalized common nouns such as "Vitamin" in
     * "Vitamin C" from acting as a personal-name signal. Add names only where
     * regnal usage is established; title-label context remains open-ended.
     */
    private val regnalNumeral =
        Regex(
            "(?<![\\p{L}\\p{N}])($REGNAL_NAMES)(\\s+)(?i:([MDCLXVI]+))(?![\\p{L}\\p{N}/-])",
        )

    override fun apply(text: String): String {
        val withTitles =
            titledNumeral.replace(text) { match ->
                val value = parseCanonical(match.groupValues[3]) ?: return@replace match.value
                match.groupValues[1] + match.groupValues[2] + NumberSpeller.cardinal(value.toLong())
            }

        return regnalNumeral.replace(withTitles) { match ->
            val value = parseCanonical(match.groupValues[3]) ?: return@replace match.value
            val ordinal = NumberSpeller.ordinal(value.toLong())
            val capitalizedOrdinal = ordinal.replaceFirstChar { it.uppercase() }
            match.groupValues[1] + match.groupValues[2] + "the " + capitalizedOrdinal
        }
    }

    /** Accept standard subtractive notation, rejecting malformed Roman strings. */
    private fun parseCanonical(token: String): Int? {
        val upper = token.uppercase()
        var value = 0
        for (index in upper.indices) {
            val current = symbolValue(upper[index]) ?: return null
            val next = if (index + 1 < upper.length) symbolValue(upper[index + 1]) ?: return null else 0
            value += if (current < next) -current else current
        }
        if (value !in 1..3999 || toRoman(value) != upper) return null
        return value
    }

    private fun symbolValue(symbol: Char): Int? =
        when (symbol) {
            'I' -> 1
            'V' -> 5
            'X' -> 10
            'L' -> 50
            'C' -> 100
            'D' -> 500
            'M' -> 1000
            else -> null
        }

    private fun toRoman(value: Int): String {
        var remaining = value
        return buildString {
            for ((amount, spelling) in ROMAN_VALUES) {
                while (remaining >= amount) {
                    append(spelling)
                    remaining -= amount
                }
            }
        }
    }

    private companion object {
        private const val REGNAL_NAMES =
            "Henry|Elizabeth|Louis|George|Edward|Charles|Richard|James|William|John|Philip|Peter|" +
                "Alexander|Frederick|Alfonso|Ferdinand|Francis|Nicholas|Paul|Leo|Benedict|Gregory|" +
                "Innocent|Pius|Sixtus|Urban|Adrian|Clement|Victor|Stephen|Martin|Constantine|" +
                "Augustus|Titus|Vespasian|Trajan|Hadrian|Antoninus|Marcus|Lucius|Maximilian"

        private val ROMAN_VALUES =
            listOf(
                1000 to "M",
                900 to "CM",
                500 to "D",
                400 to "CD",
                100 to "C",
                90 to "XC",
                50 to "L",
                40 to "XL",
                10 to "X",
                9 to "IX",
                5 to "V",
                4 to "IV",
                1 to "I",
            )
    }
}
