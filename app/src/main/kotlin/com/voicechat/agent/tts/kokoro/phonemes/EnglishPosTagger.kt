package com.voicechat.agent.tts.kokoro.phonemes

import opennlp.tools.postag.POSModel
import opennlp.tools.postag.POSTagFormat
import opennlp.tools.postag.POSTaggerME
import java.io.InputStream

/** Penn Treebank tags used for Misaki's POS-specific English pronunciations. */
internal fun interface EnglishPosTagger {
    /** Returns one POS tag per input token. */
    fun tag(tokens: List<String>): List<String>
}

/**
 * OpenNLP Penn tagger using the offline model from the linked README's data script.
 * The bundled source model is 3,975,786 bytes with SHA-256
 * `0b49b7d9bdb9f888aed85e9f41fbcfd6cab607805ba9cd2370e1e5af4e540db8`.
 */
internal class OpenNlpEnglishPosTagger(
    input: InputStream,
) : EnglishPosTagger {
    private val tagger =
        input.use { modelInput ->
            POSTaggerME(POSModel(modelInput), POSTagFormat.PENN)
        }

    override fun tag(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        return synchronized(tagger) { tagger.tag(tokens.toTypedArray()).toList() }
    }
}
