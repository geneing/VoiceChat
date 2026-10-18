package com.voicechat.agent.tts.normalization

/**
 * Spells the spaces of a number: `1.2.3` -> "one point two point three".
 *
 * [DigitGroupNormalizer] first turns the groups into words ("1.2.3" becomes
 * "one.two.three"), and the dotted number's own separators are removed here so
 * the components are not read as sentence boundaries. A period that actually
 * ends a sentence is untouched.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class DottedNumberNormalizer : TextNormalizationRule {
    override fun apply(text: String): String =
        dotted.replace(text) { match ->
            match.value.replace('.', ' ')
        }

    private companion object {
        /** A period between two number words, e.g. the `one.two.three` left by [DigitGroupNormalizer]. */
        private val dotted =
            Regex(
                "(?<![\\p{L}])\\d*" +
                    "(?:one|two|three|four|five|six|seven|eight|nine|zero)" +
                    "(?:\\.(?:one|two|three|four|five|six|seven|eight|nine|zero))+" +
                    "(?!\\p{L})",
            )
    }
}
