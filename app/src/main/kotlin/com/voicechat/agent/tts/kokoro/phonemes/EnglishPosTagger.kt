package com.voicechat.agent.tts.kokoro.phonemes

import opennlp.tools.postag.POSModel
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
 *
 * The pinned OpenNLP is 2.3.0 rather than the newest release: from 2.5.x
 * `BaseModel`'s static initializer references `java.io.ObjectInputFilter`, which
 * Android's runtime does not have, so constructing a `POSModel` on a device
 * throws `NoClassDefFoundError` and takes the whole TTS path with it (found on
 * the Pixel 10, 2026-10-17). 2.3.0 has no such reference, and it takes the model
 * directly (`POSTaggerME(POSModel)`), because the `POSTagFormat` selector that
 * newer releases use does not exist yet. See R-0267.
 */
internal class OpenNlpEnglishPosTagger(
    input: InputStream,
) : EnglishPosTagger {
    private val tagger = input.use { modelInput -> POSTaggerME(POSModel(modelInput)) }

    override fun tag(tokens: List<String>): List<String> {
        if (tokens.isEmpty()) return emptyList()
        return synchronized(tagger) { tagger.tag(tokens.toTypedArray()).toList() }
    }
}
