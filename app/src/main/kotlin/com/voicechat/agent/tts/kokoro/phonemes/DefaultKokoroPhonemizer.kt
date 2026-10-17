package com.voicechat.agent.tts.kokoro.phonemes

import android.content.res.AssetManager
import com.voicechat.agent.tts.kokoro.KokoroArtifact
import com.voicechat.agent.tts.kokoro.KokoroPhonemeChunk
import com.voicechat.agent.tts.kokoro.KokoroPhonemizer
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream

/** Opens a pinned phonemizer resource, injectable without Android in JVM tests. */
fun interface KokoroPhonemeResources {
    /** Opens [path] as a fresh stream; callers close the returned stream. */
    fun open(path: String): InputStream
}

/**
 * Opens a pinned phonemizer asset, tolerating the packager's gzip rule.
 *
 * The lexicon is committed gzipped (`us_gold.json.gz`) to keep the repository
 * small, but the Android packager stores a `.gz` asset **decompressed** under the
 * name without the suffix, so the APK contains `us_gold.json`. Asking for the
 * committed name therefore fails on a device even though it works on the JVM.
 * This resolves the real entry and returns its bytes; the lexicon reader detects
 * the gzip magic itself, so it is correct whether the bytes are compressed or
 * not.
 */
internal fun openKokoroAsset(
    assets: AssetManager,
    path: String,
): InputStream =
    try {
        assets.open(path)
    } catch (_: FileNotFoundException) {
        if (path.endsWith(".gz")) assets.open(path.removeSuffix(".gz")) else throw FileNotFoundException(path)
    }

/** Offline US English phonemizer using the pinned OpenNLP Penn model and Misaki dictionaries. */
class DefaultKokoroPhonemizer internal constructor(
    vocab: KokoroVocab,
    lexicon: EnglishLexicon,
    posTagger: EnglishPosTagger,
) : KokoroPhonemizer {
    private val g2p = EnglishG2p(lexicon, posTagger)
    private val chunker = KokoroPhonemeChunker()
    private val lock = Any()
    private val vocabulary = vocab

    /** Creates the phonemizer from app assets using the production resource paths. */
    constructor(resources: KokoroPhonemeResources) : this(
        KokoroVocab.load(resources.open(KokoroArtifact.VOCAB_ASSET_PATH)),
        EnglishLexicon.fromGzip(
            resources.open(GOLD_ASSET_PATH),
            resources.open(SILVER_ASSET_PATH),
        ),
        OpenNlpEnglishPosTagger(resources.open(POS_MODEL_ASSET_PATH)),
    )

    /** Creates the phonemizer directly from compressed files, without Robolectric. */
    constructor(vocab: File, gold: File, silver: File, posModel: File) : this(
        KokoroVocab.load(vocab.inputStream()),
        EnglishLexicon.fromFiles(gold, silver),
        OpenNlpEnglishPosTagger(posModel.inputStream()),
    )

    override fun phonemes(text: String): String = synchronized(lock) { g2p.phonemize(text).trim() }

    override fun chunks(
        text: String,
        maxPhonemes: Int,
    ): List<KokoroPhonemeChunk> =
        synchronized(lock) {
            chunker
                .chunks(text, maxPhonemes, g2p::phonemize)
                .filter { it.phonemes.any(Char::isLetter) }
                .map { KokoroPhonemeChunk(it.phonemes, it.sourceText) }
        }

    override fun encode(phonemes: String): IntArray = vocabulary.encode(phonemes)

    companion object {
        private const val GOLD_ASSET_PATH = "kokoro/us_gold.json.gz"
        private const val SILVER_ASSET_PATH = "kokoro/us_silver.json.gz"
        private const val POS_MODEL_ASSET_PATH = "kokoro/en-pos-perceptron.bin"

        /** Creates the app implementation without exposing Android types in the engine interface. */
        fun fromAssets(assets: AssetManager): DefaultKokoroPhonemizer =
            DefaultKokoroPhonemizer(KokoroPhonemeResources { path -> openKokoroAsset(assets, path) })

        /** File-based factory used by JVM tests and reference comparisons. */
        fun fromFiles(
            vocab: File,
            gold: File,
            silver: File,
            posModel: File,
        ): DefaultKokoroPhonemizer = DefaultKokoroPhonemizer(vocab, gold, silver, posModel)
    }
}
