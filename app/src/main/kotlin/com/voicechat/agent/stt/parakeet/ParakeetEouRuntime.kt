package com.voicechat.agent.stt.parakeet

/**
 * The ONNX-free runtime seam the streaming decoder (stream 1) calls.
 *
 * Stream 1 owns this interface per the frozen contract
 * (`docs/parakeet-eou-streaming.md`). The concrete ONNX implementation
 * (`OnnxParakeetEouRuntime`, stream 2) and the pure decoder here both depend
 * only on these signatures, so no `ai.onnxruntime` type ever reaches the decode
 * loop and the whole thing stays JVM-testable with a fake.
 *
 * Tensor layouts the decoder relies on:
 *
 * - [encode] receives/returns flat f32 arrays; [EncoderOutput.values] is
 *   **frame-major `[T, hidden]`** (frame 0 first), i.e. the graph's batch-major
 *   `[1, T, hidden]` output squeezed on the batch axis.
 * - [decode] returns flat `[1, 1, decoderHidden]` arrays.
 * - [joint] returns flat `[1, 1, logits]`.
 */
interface ParakeetEouRuntime : AutoCloseable {
    /** Encoder hidden size, 512. */
    val hidden: Int

    /** Decoder hidden size, 640. */
    val decoderHidden: Int

    /** Joint vocabulary/logit width, 1027. */
    val logits: Int

    /**
     * Runs the encoder on [melFrames] mel frames.
     *
     * @param mel flat f32 mel, `numMelBins * melFrames` values, **mel-major**
     *   (`[numMelBins, melFrames]`, bin 0 of every frame first) exactly as
     *   [StreamingMelFrontend] produces it.
     * @param melFrames the number of mel frames in [mel].
     * @param state the caches carried from the previous call
     *   ([EncoderState.zeroed] for the first).
     * @return the encoded frames plus the updated caches.
     */
    fun encode(
        mel: FloatArray,
        melFrames: Int,
        state: EncoderState,
    ): EncoderOutput

    /**
     * Runs one decoder (predictor) step.
     *
     * @param token the input token id.
     * @param h flat f32 `[1, 1, decoderHidden]` previous hidden state.
     * @param c flat f32 `[1, 1, decoderHidden]` previous cell state.
     */
    fun decode(
        token: Int,
        h: FloatArray,
        c: FloatArray,
    ): DecoderOutput

    /**
     * Runs the joint network for one encoder frame and the current decoder output.
     *
     * @param encoderFrame flat f32 `[1, 1, hidden]`.
     * @param decoderOutput flat f32 `[1, 1, decoderHidden]`.
     * @return flat f32 `[1, 1, logits]`.
     */
    fun joint(
        encoderFrame: FloatArray,
        decoderOutput: FloatArray,
    ): FloatArray
}

/**
 * The four streaming caches carried between encoder calls, mirroring the ONNX
 * encoder's cache inputs.
 *
 * @param preCache f32 flat `[1, 128, 9]` (1152 values).
 * @param cacheLastChannel f32 flat `[17, 1, 70, 512]`.
 * @param cacheLastTime f32 flat `[17, 1, 512, 8]`.
 * @param cacheLastChannelLen i64-compatible length; the graph takes it as a
 *   single-element i64 tensor, so a `Long` is the natural carrier.
 */
data class EncoderState(
    val preCache: FloatArray,
    val cacheLastChannel: FloatArray,
    val cacheLastTime: FloatArray,
    val cacheLastChannelLen: Long,
) {
    /** A fresh, all-zero cache set for a new utterance. */
    companion object {
        /** `1 * 128 * 9`. */
        const val PRE_CACHE_ELEMENTS: Int = 1 * 128 * 9

        /** `17 * 1 * 70 * 512`. */
        const val CACHE_LAST_CHANNEL_ELEMENTS: Int = 17 * 1 * 70 * 512

        /** `17 * 1 * 512 * 8`. */
        const val CACHE_LAST_TIME_ELEMENTS: Int = 17 * 1 * 512 * 8

        /**
         * The all-zero initial caches: no history is visible to the first encoder
         * call of a new utterance, which is exactly how `parakeet_eou_host.py`
         * primes the streaming session.
         */
        fun zeroed(): EncoderState =
            EncoderState(
                preCache = FloatArray(PRE_CACHE_ELEMENTS),
                cacheLastChannel = FloatArray(CACHE_LAST_CHANNEL_ELEMENTS),
                cacheLastTime = FloatArray(CACHE_LAST_TIME_ELEMENTS),
                cacheLastChannelLen = 0L,
            )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncoderState) return false
        return preCache.contentEquals(other.preCache) &&
            cacheLastChannel.contentEquals(other.cacheLastChannel) &&
            cacheLastTime.contentEquals(other.cacheLastTime) &&
            cacheLastChannelLen == other.cacheLastChannelLen
    }

    override fun hashCode(): Int {
        var result = preCache.contentHashCode()
        result = 31 * result + cacheLastChannel.contentHashCode()
        result = 31 * result + cacheLastTime.contentHashCode()
        result = 31 * result + cacheLastChannelLen.hashCode()
        return result
    }
}

/**
 * One encoder call's output: the encoded frames plus the caches to carry into
 * the next call.
 *
 * @param values flat encoded frames in **frame-major `[T, hidden]`** layout
 *   (frame 0 first), length `frameCount * hidden`. The ONNX runtime normalises
 *   the graph's batch-major `[1, T, hidden]` output into this layout.
 * @param frameCount number of valid frames (`encoded_length`). May be less than
 *   `values.size / hidden` only if a caller chose a wider buffer; here
 *   `values.size == frameCount * hidden`.
 * @param state the updated caches for the next [ParakeetEouRuntime.encode] call.
 */
data class EncoderOutput(
    val values: FloatArray,
    val frameCount: Int,
    val state: EncoderState,
) {
    /** @return frame [index] (0-based) as a fresh `hidden`-length array. */
    fun frame(
        index: Int,
        hidden: Int,
    ): FloatArray {
        require(index in 0 until frameCount) { "frame index $index out of range 0..<$frameCount" }
        return values.copyOfRange(index * hidden, (index + 1) * hidden)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EncoderOutput) return false
        return values.contentEquals(other.values) &&
            frameCount == other.frameCount &&
            state == other.state
    }

    override fun hashCode(): Int {
        var result = values.contentHashCode()
        result = 31 * result + frameCount
        result = 31 * result + state.hashCode()
        return result
    }
}

/**
 * One decoder step's output.
 *
 * @param decoderOutput f32 flat `[1, 1, decoderHidden]` (640 values), fed to
 *   [ParakeetEouRuntime.joint].
 * @param hOut f32 flat `[1, 1, decoderHidden]` next LSTM hidden state.
 * @param cOut f32 flat `[1, 1, decoderHidden]` next LSTM cell state.
 */
data class DecoderOutput(
    val decoderOutput: FloatArray,
    val hOut: FloatArray,
    val cOut: FloatArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecoderOutput) return false
        return decoderOutput.contentEquals(other.decoderOutput) &&
            hOut.contentEquals(other.hOut) &&
            cOut.contentEquals(other.cOut)
    }

    override fun hashCode(): Int {
        var result = decoderOutput.contentHashCode()
        result = 31 * result + hOut.contentHashCode()
        result = 31 * result + cOut.contentHashCode()
        return result
    }
}
