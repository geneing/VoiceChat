package com.voicechat.agent.tts.normalization

/**
 * Splits a sentence into any non-prose spans it contains: inline code, URLs and
 * email addresses.
 *
 * These spans are spoken as sequences of letters and symbols rather than as
 * words, e.g. `user@example.com` becomes "u s e r at e x a m p l e dot c o m". The
 * scanner walks the text and only hands the prose between the spans to the rest
 * of the pipeline, so a version number inside a URL is never read as a
 * quantity. The remainder of the sentence is left for the other rules.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class NonProseNormalizer : TextNormalizationRule {
    override fun apply(text: String): String {
        val output = StringBuilder(text.length)
        val prose = StringBuilder()

        fun flushProse() {
            if (prose.isNotEmpty()) {
                output.append(prose)
                prose.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val span = spanAt(text, index)
            if (span != null) {
                flushProse()
                output.append(spellSpan(span))
                index += span.length
            } else {
                prose.append(text[index])
                index++
            }
        }
        flushProse()
        return output.toString()
    }

    /** The longest non-prose span starting at [index], or null for prose. */
    private fun spanAt(
        text: String,
        index: Int,
    ): String? {
        for (pattern in PATTERNS) {
            val match = pattern.matchAt(text, index) ?: continue
            val value = match.value
            if (value.isNotBlank()) return value
        }
        return null
    }

    private fun spellSpan(span: String): String =
        buildString(span.length * 2) {
            fun appendWord(word: String) {
                if (isNotEmpty() && last() != ' ') append(' ')
                append(word)
            }
            for (character in span) {
                when {
                    character.isLetter() -> {
                        if (isNotEmpty() && last() != ' ') append(' ')
                        append(character.lowercaseChar())
                    }

                    character.isDigit() -> {
                        if (isNotEmpty() && last() != ' ') append(' ')
                        append(character)
                    }

                    character == '@' -> {
                        appendWord("at")
                    }

                    character == '.' -> {
                        appendWord("dot")
                    }

                    character == '/' -> {
                        appendWord("slash")
                    }

                    character == ':' -> {
                        appendWord("colon")
                    }

                    character == '-' -> {
                        appendWord("dash")
                    }

                    character == '_' -> {
                        appendWord("underscore")
                    }

                    character == '&' -> {
                        appendWord("and")
                    }

                    character == '=' -> {
                        appendWord("equals")
                    }

                    character == '+' -> {
                        appendWord("plus")
                    }

                    character == '#' -> {
                        appendWord("hash")
                    }

                    character == '~' -> {
                        appendWord("tilde")
                    }

                    character == '|' -> {
                        appendWord("pipe")
                    }

                    character == '?' -> {
                        appendWord("question mark")
                    }

                    character == '`' -> {
                        Unit
                    }

                    // Inline-code delimiter is silent.
                    character.isWhitespace() -> {
                        Unit
                    }

                    else -> {
                        if (isNotEmpty() && last() != ' ') append(' ')
                        append(character)
                    }
                }
            }
            trimEnd()
        }

    private companion object {
        /** Ordered by the span kind that should win at a given position. */
        private val PATTERNS =
            listOf(
                Regex("`[^`\\n]+`"),
                Regex("https?://\\S+", RegexOption.IGNORE_CASE),
                Regex("www\\.\\S+", RegexOption.IGNORE_CASE),
                Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
                // A bare domain such as `example.com` or `example.com/path`. The
                // name and each label must start with a letter and the TLD must be
                // at least two letters, so `1,234.50` and `a.m` are never mistaken
                // for hosts.
                Regex(
                    "(?<![\\p{L}\\p{N}])" +
                        "[A-Za-z][A-Za-z0-9\\-]*" +
                        "(?:\\.[A-Za-z][A-Za-z0-9\\-]*)*" +
                        "\\.[A-Za-z]{2,}" +
                        "(?:/[\\w\\-./?#%&=+~]*)?",
                ),
            )
    }
}
