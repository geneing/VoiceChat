package com.voicechat.agent.stt.parakeet

/**
 * The Parakeet sentencepiece vocabulary (stream 1).
 *
 * The bundle ships a flat `vocab.json` mapping token id → word piece, where the
 * sentencepiece word boundary `▁` (U+2581) marks a leading space. Ids 0..1023
 * are word pieces, 1024 is `<EOU>`, and 1025 is `<EOB>`; the blank token 1026 has
 * no vocabulary entry. [decode] joins the pieces, turns each `▁` into a space,
 * and trims the result, matching `decode_tokens` in
 * `scripts/parakeet-eou-host.py`.
 *
 * The loader and decoder are pure Kotlin: no `android.*`, no JSON library, no
 * ONNX type. [count] is the number of entries actually present, so callers can
 * assert that a bundle's vocabulary matches `config.vocabSize + 2` special
 * entries rather than trusting the file.
 */
class ParakeetVocabulary private constructor(
    private val pieces: Map<Int, String>,
) {
    /** Number of token entries loaded from `vocab.json`. */
    val count: Int get() = pieces.size

    /** The piece for [id], or `null` when the id has no entry (for example blank). */
    fun piece(id: Int): String? = pieces[id]

    /**
     * Joins the pieces for [ids] into text.
     *
     * Unknown ids are skipped, `▁` becomes a space, and the joined string is
     * trimmed — exactly the host's `decode_tokens`.
     */
    fun decode(ids: List<Int>): String {
        val builder = StringBuilder()
        for (id in ids) {
            builder.append(pieces[id] ?: continue)
        }
        return builder.toString().replace(WORD_BOUNDARY, ' ').trim()
    }

    companion object {
        /** sentencepiece word-boundary marker: U+2581. */
        const val WORD_BOUNDARY: Char = '\u2581'

        /** Parses the bundle's flat `vocab.json` (`{"<id>": "<piece>", ...}`). */
        fun fromJson(text: String): ParakeetVocabulary {
            val root = parseParakeetJsonObject(text)
            val result = LinkedHashMap<Int, String>(root.size)
            for ((key, value) in root) {
                val id =
                    key.toIntOrNull()
                        ?: throw IllegalArgumentException("vocab.json key '$key' is not an integer id")
                val piece =
                    (value as? ParakeetJson.Str)?.value
                        ?: throw IllegalArgumentException("vocab.json entry '$key' is not a string")
                result[id] = piece
            }
            require(result.isNotEmpty()) { "vocab.json contains no entries" }
            return ParakeetVocabulary(result)
        }

        /** Builds a vocabulary directly, for tests and fakes. */
        fun of(pieces: Map<Int, String>): ParakeetVocabulary = ParakeetVocabulary(LinkedHashMap(pieces))

        /**
         * The expected number of vocabulary entries for [config]: every word piece
         * plus the two special tokens, i.e. `vocabSize + 2`.
         */
        fun expectedCount(config: ParakeetEouConfig): Int = config.vocabSize + 2
    }
}

/**
 * A tiny, dependency-free JSON value used by the Parakeet config and vocabulary
 * loaders. Pure Kotlin keeps the package free of `kotlinx.serialization`/`android`
 * types, which the source-purity test enforces.
 */
internal sealed interface ParakeetJson {
    /** A JSON object, insertion-ordered. */
    data class Obj(
        val fields: Map<String, ParakeetJson>,
    ) : ParakeetJson

    /** A JSON number. */
    data class Num(
        val value: Double,
    ) : ParakeetJson

    /** A JSON string. */
    data class Str(
        val value: String,
    ) : ParakeetJson

    /** A JSON boolean. */
    data class Bool(
        val value: Boolean,
    ) : ParakeetJson

    /** A JSON array. */
    data class Arr(
        val items: List<ParakeetJson>,
    ) : ParakeetJson

    /** JSON `null`. */
    data object Null : ParakeetJson
}

/**
 * Parses a JSON **object** into its field map. Only the object/string/number
 * subset the Parakeet files use is modelled in depth; arrays and nested values
 * are parsed structurally and returned so they can be navigated or ignored.
 *
 * @throws IllegalArgumentException on malformed input.
 */
internal fun parseParakeetJsonObject(text: String): Map<String, ParakeetJson> {
    val parser = ParakeetJsonParser(text)
    val value = parser.parseValue()
    parser.skipWhitespace()
    require(parser.atEnd()) { "unexpected trailing content in JSON at offset ${parser.offset}" }
    return (value as? ParakeetJson.Obj)?.fields
        ?: throw IllegalArgumentException("expected a JSON object at the top level")
}

private class ParakeetJsonParser(
    private val text: String,
) {
    var offset: Int = 0
        private set

    fun atEnd(): Boolean = offset >= text.length

    fun skipWhitespace() {
        while (offset < text.length && text[offset].isWhitespace()) offset++
    }

    fun parseValue(): ParakeetJson {
        skipWhitespace()
        require(offset < text.length) { "unexpected end of JSON" }
        return when (val c = text[offset]) {
            '{' -> {
                parseObject()
            }

            '[' -> {
                ParakeetJson.Arr(parseArray())
            }

            '"' -> {
                ParakeetJson.Str(parseString())
            }

            't' -> {
                parseLiteral("true", ParakeetJson.Bool(true))
            }

            'f' -> {
                parseLiteral("false", ParakeetJson.Bool(false))
            }

            'n' -> {
                parseLiteral("null", ParakeetJson.Null)
            }

            else -> {
                if (c == '-' || c.isDigit()) {
                    ParakeetJson.Num(parseNumber())
                } else {
                    throw IllegalArgumentException("unexpected character '$c' in JSON at offset $offset")
                }
            }
        }
    }

    private fun parseObject(): ParakeetJson.Obj {
        expect('{')
        val fields = LinkedHashMap<String, ParakeetJson>()
        skipWhitespace()
        if (peek() == '}') {
            offset++
            return ParakeetJson.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                '}' -> return ParakeetJson.Obj(fields)
                else -> throw IllegalArgumentException("expected ',' or '}' in JSON at offset ${offset - 1}, was '$c'")
            }
        }
    }

    private fun parseArray(): List<ParakeetJson> {
        expect('[')
        val items = mutableListOf<ParakeetJson>()
        skipWhitespace()
        if (peek() == ']') {
            offset++
            return items
        }
        while (true) {
            items += parseValue()
            skipWhitespace()
            when (val c = next()) {
                ',' -> continue
                ']' -> return items
                else -> throw IllegalArgumentException("expected ',' or ']' in JSON at offset ${offset - 1}, was '$c'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val builder = StringBuilder()
        while (true) {
            require(offset < text.length) { "unterminated JSON string" }
            when (val c = text[offset++]) {
                '"' -> return builder.toString()
                '\\' -> builder.append(parseEscape())
                else -> builder.append(c)
            }
        }
    }

    private fun parseEscape(): Char {
        require(offset < text.length) { "unterminated JSON escape" }
        return when (val c = text[offset++]) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> parseUnicodeEscape()
            else -> throw IllegalArgumentException("invalid JSON escape '\\$c' at offset ${offset - 1}")
        }
    }

    private fun parseUnicodeEscape(): Char {
        require(offset + 4 <= text.length) { "truncated JSON unicode escape at offset $offset" }
        val hex = text.substring(offset, offset + 4)
        offset += 4
        return hex.toIntOrNull(16)?.toChar()
            ?: throw IllegalArgumentException("invalid JSON unicode escape '\\u$hex'")
    }

    private fun parseNumber(): Double {
        val start = offset
        if (peek() == '-') offset++
        while (offset < text.length && (text[offset].isDigit() || text[offset] in "+-.eE")) offset++
        val token = text.substring(start, offset)
        return token.toDoubleOrNull()
            ?: throw IllegalArgumentException("invalid JSON number '$token' at offset $start")
    }

    private fun parseLiteral(
        literal: String,
        value: ParakeetJson,
    ): ParakeetJson {
        require(text.startsWith(literal, offset)) { "invalid JSON literal at offset $offset" }
        offset += literal.length
        return value
    }

    private fun expect(c: Char) {
        skipWhitespace()
        require(offset < text.length && text[offset] == c) {
            "expected '$c' in JSON at offset $offset"
        }
        offset++
    }

    private fun peek(): Char? = if (offset < text.length) text[offset] else null

    private fun next(): Char {
        require(offset < text.length) { "unexpected end of JSON" }
        return text[offset++]
    }
}
