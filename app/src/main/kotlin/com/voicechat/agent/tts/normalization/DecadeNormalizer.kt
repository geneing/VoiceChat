package com.voicechat.agent.tts.normalization

/**
 * Spells decades: `1990s`, `'80s`, `the 80s`.
 *
 * Reads as "nineteen nineties" / "eighties". A four-digit decade must look
 * like a real year group (`1990`, not an arbitrary `1230s`), and any explicit
 * leading apostrophe is consumed with the rest of the match.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class DecadeNormalizer : TextNormalizationRule {
    private val withApostrophe =
        Regex(
            "(?<![\\p{L}\\p{N}])" +
                "'(\\d{2})s" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    private val fourDigit =
        Regex(
            "(?<![\\p{L}\\p{N}'\\d])" +
                "(1\\d{3}|20\\d{2})s" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    private val plain =
        Regex(
            "(?<![\\p{L}\\p{N}'\\d])" +
                "([2-9]0)s" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    override fun apply(text: String): String {
        var result =
            withApostrophe.replace(text) { match ->
                NumberSpeller.decade(1900 + match.groupValues[1].toInt())
            }
        result =
            fourDigit.replace(result) { match ->
                NumberSpeller.decade(match.value.dropLast(1).toInt())
            }
        return plain.replace(result) { match ->
            NumberSpeller.decade(match.groupValues[1].toInt())
        }
    }
}
