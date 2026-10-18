package com.voicechat.agent.tts.normalization

/**
 * Handles measurements and unit abbreviations: `5 km`, `6 ft.`, `70 mph`,
 * `20 °C`, `10 kg`, `3 in.`, `25°`, and the multiplication sign (`2 x 3`).
 *
 * A unit is only spelled when a number precedes it, which is why `ft.` and
 * `in.` are absent from [AbbreviationNormalizer]'s list. `x`/`×` between two
 * numbers is multiplication. The degree sign always means degrees, including
 * bare angles (`25°`). Units that share a symbol (`m` metres vs miles) resolve
 * to the SI reading; the numeral itself is left for [NumberNormalizer].
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class UnitAndSymbolNormalizer : TextNormalizationRule {
    /**
     * Unit symbols ordered longest-first so `km/h` wins over `km` and `min`
     * over `m`, matched directly after the numeral (spacing optional). The
     * optional whitespace is consumed with the match; the replacement inserts
     * the separator between the spoken number and unit name.
     */
    private val measurement =
        Regex(
            "(?<![\\p{L}\\p{N}.])" +
                "(\\d+(?:[.,]\\d+)?|\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?)" +
                "[ \\u00A0]?" +
                "(" +
                UNITS.keys
                    .sortedByDescending { it.length }
                    .joinToString("|") { Regex.escape(it) } +
                ")" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    private val multiplication =
        Regex(
            "(?<![\\p{L}\\p{N}.])" +
                "(\\d+(?:\\.\\d+)?)" +
                "\\s?[x×]\\s?" +
                "(\\d+(?:\\.\\d+)?)" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    override fun apply(text: String): String {
        val multiplied =
            multiplication.replace(text) { match ->
                "${NumberSpeller.quantity(match.groupValues[1])} times " +
                    NumberSpeller.quantity(match.groupValues[2])
            }
        return measurement.replace(multiplied) { match ->
            val unit = UNITS.getValue(match.groupValues[2].lowercase())
            val symbol = match.groupValues[2].lowercase()
            if (symbol == "in" && isFollowedByNumber(multiplied, match.range.last + 1)) {
                match.value
            } else {
                "${NumberSpeller.quantity(match.groupValues[1])} $unit"
            }
        }
    }

    /** `in` is the preposition, not inches, when another numeral follows it. */
    private fun isFollowedByNumber(
        text: String,
        index: Int,
    ): Boolean {
        val remainder = text.substring(index).dropWhile(Char::isWhitespace)
        return remainder.firstOrNull()?.isDigit() == true
    }

    private companion object {
        /**
         * Symbols that are unambiguous on their own, plus common short units.
         * Short units are accepted immediately after a numeral (with at most
         * one space); for `in`, a following numeral disambiguates the prose
         * preposition ("in 1999").
         */
        private val UNITS =
            mapOf(
                "km/h" to "kilometers per hour",
                "mph" to "miles per hour",
                "kph" to "kilometers per hour",
                "km" to "kilometers",
                "kg" to "kilograms",
                "mg" to "milligrams",
                "ml" to "milliliters",
                "cm" to "centimeters",
                "mm" to "millimeters",
                "ft" to "feet",
                "yd" to "yards",
                "mi" to "miles",
                "lb" to "pounds",
                "oz" to "ounces",
                "°c" to "degrees Celsius",
                "°f" to "degrees Fahrenheit",
                "°" to "degrees",
                "min" to "minutes",
                "sec" to "seconds",
                "m" to "meters",
                "in" to "inches",
                "l" to "liters",
                "g" to "grams",
                "h" to "hours",
            )
    }
}
