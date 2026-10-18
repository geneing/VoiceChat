package com.voicechat.agent.tts.normalization

/**
 * Converts bare Arabic numerals to spoken words.
 *
 * Handles comma-grouped thousands (`1,234,567`), decimal points (`3.14`) and
 * four-digit years (`1999`, `2005`). Negative values keep their sign and are
 * spoken as "minus ...." A hyphen that follows a digit is never treated as a
 * sign, so `3-5` reaches [RangeNormalizer] instead. Numbers attached to
 * letters on both sides (`3D`) are left alone. Structured numeric forms
 * (currency, percentages, ordinals, dates, phones, units, ranges, versions)
 * are consumed by earlier rules in the pipeline; this is the catch-all for
 * what remains.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class NumberNormalizer : TextNormalizationRule {
    private val token =
        Regex(
            "(?<![\\p{L}\\p{N}-])" +
                "(?:" +
                "-?\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?" + "|" + // 1,234 / 1,234.5
                "-?\\d+\\.\\d+" + "|" + // 3.14
                "-?\\d+" + // 42
                ")" +
                "(?![\\p{L}\\p{N}])",
        )

    override fun apply(text: String): String = token.replace(text) { match -> spell(match.value) }

    private fun spell(raw: String): String {
        var token = raw
        val negative = token.startsWith('-')
        if (negative) token = token.substring(1)
        val spelled = NumberSpeller.spellToken(token, NumberSpeller.Reading.YEAR)
        return if (negative) "minus $spelled" else spelled
    }
}
