package com.voicechat.agent.tts.kokoro.phonemes

/** Minimal JSON values and reader for the publisher's object-only vocabulary and lexicon assets. */
internal sealed interface PhonemeJsonValue {
    data class ObjectValue(
        val values: Map<String, PhonemeJsonValue>,
    ) : PhonemeJsonValue

    data class ArrayValue(
        val values: List<PhonemeJsonValue>,
    ) : PhonemeJsonValue

    data class StringValue(
        val value: String,
    ) : PhonemeJsonValue

    data class NumberValue(
        val value: String,
    ) : PhonemeJsonValue

    data class BooleanValue(
        val value: Boolean,
    ) : PhonemeJsonValue

    data object NullValue : PhonemeJsonValue
}

/** A transport-independent JSON reader so the phonemizer stays outside the remote JSON boundary. */
internal object PhonemeJson {
    /** Parses one complete JSON document and rejects trailing content. */
    fun parse(source: String): PhonemeJsonValue {
        val reader = Reader(source)
        val value = reader.readValue()
        reader.skipWhitespace()
        require(reader.atEnd()) { "Unexpected content after JSON document" }
        return value
    }

    private class Reader(
        private val source: String,
    ) {
        private var position = 0

        fun atEnd(): Boolean = position == source.length

        fun skipWhitespace() {
            while (!atEnd() && source[position] in JSON_WHITESPACE) position++
        }

        fun readValue(): PhonemeJsonValue {
            skipWhitespace()
            require(!atEnd()) { "Unexpected end of JSON document" }
            return when (source[position]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> PhonemeJsonValue.StringValue(readString())
                't' -> readLiteral("true", PhonemeJsonValue.BooleanValue(true))
                'f' -> readLiteral("false", PhonemeJsonValue.BooleanValue(false))
                'n' -> readLiteral("null", PhonemeJsonValue.NullValue)
                '-', in '0'..'9' -> readNumber()
                else -> error("Invalid JSON value at offset $position")
            }
        }

        private fun readObject(): PhonemeJsonValue.ObjectValue {
            position++
            skipWhitespace()
            val values = linkedMapOf<String, PhonemeJsonValue>()
            if (consume('}')) return PhonemeJsonValue.ObjectValue(values)
            while (true) {
                skipWhitespace()
                require(!atEnd() && source[position] == '"') { "Expected JSON object key at offset $position" }
                val key = readString()
                skipWhitespace()
                require(consume(':')) { "Expected ':' after JSON object key at offset $position" }
                values[key] = readValue()
                skipWhitespace()
                if (consume('}')) return PhonemeJsonValue.ObjectValue(values)
                require(consume(',')) { "Expected ',' between JSON object entries at offset $position" }
            }
        }

        private fun readArray(): PhonemeJsonValue.ArrayValue {
            position++
            skipWhitespace()
            val values = mutableListOf<PhonemeJsonValue>()
            if (consume(']')) return PhonemeJsonValue.ArrayValue(values)
            while (true) {
                values += readValue()
                skipWhitespace()
                if (consume(']')) return PhonemeJsonValue.ArrayValue(values)
                require(consume(',')) { "Expected ',' between JSON array entries at offset $position" }
            }
        }

        private fun readString(): String {
            require(consume('"')) { "Expected JSON string at offset $position" }
            val value = StringBuilder()
            while (!atEnd()) {
                val char = source[position++]
                when {
                    char == '"' -> return value.toString()
                    char == '\\' -> appendEscape(value)
                    char < ' ' -> error("Unescaped control character in JSON string at offset ${position - 1}")
                    else -> value.append(char)
                }
            }
            error("Unterminated JSON string")
        }

        private fun appendEscape(value: StringBuilder) {
            require(!atEnd()) { "Unterminated JSON escape" }
            when (val escaped = source[position++]) {
                '"', '\\', '/' -> {
                    value.append(escaped)
                }

                'b' -> {
                    value.append('\b')
                }

                'f' -> {
                    value.append('\u000C')
                }

                'n' -> {
                    value.append('\n')
                }

                'r' -> {
                    value.append('\r')
                }

                't' -> {
                    value.append('\t')
                }

                'u' -> {
                    require(position + 4 <= source.length) { "Short Unicode escape in JSON string" }
                    val codeUnit = source.substring(position, position + 4).toIntOrNull(16)
                    require(codeUnit != null) { "Invalid Unicode escape in JSON string" }
                    value.append(codeUnit.toChar())
                    position += 4
                }

                else -> {
                    error("Invalid JSON escape '\\$escaped' at offset ${position - 1}")
                }
            }
        }

        private fun readNumber(): PhonemeJsonValue.NumberValue {
            val start = position
            consume('-')
            if (!atEnd() && source[position] == '0') {
                position++
            } else {
                require(readDigits()) { "Invalid JSON number at offset $position" }
            }
            if (consume('.')) require(readDigits()) { "Invalid JSON fraction at offset $position" }
            if (!atEnd() && source[position] in "eE") {
                position++
                if (!atEnd() && source[position] in "+-") position++
                require(readDigits()) { "Invalid JSON exponent at offset $position" }
            }
            return PhonemeJsonValue.NumberValue(source.substring(start, position))
        }

        private fun readDigits(): Boolean {
            val start = position
            while (!atEnd() && source[position] in '0'..'9') position++
            return position > start
        }

        private fun readLiteral(
            literal: String,
            value: PhonemeJsonValue,
        ): PhonemeJsonValue {
            require(source.startsWith(literal, position)) { "Invalid JSON literal at offset $position" }
            position += literal.length
            return value
        }

        private fun consume(char: Char): Boolean {
            if (atEnd() || source[position] != char) return false
            position++
            return true
        }
    }

    private const val JSON_WHITESPACE = " \t\r\n"
}

internal fun PhonemeJsonValue.asObject(): Map<String, PhonemeJsonValue> =
    (this as? PhonemeJsonValue.ObjectValue)?.values ?: error("Expected a JSON object")
