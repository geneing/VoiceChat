package com.voicechat.agent.tts.normalization

/**
 * Spells fractions: `3/4` -> "three quarters", `1/2` -> "one half", `3/10` ->
 * "three tenths".
 *
 * A numeric fraction is built from a cardinal numerator and a pluralized
 * ordinal denominator, then the irregular denominators are corrected so the
 * result is "quarter"/"half" rather than "fourth"/"second".
 * [NumericDateNormalizer] claims slashed dates such as `02/03/2007` first.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class FractionNormalizer : TextNormalizationRule {
    private val fraction =
        Regex(
            "(?<![\\p{L}\\p{N}/.])" +
                "(\\d{1,3})\\s*/\\s*(\\d{1,3})" +
                "(?![\\p{L}\\p{N}/])",
        )

    /**
     * Corrects the irregular denominators that a pluralized ordinal would
     * otherwise spell wrong: "second" -> "half", "fourth" -> "quarter". The
     * numerator is recognised as a standalone word so `three quarters` is
     * rewritten without touching the surrounding words.
     */
    private val irregularDenominator =
        Regex(
            "(?<![\\p{L}'’])" +
                "(one|two|three|four|five|six|seven|eight|nine|ten|eleven|twelve" +
                "|twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)" +
                "\\s+" +
                "(second|fourth)s" +
                "(?![\\p{L}'’])",
            RegexOption.IGNORE_CASE,
        )

    override fun apply(text: String): String {
        val spelled =
            fraction.replace(text) { match ->
                val numerator = match.groupValues[1].toInt()
                val denominator = match.groupValues[2].toInt()
                if (denominator == 0) {
                    match.value
                } else {
                    val numeratorWord = NumberSpeller.cardinal(numerator.toLong())
                    val denominatorWord = pluralize(NumberSpeller.ordinal(denominator.toLong()))
                    "$numeratorWord $denominatorWord"
                }
            }
        return irregularDenominator.replace(spelled) { match ->
            val numerator = match.groupValues[1].lowercase()
            val singular = numerator == "one"
            val denominator =
                when (match.groupValues[2].lowercase()) {
                    "second" -> "half"
                    else -> "quarter"
                }
            val plural = if (singular) denominator else "${denominator}s"
            "$numerator $plural"
        }
    }

    private fun pluralize(word: String): String = if (word.endsWith("f")) word.dropLast(1) + "ves" else word + "s"
}
