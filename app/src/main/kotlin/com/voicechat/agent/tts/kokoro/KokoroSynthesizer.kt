package com.voicechat.agent.tts.kokoro

/**
 * One ONNX graph call: Kokoro phoneme ids in, 24 kHz mono audio out.
 *
 * This is the seam the M10 Smart Turn detector also uses
 * ([com.voicechat.agent.turn.SmartTurnInferenceEngine]): the only implementation
 * touches ONNX Runtime, and everything above it — the engine, the pipeline, the
 * latency harness, the JVM tests — is pure Kotlin and runs against a fake. The
 * native library and the 9 MB graph are therefore never needed to prove
 * orchestration, chunking, accounting, or failure behavior.
 */
interface KokoroSynthesizer {
    /**
     * Runs the graph for [ids] at [speed].
     *
     * [ids] are the padded Kokoro token ids from [KokoroPhonemizer.encode].
     * [speed] is the pace the publisher's contract defines: `1.0` is the
     * teacher's pace and above `1` speaks faster.
     *
     * @return mono float samples in `[-1, 1]` at [KokoroArtifact.SAMPLE_RATE_HZ].
     *   The excitation is random, so two calls with the same input are not
     *   bit-identical; that is the model, not a defect.
     */
    fun synthesize(
        ids: IntArray,
        speed: Float = KokoroArtifact.DEFAULT_SPEED,
    ): FloatArray

    /** Releases the graph session. Idempotent. */
    fun close()
}

/**
 * Text to 24 kHz mono audio, one piece per phoneme chunk.
 *
 * This is the composition the engine and the latency harness share, so both
 * measure the same work: phonemize the text, encode each chunk, run the graph,
 * and keep the chunk's source text for interruption accounting.
 *
 * Pieces are produced lazily, so a caller that starts playing piece 0 does not
 * wait for the rest of the utterance to be synthesized. That laziness is the
 * whole point of the pipeline: time-to-first-audio is the product requirement
 * (`docs/voice-quality-and-latency.md`), and it is the number the M25 TTS
 * harness records.
 *
 * This class is pure Kotlin and holds no audio or platform state; blocking
 * synthesis happens on whatever dispatcher the caller iterates it from.
 */
class KokoroSynthesisPipeline(
    private val phonemizer: KokoroPhonemizer,
    private val synthesizer: KokoroSynthesizer,
    private val speed: Float = KokoroArtifact.DEFAULT_SPEED,
) {
    /** The ordered audio pieces for [text]; empty when [text] has no speakable phonemes. */
    fun pieces(text: String): Sequence<KokoroPcmPiece> = phonemizer.chunks(text).asSequence().map { synthesize(it) }

    /** The audio for one phoneme chunk. */
    fun synthesize(chunk: KokoroPhonemeChunk): KokoroPcmPiece =
        KokoroPcmPiece(samples = synthesizer.synthesize(phonemizer.encode(chunk.phonemes), speed), sourceText = chunk.sourceText)
}

/** One synthesized piece of an utterance: 24 kHz mono samples and the text they speak. */
class KokoroPcmPiece(
    val samples: FloatArray,
    val sourceText: String,
)
