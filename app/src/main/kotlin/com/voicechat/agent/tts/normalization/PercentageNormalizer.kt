package com.voicechat.agent.tts.normalization

/**
 * Spells percentages: `50%`, `3.5 %`, `1500%`.
 *
 * Multiplies through to the same numeral reading, so a year-like `1500%` is
 * read as "one thousand five hundred percent" rather than "fifteen hundred".
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class PercentageNormalizer : TextNormalizationRule {
    private val percentage =
        Regex(
            "(?<![\\p{L}\\p{N}])" +
                "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+(?:\\.\\d+)?)" +
                "\\s?%" +
                "(?![\\p{L}\\p{N}])",
        )

    override fun apply(text: String): String =
        percentage.replace(text) { match ->
            "${NumberSpeller.quantity(match.groupValues[1])} percent"
        }
}
