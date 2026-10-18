package com.voicechat.agent.tts.normalization

/**
 * Spells symbols and number-adjacent signs: `&`, `+`, `=`, `©`, a leading
 * phone `+`, and repeated abbreviation periods.
 *
 * `%`, `°` and `x`/`×` are handled where their meaning depends on a preceding
 * number ([PercentageNormalizer], [UnitAndSymbolNormalizer]). A period left
 * between single letters (`e.g` -> `eg`, `a.m` -> `am`) is only collapsed when
 * every letter around it is lower case, so the sentence-ending period of
 * "He saw Dr. X." stays put.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class SymbolNormalizer : TextNormalizationRule {
    /** A lower-case abbreviation chain left without its first period. */
    private val abbreviationPeriod = Regex("(?<=[a-z])\\.(?=[a-zA-Z])")

    override fun apply(text: String): String {
        var result = text
        result = result.replace(abbreviationPeriod, "")
        for ((symbol, words) in SYMBOLS) {
            result = result.replace(symbol, words)
        }
        return NUMBER.replace(result) { match -> NumberSpeller.quantity(match.value) }
    }

    private companion object {
        /** A numeral left between the symbol words and the lexicon. */
        private val NUMBER =
            Regex(
                "(?<![\\p{L}\\p{N}-])(\\d{1,3}(?:,\\d{3})*(?:\\.\\d+)?)(?![\\p{L}\\p{N}])",
            )

        /** Applied in this order because some values contain other symbols. */
        private val SYMBOLS =
            listOf(
                "©" to "copyright",
                "®" to "registered trademark",
                "™" to "trademark",
                "&" to " and ",
                "=" to " equals ",
                "+" to " plus ",
                "±" to " plus or minus ",
                "÷" to " divided by ",
            )
    }
}
