package com.voicechat.agent.tts.normalization

/**
 * Spells number ranges: `3-5`, `1990-2000`, `1,000-2,000`, `3 - 5`.
 *
 * Produces "three to five" / "nineteen ninety to two thousand". The open and
 * close values are captured once each, in a single pass, so spacing around the
 * dash does not matter.
 *
 * The dash is limited to the hyphen and en dash; a minus sign is a different
 * character, which keeps a genuine negative (`-5`) in [NumberNormalizer]. The
 * lookbehind also rejects a preceding hyphen so a longer expression is never
 * picked up mid-token.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class RangeNormalizer : TextNormalizationRule {
    private val punctuated =
        Regex(
            "(?<![\\p{L}\\p{N}-])" +
                "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)" +
                "\\s*[-\u2013]\\s*" +
                "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)" +
                "(?![\\p{L}\\p{N}])",
        )

    override fun apply(text: String): String =
        punctuated.replace(text) { match ->
            "${NumberSpeller.spellToken(match.groupValues[1], NumberSpeller.Reading.YEAR)} to " +
                NumberSpeller.spellToken(match.groupValues[2], NumberSpeller.Reading.YEAR)
        }

    /** Whether [text] is entirely a numeral range this rule would spell. */
    fun matches(text: String): Boolean = punctuated.matches(text.trim())
}
