package com.voicechat.agent.tts.normalization

/**
 * Cleans up characters before any semantic rule runs: decodes HTML entities,
 * normalizes Unicode whitespace and zero-width/control characters, converts
 * smart quotes, dashes and ellipsis to ASCII, repairs line-break hyphenation,
 * and collapses repeated sentence punctuation.
 *
 * Must run first: the number/symbol rules assume ASCII punctuation and a
 * single space between words.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class HygieneNormalizer : TextNormalizationRule {
    override fun apply(text: String): String {
        // Repair end-of-line hyphenation before newlines become spaces.
        val dehyphenated = LINE_BREAK_HYPHEN.replace(text, "$1$2")
        val decoded = ENTITY.replace(dehyphenated) { decodeEntity(it) }

        val normalized = StringBuilder(decoded.length)
        for (character in decoded) {
            when {
                character.isUnicodeSpace() -> normalized.append(' ')
                character.isInvisible() || character.isISOControl() -> Unit
                character in SMART_SINGLE -> normalized.append('\'')
                character in SMART_DOUBLE -> normalized.append('"')
                character in ASCII_DASHES -> normalized.append('-')
                character in SPACED_DASHES -> normalized.append(" - ")
                character == '\u2026' -> normalized.append("...")
                else -> normalized.append(character)
            }
        }

        var result = normalized.toString()
        result = REPEATED_QUESTION.replace(result, "$1")
        result = REPEATED_DOT.replace(result, "...")
        return result
    }

    private fun Char.isUnicodeSpace(): Boolean =
        isWhitespace() || this == '\u00A0' || this == '\u2007' || this == '\u2009' ||
            this == '\u200A' || this == '\u202F' || this == '\u3000'

    private fun Char.isInvisible(): Boolean =
        this == '\u200B' || this == '\u200C' || this == '\u200D' || this == '\u2060' ||
            this == '\uFEFF'

    private fun decodeEntity(match: MatchResult): String {
        val body = match.groupValues[1]
        if (body.startsWith("#")) {
            val code =
                if (body.length > 1 && (body[1] == 'x' || body[1] == 'X')) {
                    body.substring(2).toIntOrNull(16)
                } else {
                    body.substring(1).toIntOrNull()
                }
            return code
                ?.takeIf { it in 1..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
        return NAMED_ENTITIES[body] ?: NAMED_ENTITIES[body.lowercase()] ?: match.value
    }

    private companion object {
        private val LINE_BREAK_HYPHEN = Regex("(\\p{L})-\\s*\\n\\s*(\\p{L})")
        private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9A-Fa-f]+|[A-Za-z][A-Za-z0-9]*);")
        private val REPEATED_QUESTION = Regex("([!?])\\1+")
        private val REPEATED_DOT = Regex("\\.{4,}")

        private val SMART_SINGLE = setOf('\u2018', '\u2019', '\u201A', '\u201B', '\u2032')
        private val SMART_DOUBLE = setOf('\u201C', '\u201D', '\u201E', '\u201F', '\u2033')
        private val ASCII_DASHES = setOf('\u2012', '\u2013', '\u2212')
        private val SPACED_DASHES = setOf('\u2014', '\u2015')

        private val NAMED_ENTITIES =
            mapOf(
                "amp" to "&",
                "lt" to "<",
                "gt" to ">",
                "quot" to "\"",
                "apos" to "'",
                "nbsp" to " ",
                "ensp" to " ",
                "emsp" to " ",
                "thinsp" to " ",
                "mdash" to " - ",
                "ndash" to "-",
                "hellip" to "...",
                "lsquo" to "'",
                "rsquo" to "'",
                "ldquo" to "\"",
                "rdquo" to "\"",
                "copy" to "\u00A9",
                "reg" to "\u00AE",
                "trade" to "\u2122",
                "deg" to "\u00B0",
                "times" to "\u00D7",
                "divide" to "\u00F7",
                "plusmn" to "\u00B1",
                "pound" to "\u00A3",
                "euro" to "\u20AC",
                "cent" to "\u00A2",
                "yen" to "\u00A5",
                "sect" to "\u00A7",
            )
    }
}
