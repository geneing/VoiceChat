package com.voicechat.agent.tts.normalization

/**
 * Spells currency amounts: `$5`, `$1,234.56`, `£9`, `€20`, `5 dollars`, `3
 * euros`, `50 cents`, `£3.50`.
 *
 * Symbol and code forms become a spoken amount; an explicit word currency
 * (`5 dollars`) only needs its numeral spelled out. Cents are spoken as a
 * fraction of the major unit ("three pounds and fifty pence"). Amounts are
 * deliberately not treated as years, so `$1500` reads "one thousand five
 * hundred dollars".
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class CurrencyNormalizer : TextNormalizationRule {
    private val symbol =
        Regex(
            "([$£€¥₹])" +
                "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)" +
                "(?![\\p{L}\\p{N}])",
        )

    private val code =
        Regex(
            "(USD|EUR|GBP|JPY|CAD|AUD)\\s?" +
                "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d{1,2})?|\\d+(?:\\.\\d{1,2})?)" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    private val wordCurrency =
        Regex(
            "(\\d{1,3}(?:,\\d{3})*(?:\\.\\d{1,2})?)" +
                "\\s+(dollars?|euros?|pounds?|cents?|pence|yen)",
            RegexOption.IGNORE_CASE,
        )

    override fun apply(text: String): String {
        var result =
            symbol.replace(text) { match ->
                amount(match.groupValues[2], SYMBOLS.getValue(match.groupValues[1]))
            }
        result =
            code.replace(result) { match ->
                amount(match.groupValues[2], CODES.getValue(match.groupValues[1].uppercase()))
            }
        return wordCurrency.replace(result) { match ->
            "${NumberSpeller.quantity(match.groupValues[1])} ${match.groupValues[2].lowercase()}"
        }
    }

    /**
     * Speaks `raw` (optionally comma-grouped) as a plain quantity with [unit],
     * e.g. "5 km" -> "five kilometers". Unit words carry their own leading
     * space so the concatenation is exact.
     */
    private fun amount(
        raw: String,
        unit: String,
    ): String {
        val dot = raw.indexOf('.')
        if (dot < 0) {
            val whole = raw.replace(",", "").toLongOrNull() ?: return raw
            return NumberSpeller.cardinal(whole) + unit
        }
        val whole = raw.substring(0, dot).replace(",", "").toLongOrNull() ?: return raw
        val fraction = raw.substring(dot + 1)
        val cents = fraction.padEnd(2, '0').toIntOrNull() ?: return raw
        return buildString {
            append(NumberSpeller.cardinal(whole))
            append(unit)
            if (cents > 0) {
                append(" and ")
                append(NumberSpeller.cardinal(cents.toLong()))
                append(COINS.getValue(unit))
            }
        }
    }

    private companion object {
        private val SYMBOLS =
            mapOf(
                "$" to " dollars",
                "£" to " pounds",
                "€" to " euros",
                "¥" to " yen",
                "₹" to " rupees",
            )

        private val CODES =
            mapOf(
                "USD" to " dollars",
                "EUR" to " euros",
                "GBP" to " pounds",
                "JPY" to " yen",
                "CAD" to " Canadian dollars",
                "AUD" to " Australian dollars",
            )

        /** Coin word per major unit; the unit keys include their leading space. */
        private val COINS =
            mapOf(
                " dollars" to " cents",
                " pounds" to " pence",
                " euros" to " cents",
                " yen" to " sen",
                " rupees" to " paise",
                " Canadian dollars" to " cents",
                " Australian dollars" to " cents",
            )
    }
}
