package com.voicechat.agent.tts.kokoro.phonemes

import com.voicechat.agent.tts.kokoro.KokoroArtifact

/** Sentence and graph-size splitting from `titl/phonemes.py`. */
class KokoroPhonemeChunker {
    /** Split phonemes by source sentence, then at the last fitting space. */
    fun chunks(
        text: String,
        maxPhonemes: Int = KokoroArtifact.MAX_PHONEMES,
        phonemize: (String) -> String,
    ): List<PhonemeTextChunk> {
        require(maxPhonemes > 0) { "maxPhonemes must be positive" }
        val result = mutableListOf<PhonemeTextChunk>()
        sentenceSpans(text).forEach { sentence ->
            val source = text.substring(sentence.first, sentence.last + 1)
            if (source.isEmpty()) return@forEach
            val pieces = splitPhonemes(phonemize(source.trim()), maxPhonemes)
            pieces.forEachIndexed { index, phonemes ->
                result += PhonemeTextChunk(phonemes, if (index == 0) source else "")
            }
        }
        return result
    }

    /** Exact `split_phonemes` behavior, including the fallback hard cut without a space. */
    fun splitPhonemes(
        phonemes: String,
        maxPhonemes: Int = KokoroArtifact.MAX_PHONEMES,
    ): List<String> {
        require(maxPhonemes > 0) { "maxPhonemes must be positive" }
        val result = mutableListOf<String>()
        var rest = phonemes.trim()
        while (rest.length > maxPhonemes) {
            var cut = rest.lastIndexOf(' ', maxPhonemes).coerceAtMost(maxPhonemes)
            if (cut <= 0) cut = maxPhonemes
            result += rest.substring(0, cut).trimEnd()
            rest = rest.substring(cut).trimStart()
        }
        if (rest.isNotEmpty()) result += rest
        return result
    }

    /** The Italian-only eSpeak dental bridge workaround; English is never altered. */
    fun normalizeDentals(
        phonemes: String,
        language: String,
    ): String = if (language == "it") phonemes.replace("\u032A", "") else phonemes

    private fun sentenceSpans(text: String): List<IntRange> {
        val trimmedStart = text.indexOfFirst { !it.isWhitespace() }
        if (trimmedStart < 0) return emptyList()
        val trimmedEnd = text.indexOfLast { !it.isWhitespace() }
        val result = mutableListOf<IntRange>()
        var spanStart = 0
        var index = trimmedStart
        while (index <= trimmedEnd) {
            val sentenceEnd = text[index] in ".!?…" && index + 1 <= trimmedEnd && text[index + 1].isWhitespace()
            val newlineEnd = text[index] == '\n'
            if (sentenceEnd || newlineEnd) {
                var next = index + 1
                if (sentenceEnd) while (next <= trimmedEnd && text[next].isWhitespace()) next++
                if (newlineEnd) while (next <= trimmedEnd && text[next] == '\n') next++
                if (next > spanStart) result += spanStart..(next - 1)
                while (next <= trimmedEnd && text[next].isWhitespace()) next++
                spanStart = next
                index = next
            } else {
                index++
            }
        }
        if (spanStart <= text.lastIndex) result += spanStart..text.lastIndex
        return result
    }
}

/** A phoneme piece and the sentence text assigned to its first graph call. */
data class PhonemeTextChunk(
    val phonemes: String,
    val sourceText: String,
)
