package com.voicechat.agent.tts.kokoro.phonemes

import com.voicechat.agent.tts.kokoro.KokoroArtifact
import java.io.InputStream

/** Kokoro's symbol-to-id table and the publisher's padded token encoding. */
class KokoroVocab private constructor(
    private val ids: Map<Char, Int>,
) {
    /** Encodes Unicode symbols known to Kokoro, dropping every unknown symbol. */
    fun encode(phonemes: String): IntArray {
        val encoded = phonemes.mapNotNull(ids::get)
        require(encoded.size <= KokoroArtifact.MAX_PHONEMES) {
            "${encoded.size} phonemes; at most ${KokoroArtifact.MAX_PHONEMES} fit in one call"
        }
        return IntArray(encoded.size + 2).also { result ->
            encoded.forEachIndexed { index, id -> result[index + 1] = id }
        }
    }

    companion object {
        /** Loads the UTF-8 `symbol -> id` JSON from an app asset or a test file. */
        fun load(input: InputStream): KokoroVocab =
            input.use { stream ->
                val values = PhonemeJson.parse(stream.bufferedReader(Charsets.UTF_8).readText()).asObject()
                KokoroVocab(
                    values
                        .map { (symbol, value) ->
                            val id =
                                (value as? PhonemeJsonValue.NumberValue)?.value?.toInt()
                                    ?: error("Vocabulary id for '$symbol' must be an integer")
                            symbol.single() to id
                        }.toMap(),
                )
            }
    }
}
