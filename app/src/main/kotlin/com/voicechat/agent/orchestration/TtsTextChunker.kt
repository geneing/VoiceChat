package com.voicechat.agent.orchestration

/**
 * Splits streamed assistant text into **complete** chunks suitable for TTS.
 *
 * Incremental LLM deltas arrive at arbitrary boundaries ("Hel", "lo wor",
 * "ld."), so handing each delta to [com.voicechat.agent.contracts.TextToSpeech]
 * would speak fragments. The chunker buffers deltas and emits a chunk only at a
 * sentence boundary or when the buffered text reaches [maxCharacters], and
 * flushes the trailing remainder once the stream ends. It is a pure function of
 * the calls, so a test can assert exact chunk boundaries.
 *
 * A chunk is never emitted with blank text, and every emitted character is kept
 * exactly once, so concatenating all chunks reproduces the generated text.
 */
class TtsTextChunker(
    private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
) {
    init {
        require(maxCharacters >= MIN_CHARACTERS) { "maxCharacters must be at least $MIN_CHARACTERS" }
    }

    private val buffer = StringBuilder()

    /** Appends [text] and returns any complete chunks it completed. */
    fun append(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        buffer.append(text)
        val chunks = mutableListOf<String>()
        while (true) {
            val boundary = nextBoundary() ?: break
            val chunk = buffer.substring(0, boundary)
            if (chunk.isNotBlank()) chunks += chunk
            buffer.delete(0, boundary)
        }
        return chunks
    }

    /**
     * Returns the remaining buffered text (trimmed) and clears the buffer.
     *
     * Call once the provider stream ended, so the final fragment is still spoken.
     */
    fun flush(): String? {
        val remainder = buffer.toString()
        buffer.setLength(0)
        return remainder.trim().takeIf { it.isNotEmpty() }
    }

    /** Drops any buffered text without emitting it. */
    fun reset() {
        buffer.setLength(0)
    }

    /** Offset of the next chunk boundary, or `null` when no complete chunk exists. */
    private fun nextBoundary(): Int? {
        val sentenceEnd = indexOfSentenceEnd()
        if (sentenceEnd != null) return sentenceEnd
        if (buffer.length >= maxCharacters) {
            // Prefer a whitespace break near the cap; fall back to a hard split so
            // a long unbroken token is still spoken.
            val whitespace = buffer.lastIndexOf(' ', startIndex = maxCharacters - 1)
            return if (whitespace > 0) whitespace + 1 else maxCharacters
        }
        return null
    }

    private fun indexOfSentenceEnd(): Int? {
        var index = 0
        while (index < buffer.length) {
            if (buffer[index] in SENTENCE_TERMINATORS) {
                // Include trailing whitespace so chunks read naturally.
                var end = index + 1
                while (end < buffer.length && buffer[end] == ' ') end++
                return end
            }
            index++
        }
        return null
    }

    companion object {
        /** Default cap before a chunk is emitted without a sentence boundary. */
        const val DEFAULT_MAX_CHARACTERS: Int = 160

        /** Smallest allowed cap: below this a chunk could not hold a sentence. */
        const val MIN_CHARACTERS: Int = 16

        private val SENTENCE_TERMINATORS = setOf('.', '!', '?', '\n', '。', '！', '？')
    }
}
