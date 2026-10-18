package com.voicechat.agent.tts.kokoro

/**
 * One graph-sized piece of an utterance: the phonemes to synthesize and the
 * original text they came from.
 *
 * [sourceText] is what makes interruption accounting truthful. The engine
 * reports an interrupted utterance as the **prefix of the input text it could
 * confirm was audible**, and it can only do that if each synthesized piece knows
 * which characters of the input it speaks. The pieces of one utterance are
 * ordered, and their [sourceText] values are a partition of the input text: a
 * sentence that fits in one graph call carries that whole sentence, and a
 * sentence long enough to be cut carries its text on its **first** piece and `""`
 * on the rest — a rare path (over ~500 phonemes, roughly 40 words in one
 * sentence) that under-reports rather than over-reports what was audible.
 */
class KokoroPhonemeChunk(
    /** Kokoro phonemes, without the padding tokens [KokoroPhonemizer.encode] adds. */
    val phonemes: String,
    /** The input-text span this piece speaks; concatenating a utterance's pieces reproduces its text. */
    val sourceText: String,
)

/**
 * Text to Kokoro phonemes, ids, and graph-sized chunks.
 *
 * This is the port of the publisher's `titl/phonemes.py` and the Misaki English
 * G2P it delegates to; the implementation lives in
 * [com.voicechat.agent.tts.kokoro.phonemes]. It is deliberately an interface so
 * the engine, the harness, and the JVM tests can run against a deterministic
 * fake without the lexicon, and so a second language is a new implementation
 * rather than a change here.
 *
 * **Thread safety.** Implementations must be safe to call from more than one
 * coroutine, because a voice session can start synthesizing the next sentence
 * while the previous one is still being spoken. The ported English G2P keeps
 * mutable context per call and is therefore internally serialized, exactly as
 * the Python original is (its `Phonemizer` holds a lock for the same reason).
 */
interface KokoroPhonemizer {
    /**
     * The phonemes of [text], as Kokoro's own pipeline produces them: one string,
     * sentences joined, unknown symbols already dropped by [encode] rather than
     * here. Never throws for ordinary prose.
     */
    fun phonemes(text: String): String

    /**
     * [text] cut into graph-sized chunks, in reading order, one per sentence and
     * cut further only when a sentence exceeds [maxPhonemes].
     *
     * The split rule is the publisher's: split the text at sentence breaks, and
     * cut a too-long sentence's phonemes at the last space that fits. Text with
     * no phonemes (punctuation only, or nothing the vocabulary knows) yields no
     * chunks, so the engine can report "nothing to say" rather than play silence.
     */
    fun chunks(
        text: String,
        maxPhonemes: Int = KokoroArtifact.MAX_PHONEMES,
    ): List<KokoroPhonemeChunk>

    /**
     * Kokoro token ids for [phonemes], with the padding token `0` at both ends.
     *
     * Symbols outside Kokoro's vocabulary are dropped, exactly as the publisher's
     * `encode` does, and more than [KokoroArtifact.MAX_PHONEMES] phonemes is an
     * error rather than a silently truncated call.
     */
    fun encode(phonemes: String): IntArray
}
