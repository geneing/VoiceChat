package com.voicechat.agent.stt.parakeet

import kotlin.math.exp
import kotlin.math.ln

/** What one [ParakeetStreamingDecoder] step produced. */
data class DecodeResult(
    /** Recognized text for this step; may be empty. Scoped to the current utterance segment. */
    val text: String,
    /** True when this step terminated the utterance (EOU, force, or finalize). */
    val isFinal: Boolean,
    /** True when the model emitted `<EOU>` and the debounce committed a segment on this step. */
    val eouDetected: Boolean,
    /** `exp(mean(log-softmax))` over the tokens emitted since the last boundary, in `[0, 1]`. */
    val confidence: Float,
    /** Monotonic index of the current (or just-committed) segment within the capture. */
    val segmentIndex: Int,
)

/**
 * The Parakeet-EOU streaming decode loop (stream 1).
 *
 * Pure Kotlin — no `android.*`, no ONNX type — so it is fully JVM-testable with a
 * scripted [ParakeetEouRuntime] fake. It owns the mel front end
 * ([StreamingMelFrontend]) and the RNNT greedy loop, and is a faithful port of
 * `ParakeetEou` in `scripts/parakeet-eou-host.py` with the two documented
 * corrections the frozen contract requires:
 *
 * - `<EOB>` (id 1025) is **consumed** (it updates the predictor state) but never
 *   appended to the emitted text, unlike the host's literal loop.
 * - EOU commits are gated by [eouDebounceMillis] of sustained `<EOU>` with no new
 *   tokens, so a mid-word EOU head does not prematurely end the turn.
 *
 * **Streaming geometry.** [accept] buffers samples and runs the encoder whenever
 * it holds a full [ParakeetEouConfig.chunkSamples] window (10,240 samples / 64 mel
 * frames), then drops [ParakeetEouConfig.shiftSamples] (5,120 samples) so the next
 * window overlaps by half. Each encoder call decodes
 * `min(outputMelFrames, encodedFrames)` encoder frames — 4 frames, which is the
 * 32-mel-frame / 5,120-sample advance the contract describes.
 *
 * **Cache hand-off.** The four encoder caches live in [EncoderState] and are
 * threaded through every [ParakeetEouRuntime.encode]; the predictor `h`/`c` and
 * decoder output are primed once with the blank token in [reset] and carried
 * across frames. [forceEndOfUtterance] commits text **without** clearing those
 * caches, so the model keeps streaming after a VAD-driven endpoint.
 *
 * A session is confined to one worker thread by its caller; this class is not
 * thread-safe.
 */
class ParakeetStreamingDecoder(
    private val vocab: ParakeetVocabulary,
    private val config: ParakeetEouConfig,
    private val runtime: ParakeetEouRuntime,
    private val eouDebounceMillis: Int = DEFAULT_EOU_DEBOUNCE_MILLIS,
) {
    private val mel = StreamingMelFrontend(config)

    /** Growable sample buffer; only the first [bufferSize] values are live. */
    private var buffer = FloatArray(config.chunkSamples * 2)
    private var bufferSize = 0

    private var encoderState = EncoderState.zeroed()
    private var h = FloatArray(runtime.decoderHidden)
    private var c = FloatArray(runtime.decoderHidden)
    private var decoderOutput = FloatArray(runtime.decoderHidden)

    /** Every emitted token since [reset]; partials are [tokenOffset]-scoped views of this. */
    private val allTokens = mutableListOf<Int>()
    private var tokenOffset = 0
    private var segmentLogProbs = mutableListOf<Double>()
    private var segmentIndex = 0
    private var processedSamples = 0L
    private var eouFirstAtSamples: Long? = null

    /** Host's `eou_fired`: a debounced EOU is pending commit. Persists across accepts. */
    private var eouPending = false

    private var finalized = false
    private var lastResult = DecodeResult("", false, false, 1f, 0)

    init {
        require(runtime.hidden > 0) { "runtime.hidden must be positive" }
        require(runtime.decoderHidden == config.decoderHidden) {
            "runtime.decoderHidden ${runtime.decoderHidden} != config.decoderHidden ${config.decoderHidden}"
        }
        require(runtime.logits >= config.logits) {
            "runtime.logits ${runtime.logits} < config.logits ${config.logits}"
        }
        require(eouDebounceMillis >= 0) { "eouDebounceMillis must not be negative" }
        reset()
    }

    /** Clears all decode state so the next chunk starts a fresh utterance. */
    fun reset() {
        encoderState = EncoderState.zeroed()
        h = FloatArray(runtime.decoderHidden)
        c = FloatArray(runtime.decoderHidden)
        decoderOutput = primeWithBlank()
        bufferSize = 0
        allTokens.clear()
        tokenOffset = 0
        segmentLogProbs = mutableListOf()
        segmentIndex = 0
        processedSamples = 0L
        eouFirstAtSamples = null
        eouPending = false
        finalized = false
        lastResult = DecodeResult("", false, false, 1f, 0)
    }

    /**
     * Buffers [chunkSamples] samples (any length) and decodes every whole window
     * now available. Returns the current partial, or the segment just committed by
     * a debounced EOU when no new tokens followed it.
     */
    fun accept(chunkSamples: FloatArray): DecodeResult {
        require(!finalized) { "decoder already finalized" }
        append(chunkSamples)
        val commit = runPending()
        return resultFor(commit, isFinal = false)
    }

    /**
     * Flushes the trailing samples at end of input.
     *
     * Any remaining samples are zero-padded to a full [ParakeetEouConfig.chunkSamples]
     * window **exactly once** and decoded, then the remaining tokens are committed
     * as final. Idempotent: a second call returns the same [DecodeResult] without
     * re-running the runtime.
     */
    fun finalize(): DecodeResult {
        if (finalized) return lastResult
        finalized = true
        if (bufferSize > 0) {
            val padded = FloatArray(config.chunkSamples)
            System.arraycopy(buffer, 0, padded, 0, bufferSize)
            buffer = padded
            bufferSize = config.chunkSamples
        }
        val commit = runPending()
        val eouDetected = eouPending || commit != null
        val eouCommit = if (commit != null && currentText().isEmpty()) commit else null
        val trailing = commitSegment()
        val result =
            if (eouCommit != null) {
                DecodeResult(
                    text = eouCommit.text,
                    isFinal = true,
                    eouDetected = true,
                    confidence = confidenceOf(eouCommit.logProbs),
                    segmentIndex = eouCommit.index,
                )
            } else {
                DecodeResult(
                    text = trailing.text,
                    isFinal = true,
                    eouDetected = eouDetected && trailing.text.isEmpty(),
                    confidence = confidenceOf(trailing.logProbs),
                    segmentIndex = trailing.index,
                )
            }
        lastResult = result
        return result
    }

    /**
     * VAD-driven force-finalize: commits the accumulated tokens as final and
     * clears the EOU debounce, but **keeps** the encoder caches and predictor
     * state alive so the same streaming session continues.
     */
    fun forceEndOfUtterance(): DecodeResult {
        require(!finalized) { "decoder already finalized" }
        val commit = commitSegment()
        eouPending = false
        eouFirstAtSamples = null
        val result =
            DecodeResult(
                text = commit.text,
                isFinal = true,
                eouDetected = false,
                confidence = confidenceOf(commit.logProbs),
                segmentIndex = commit.index,
            )
        lastResult = result
        return result
    }

    /** The encoder-frame budget for one call: `min(outputMelFrames, encodedFrames)`. */
    private fun decodeFrameBudget(encoderOutput: EncoderOutput): Int =
        minOf(config.outputMelFrames, encoderOutput.frameCount, encoderOutput.values.size / runtime.hidden)

    /**
     * Consumes every buffered full chunk.
     *
     * @return the last segment committed by a debounced EOU during this call, or
     *   `null` when no commit fired.
     */
    private fun runPending(): Commit? {
        var lastCommit: Commit? = null
        while (bufferSize >= config.chunkSamples) {
            val chunk = buffer.copyOf(config.chunkSamples)
            val commit = runChunk(chunk)
            if (commit != null) lastCommit = commit
            advance(config.shiftSamples)
        }
        return lastCommit
    }

    /**
     * One encoder + greedy-decode iteration, mirroring the host's streaming body.
     * Returns the segment committed by a debounced EOU on this chunk, if any.
     */
    private fun runChunk(chunk: FloatArray): Commit? {
        val encoded = encoderOutputFor(chunk)
        val budget = decodeFrameBudget(encoded)
        val tokensBefore = allTokens.size
        val greedy = greedyDecode(encoded, budget)
        allTokens.addAll(greedy.tokens)
        segmentLogProbs.addAll(greedy.logProbs)
        val emitted = allTokens.size - tokensBefore

        processedSamples += config.chunkSamples.toLong()
        var commit: Commit? = null
        if (greedy.eou) {
            if (emitted == 0) {
                if (eouFirstAtSamples == null) eouFirstAtSamples = processedSamples
            } else {
                eouFirstAtSamples = null
            }
            val first = eouFirstAtSamples
            if (first != null && (processedSamples - first) * 1000L / config.sampleRate >= eouDebounceMillis) {
                eouPending = true
            }
        } else {
            eouFirstAtSamples = null
        }

        if (eouPending) {
            commit = commitSegment()
            eouPending = false
            eouFirstAtSamples = null
        }
        return commit
    }

    /** Runs the encoder on the first [config.chunkMelFrames] mel frames of [chunk]. */
    private fun encoderOutputFor(chunk: FloatArray): EncoderOutput {
        val frames = mel.frameCount(chunk.size)
        val melFrames = config.chunkMelFrames
        val input = FloatArray(config.numMelBins * melFrames)
        if (frames > 0 && chunk.isNotEmpty()) {
            val raw = mel.extract(chunk)
            val usable = minOf(frames, melFrames)
            for (m in 0 until config.numMelBins) {
                System.arraycopy(raw, m * frames, input, m * melFrames, usable)
            }
        }
        val output = runtime.encode(input, melFrames, encoderState)
        encoderState = output.state
        return output
    }

    /**
     * The RNNT greedy loop: up to [MAX_SYMBOLS_PER_FRAME] non-blank symbols per
     * encoder frame; blank breaks; `<EOU>` sets [GreedyTokens.eou] and breaks;
     * `<EOB>` updates the predictor but is never emitted.
     */
    private fun greedyDecode(
        encoded: EncoderOutput,
        frameCount: Int,
    ): GreedyTokens {
        val tokens = mutableListOf<Int>()
        val logProbs = mutableListOf<Double>()
        var eou = false
        var frameIndex = 0
        outer@ while (frameIndex < frameCount) {
            val frame = encoded.frame(frameIndex, runtime.hidden)
            for (attempt in 0 until MAX_SYMBOLS_PER_FRAME) {
                val logits = runtime.joint(frame, decoderOutput)
                val token = argmax(logits, config.logits)
                when (token) {
                    config.blankTokenId -> {
                        frameIndex++
                        continue@outer
                    }

                    config.eouTokenId -> {
                        eou = true
                        break@outer
                    }

                    config.eobTokenId -> {
                        advancePredictor(token)
                    }

                    else -> {
                        tokens += token
                        logProbs += logSoftmax(logits, token)
                        advancePredictor(token)
                    }
                }
            }
            frameIndex++
        }
        return GreedyTokens(tokens, logProbs, eou)
    }

    /** Feeds [token] to the predictor and stores the next decoder output/h/c. */
    private fun advancePredictor(token: Int) {
        val output = runtime.decode(token, h, c)
        decoderOutput = output.decoderOutput
        h = output.hOut
        c = output.cOut
    }

    private fun primeWithBlank(): FloatArray {
        val output = runtime.decode(config.blankTokenId, h, c)
        h = output.hOut
        c = output.cOut
        return output.decoderOutput
    }

    /** Commits the current [tokenOffset]-scoped tokens as a new segment. Always advances. */
    private fun commitSegment(): Commit {
        val text = currentText()
        val logProbs = segmentLogProbs.toList()
        val commit = Commit(text, logProbs, segmentIndex)
        tokenOffset = allTokens.size
        segmentLogProbs = mutableListOf()
        segmentIndex++
        eouFirstAtSamples = null
        return commit
    }

    /** Builds the result for [accept]/[finalize] from the current state and any commit. */
    private fun resultFor(
        commit: Commit?,
        isFinal: Boolean,
    ): DecodeResult {
        val current = currentText()
        if (commit != null && current.isEmpty()) {
            return DecodeResult(
                text = commit.text,
                isFinal = true,
                eouDetected = true,
                confidence = confidenceOf(commit.logProbs),
                segmentIndex = commit.index,
            )
        }
        return DecodeResult(
            text = current,
            isFinal = isFinal,
            eouDetected = false,
            confidence = confidenceOf(segmentLogProbs),
            segmentIndex = segmentIndex,
        )
    }

    private fun currentText(): String = vocab.decode(allTokens.subList(tokenOffset, allTokens.size))

    private fun append(samples: FloatArray) {
        if (samples.isEmpty()) return
        ensureCapacity(bufferSize + samples.size)
        samples.copyInto(buffer, bufferSize)
        bufferSize += samples.size
    }

    private fun advance(count: Int) {
        System.arraycopy(buffer, count, buffer, 0, bufferSize - count)
        bufferSize -= count
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= buffer.size) return
        var capacity = buffer.size
        while (capacity < needed) capacity = capacity shl 1
        buffer = buffer.copyOf(capacity)
    }

    private fun argmax(
        logits: FloatArray,
        limit: Int,
    ): Int {
        val bound = minOf(limit, logits.size)
        var best = 0
        var bestValue = logits[0]
        for (i in 1 until bound) {
            if (logits[i] > bestValue) {
                bestValue = logits[i]
                best = i
            }
        }
        return best
    }

    /** `log p(token) = logit[token] - logsumexp(logits)`. */
    private fun logSoftmax(
        logits: FloatArray,
        token: Int,
    ): Double {
        val bound = minOf(config.logits, logits.size)
        var max = logits[0].toDouble()
        for (i in 1 until bound) if (logits[i] > max) max = logits[i].toDouble()
        var sum = 0.0
        for (i in 0 until bound) sum += exp(logits[i] - max)
        return (logits[token] - (max + ln(sum)))
    }

    private fun confidenceOf(logProbs: List<Double>): Float {
        if (logProbs.isEmpty()) return 1f
        var sum = 0.0
        for (value in logProbs) sum += value
        return exp(sum / logProbs.size).toFloat().coerceIn(0f, 1f)
    }

    /** A committed utterance segment. */
    private data class Commit(
        val text: String,
        val logProbs: List<Double>,
        val index: Int,
    )

    private data class GreedyTokens(
        val tokens: List<Int>,
        val logProbs: List<Double>,
        val eou: Boolean,
    )

    companion object {
        /** Maximum non-blank symbols emitted per encoder frame (RNNT limit). */
        const val MAX_SYMBOLS_PER_FRAME: Int = 10

        /** speech-swift's EOU debounce: sustained `<EOU>` for ~1.28 s with no new tokens. */
        const val DEFAULT_EOU_DEBOUNCE_MILLIS: Int = 1280
    }
}
