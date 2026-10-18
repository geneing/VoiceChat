package com.voicechat.agent.tts.normalization

/**
 * Rewrites an utterance into the form a speech engine should pronounce.
 *
 * The normalizer runs **after** sentence segmentation and **before** synthesis,
 * so it only ever sees one sentence of source text and never has to preserve
 * offsets: the text the app accounts for as delivered stays the original
 * sentence, and only the string handed to the phonemizer is rewritten. That is
 * why this layer is a plain `String -> String` pipeline rather than something
 * entangled with the chunk plan
 * ([com.voicechat.agent.tts.kokoro.KokoroPhonemeChunk]).
 *
 * Rule order is significant and mirrors the source project:
 *
 * 1. isolate spans that must never reach the prose rules (code, URLs, e-mail);
 * 2. clean up characters and whitespace the rest relies on;
 * 3. claim contextual Roman numerals before abbreviations rewrite labels such
 *    as `Vol.` and before later word rules;
 * 4. claim structured numeric forms (dates, currency, percentages, ordinals,
 *    decades, ranges, times, fractions, digit groups, units) before bare
 *    numerals;
 * 5. whatever is left is a plain numeral;
 * 6. symbol words and word-level rewrites last.
 *
 * Late rules must not re-interpret another rule's emitted spoken text as source
 * input; the combined contract is idempotence,
 * `normalize(normalize(text)) == normalize(text)`.
 *
 * **Extending.** Add a [TextNormalizationRule] to [English] at the intended
 * position. Keep each rule pure, deterministic, and independently unit-tested.
 */
class TextNormalizer internal constructor(
    private val rules: List<TextNormalizationRule>,
) {
    /** Applies every rule in order and returns the spoken form of [text]. */
    fun normalize(text: String): String {
        var result = text
        for (rule in rules) {
            result = rule.apply(result)
        }
        return result
    }

    companion object {
        /** English normalization: the only language the distilled Kokoro voice speaks. */
        val English: TextNormalizer =
            TextNormalizer(
                listOf(
                    listOf(
                        // 1. Isolate spans that must never reach the prose rules.
                        NonProseNormalizer(),
                        // 2. Clean up characters and whitespace the rest relies on.
                        HygieneNormalizer(),
                        // 3. Claim contextual Roman numerals before abbreviations
                        //    rewrite labels such as "Vol." and before later word rules.
                        RomanNumeralNormalizer(),
                    ),
                    // 4. Claim structured numeric forms before bare numerals.
                    DATE_TIME_AND_QUANTITY_RULES,
                    listOf(
                        FractionNormalizer(),
                        DigitGroupNormalizer(),
                        UnitAndSymbolNormalizer(),
                        // 5. Whatever is left is a plain numeral.
                        NumberNormalizer(),
                        DottedNumberNormalizer(),
                        // 6. Symbol words and word-level rewrites last.
                        AbbreviationNormalizer(),
                        SymbolNormalizer(),
                        LexiconNormalizer(),
                    ),
                ).flatten(),
            )

        /**
         * Pass-through normalizer for text this app must not rewrite.
         *
         * It is the honest choice for a caller that needs the *reference* form of
         * the text — the phonemizer's parity fixtures are generated from raw
         * sentences, so the port's fidelity is asserted with this normalizer and
         * the spoken path is asserted separately.
         */
        val Identity: TextNormalizer = TextNormalizer(emptyList())

        /**
         * Picks a normalizer for [languageTag] (a BCP-47 tag such as `en-US`).
         *
         * Unknown or absent tags fall back to English; a non-English tag is passed
         * through unchanged rather than mis-pronounced by English rules.
         */
        fun forLanguageTag(languageTag: String?): TextNormalizer {
            val tag = languageTag?.lowercase() ?: return English
            return if (tag == "en" || tag.startsWith("en-")) English else Identity
        }
    }
}
