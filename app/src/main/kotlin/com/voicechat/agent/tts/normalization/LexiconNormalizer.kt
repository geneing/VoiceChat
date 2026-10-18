package com.voicechat.agent.tts.normalization

/**
 * Handles initialisms and acronyms, optionally with a user lexicon.
 *
 * A known acronym is replaced by its pronunciation ("NATO" -> "Nato"); an
 * unlisted all-caps word is spelled letter by letter when it looks like an
 * initialism ("WHO" -> "W H O"). A word that contains a vowel is assumed to be
 * pronounceable and left to the engine, and mixed-case words are untouched.
 *
 * [lexicon] holds user pronunciation substitutions, applied afterwards so the
 * user always wins. It is a plain map of written form to spoken form. There is
 * no UI for it yet; this parameter is the extension point for future user
 * pronunciation settings.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class LexiconNormalizer(
    private val lexicon: Map<String, String> = emptyMap(),
) : TextNormalizationRule {
    private val caps = Regex("(?<![\\p{L}\\p{N}'’])([A-Z][A-Z]+)(?![\\p{L}\\p{N}])")

    override fun apply(text: String): String {
        val normalized =
            caps.replace(text) { match ->
                val word = match.value
                when {
                    ACRONYMS.containsKey(word) -> ACRONYMS.getValue(word)

                    word.length > MAX_SPELLED_LENGTH -> word

                    // A vowel (including Y) suggests a pronounceable word.
                    word.any { it in VOWELS } -> word

                    else -> spellInitialism(word)
                }
            }
        if (lexicon.isEmpty()) return normalized

        var result = normalized
        for ((written, spoken) in lexicon) {
            if (written.isBlank()) continue
            result =
                Regex(
                    "(?<![\\p{L}\\p{N}])${Regex.escape(written)}(?![\\p{L}\\p{N}])",
                    RegexOption.IGNORE_CASE,
                ).replace(result, spoken)
        }
        return result
    }

    private fun spellInitialism(word: String): String = word.map { it.toString() }.joinToString(" ")

    private companion object {
        private const val MAX_SPELLED_LENGTH = 6
        private const val VOWELS = "AEIOUY"

        /** Acronyms pronounced as a word (or with an unusual reading). */
        private val ACRONYMS =
            mapOf(
                "NATO" to "Nato",
                "NASA" to "Nasa",
                "UNESCO" to "Unesco",
                "UNICEF" to "Unicef",
                "OPEC" to "Opec",
                "FIFA" to "Fifa",
                "NASDAQ" to "Nasdaq",
                "AIDS" to "Aids",
                "SARS" to "Sars",
                "COVID" to "Covid",
                "LASER" to "laser",
                "RADAR" to "radar",
                "SCUBA" to "scuba",
            )
    }
}
