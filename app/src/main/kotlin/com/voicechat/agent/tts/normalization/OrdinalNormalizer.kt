package com.voicechat.agent.tts.normalization

/**
 * Spells ordinals and fixes ordinals already written out: `21st`, `3rd`,
 * `threefourth`, `onehundredth`, `twelvehundredth`.
 *
 * The punctuation form is claimed by a word-boundary match so `1st` does not
 * fall through to [NumberNormalizer] (which requires a digit run not attached
 * to letters) and is never read as a cardinal.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class OrdinalNormalizer : TextNormalizationRule {
    private val ordinal =
        Regex(
            "(?<![\\p{L}\\p{N}])" +
                "(\\d{1,3}(?:,\\d{3})*)(?:st|nd|rd|th)" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    /**
     * An ordinal already spelled as a word (`threefourth`, `onehundredth`).
     * The enumerated forms are exactly those whose spelling does not follow the
     * cardinal + "th" rule, so a plain `fourth` is left alone and only the
     * irregular ones are corrected.
     */
    private val word =
        Regex(
            "(?<![\\p{L}'’])" +
                "(one|two|three|five|eight|nine|twelve)" +
                "(hundred|thousand|million|billion|trillion)?" +
                "(first|second|third|fifth|eighth|ninth|twelfth)" +
                "(?![\\p{L}'’])",
            RegexOption.IGNORE_CASE,
        )

    override fun apply(text: String): String {
        val corrected =
            word.replace(text) { match ->
                val ordinalWord = match.groupValues[3].lowercase()
                val scale = match.groupValues[2].lowercase()
                if (scale.isEmpty()) ordinalWord else "$scale$ordinalWord"
            }
        return ordinal.replace(corrected) { match ->
            NumberSpeller.ordinal(match.groupValues[1].replace(",", "").toLong())
        }
    }
}
