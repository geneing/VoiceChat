package com.voicechat.agent.tts.kokoro.phonemes

import android.content.res.AssetManager
import com.voicechat.agent.tts.kokoro.KokoroArtifact
import com.voicechat.agent.tts.kokoro.KokoroPhonemeChunk
import com.voicechat.agent.tts.kokoro.KokoroPhonemizer
import com.voicechat.agent.tts.normalization.TextNormalizer
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

/**
 * Offline US English phonemizer using the pinned OpenNLP Penn model and Misaki dictionaries.
 *
 * **Text normalization.** [normalizer] runs on the spoken string on the way into
 * the G2P, per sentence, and is the port of the neighboring EpubReader project's
 * rule pipeline ([docs/text-normalization.md](../../../../../../../../../docs/text-normalization.md)).
 * It defaults to the English pipeline because that is what the app speaks, and a
 * caller that needs the *reference* form of the text passes
 * [TextNormalizer.Identity] instead — the parity fixtures are generated from raw
 * sentences, so the port's fidelity to Misaki is asserted with the pass-through
 * and the spoken path is asserted separately.
 *
 * Normalization never changes what the app accounts for as delivered: [chunks]
 * keeps each chunk's original sentence as its source text, and only the string
 * handed to the phonemizer is rewritten.
 */
class DefaultKokoroPhonemizer internal constructor(
    vocab: KokoroVocab,
    lexicon: EnglishLexicon,
    posTagger: EnglishPosTagger,
    private val normalizer: TextNormalizer = TextNormalizer.English,
) : KokoroPhonemizer {
    private val g2p = EnglishG2p(lexicon, posTagger)
    private val chunker = KokoroPhonemeChunker()
    private val lock = Any()
    private val vocabulary = vocab

    /** Creates the phonemizer from app assets using the production resource paths. */
    constructor(
        resources: KokoroPhonemeResources,
        normalizer: TextNormalizer = TextNormalizer.English,
    ) : this(
        KokoroVocab.load(resources.open(KokoroArtifact.VOCAB_ASSET_PATH)),
        EnglishLexicon.fromGzip(
            resources.open(GOLD_ASSET_PATH),
            resources.open(SILVER_ASSET_PATH),
        ),
        OpenNlpEnglishPosTagger(resources.open(POS_MODEL_ASSET_PATH)),
        normalizer,
    )

    /** Creates the phonemizer directly from compressed files, without Robolectric. */
    constructor(
        vocab: File,
        gold: File,
        silver: File,
        posModel: File,
        normalizer: TextNormalizer = TextNormalizer.English,
    ) : this(
        KokoroVocab.load(vocab.inputStream()),
        EnglishLexicon.fromFiles(gold, silver),
        OpenNlpEnglishPosTagger(posModel.inputStream()),
        normalizer,
    )

    override fun phonemes(text: String): String = synchronized(lock) { g2p.phonemize(normalizer.normalize(text)).trim() }

    override fun chunks(
        text: String,
        maxPhonemes: Int,
    ): List<KokoroPhonemeChunk> =
        synchronized(lock) {
            chunker
                // Normalize each sentence on the way in: the chunker keeps the
                // original sentence as the chunk's source text, so expanding
                // "$1500" into words can never change what the app accounts for as
                // delivered (`docs/text-normalization.md`).
                .chunks(text, maxPhonemes) { sentence -> g2p.phonemize(normalizer.normalize(sentence)) }
                .filter { it.phonemes.any(Char::isLetter) }
                .map { KokoroPhonemeChunk(it.phonemes, it.sourceText) }
        }

    override fun encode(phonemes: String): IntArray = vocabulary.encode(phonemes)

    companion object {
        private const val GOLD_ASSET_PATH = "kokoro/us_gold.json.gz"
        private const val SILVER_ASSET_PATH = "kokoro/us_silver.json.gz"
        private const val POS_MODEL_ASSET_PATH = "kokoro/en-pos-perceptron.bin"

        /** Creates the app implementation without exposing Android types in the engine interface. */
        fun fromAssets(
            assets: AssetManager,
            normalizer: TextNormalizer = TextNormalizer.English,
        ): DefaultKokoroPhonemizer = DefaultKokoroPhonemizer(KokoroPhonemeResources { path -> openKokoroAsset(assets, path) }, normalizer)

        /** File-based factory used by JVM tests and reference comparisons. */
        fun fromFiles(
            vocab: File,
            gold: File,
            silver: File,
            posModel: File,
            normalizer: TextNormalizer = TextNormalizer.English,
        ): DefaultKokoroPhonemizer = DefaultKokoroPhonemizer(vocab, gold, silver, posModel, normalizer)
    }
}
