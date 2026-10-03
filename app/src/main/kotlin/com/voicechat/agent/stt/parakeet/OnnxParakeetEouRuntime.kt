package com.voicechat.agent.stt.parakeet

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.io.IOException
import java.nio.FloatBuffer
import java.nio.LongBuffer

/**
 * The only ONNX Runtime-backed [ParakeetEouRuntime] (stream 2).
 *
 * It loads the three pinned graphs — `parakeet-eou-encoder.onnx` (INT8),
 * `parakeet-eou-decoder.onnx` (FP32), and `parakeet-eou-joint.onnx` (FP32) — from
 * app-private storage and runs the CPU execution provider with
 * [intraOpNumThreads] threads (default [DEFAULT_INTRA_OP_THREADS], matching the
 * host reference's default of 4). The caller must have verified all five files
 * against [ParakeetEouArtifact.PINNED] first: [load] only loads them.
 *
 * ## Tensor layouts (contract)
 *
 * The graphs' layouts match the frozen contract; this class normalises the one
 * place they differ from the streaming decoder's expectation:
 *
 * - **encode** input `audio_signal` f32 `[1, numMelBins, melFrames]`
 *   (**mel-major**, exactly as the front end produces); `audio_length` i64 `[1]`;
 *   caches `pre_cache` f32 `[1,128,9]`, `cache_last_channel` f32 `[17,1,70,512]`,
 *   `cache_last_time` f32 `[17,1,512,8]`, `cache_last_channel_len` i64 `[1]`.
 *   Output `encoded_output` f32 `[1, T, hidden]` is normalised by **squeezing the
 *   batch axis into frame-major `[T, hidden]`** ([EncoderOutput.values]); frame 0's
 *   `hidden` values come first. `encoded_length` is read as the frame count, and
 *   `new_pre_cache` / `new_cache_last_channel` / `new_cache_last_time` /
 *   `new_cache_last_channel_len` become the next [EncoderState].
 * - **decode** input `token` i64 `[1,1]`, `h`/`c` f32 `[1,1,decoderHidden]`;
 *   outputs `decoder_output` / `h_out` / `c_out` flat `[1,1,decoderHidden]`.
 * - **joint** input `encoder_output` f32 `[1,1,hidden]`, `decoder_output` f32
 *   `[1,1,decoderHidden]`; output `logits` f32 `[1,1,1027]` flattened to length
 *   [logits].
 *
 * ## Environment lifetime
 *
 * [OrtEnvironment] is the shared process singleton and is **not** closed here;
 * only the three sessions are released, so closing one runtime does not tear down
 * the runtime for another ([OnnxSmartTurnEngine] does the same).
 *
 * This file is never referenced by JVM unit tests (no ONNX native library is
 * available to that source set); native loading is proven by the on-device test.
 */
class OnnxParakeetEouRuntime private constructor(
    private val environment: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    private val joint: OrtSession,
    override val hidden: Int,
    override val decoderHidden: Int,
    override val logits: Int,
    private val numMelBins: Int,
    private val encoderLayers: Int,
    private val attentionContext: Int,
    private val convCacheSize: Int,
) : ParakeetEouRuntime {
    override fun encode(
        mel: FloatArray,
        melFrames: Int,
        state: EncoderState,
    ): EncoderOutput {
        require(melFrames > 0) { "melFrames must be positive, was $melFrames" }
        require(mel.size == numMelBins * melFrames) {
            "mel must hold numMelBins * melFrames = ${numMelBins * melFrames} values, was ${mel.size}"
        }
        require(state.preCache.size == EncoderState.PRE_CACHE_ELEMENTS) {
            "preCache must hold ${EncoderState.PRE_CACHE_ELEMENTS} values, was ${state.preCache.size}"
        }
        require(state.cacheLastChannel.size == EncoderState.CACHE_LAST_CHANNEL_ELEMENTS) {
            "cacheLastChannel must hold ${EncoderState.CACHE_LAST_CHANNEL_ELEMENTS} values, was ${state.cacheLastChannel.size}"
        }
        require(state.cacheLastTime.size == EncoderState.CACHE_LAST_TIME_ELEMENTS) {
            "cacheLastTime must hold ${EncoderState.CACHE_LAST_TIME_ELEMENTS} values, was ${state.cacheLastTime.size}"
        }

        val inputs = LinkedHashMap<String, OnnxTensor>()
        val tensors = mutableListOf<OnnxTensor>()
        try {
            tensors +=
                floatTensor(mel, longArrayOf(1L, numMelBins.toLong(), melFrames.toLong())).also {
                    inputs[ENCODER_AUDIO_SIGNAL] = it
                }
            tensors +=
                longTensor(longArrayOf(melFrames.toLong()), longArrayOf(1L)).also {
                    inputs[ENCODER_AUDIO_LENGTH] = it
                }
            tensors +=
                floatTensor(state.preCache, longArrayOf(1L, PRE_CACHE_BINS.toLong(), preCacheFrames())).also {
                    inputs[ENCODER_PRE_CACHE] = it
                }
            tensors +=
                floatTensor(
                    state.cacheLastChannel,
                    longArrayOf(encoderLayers.toLong(), 1L, attentionContext.toLong(), hidden.toLong()),
                ).also { inputs[ENCODER_CACHE_LAST_CHANNEL] = it }
            tensors +=
                floatTensor(
                    state.cacheLastTime,
                    longArrayOf(encoderLayers.toLong(), 1L, hidden.toLong(), convCacheSize.toLong()),
                ).also { inputs[ENCODER_CACHE_LAST_TIME] = it }
            tensors +=
                longTensor(longArrayOf(state.cacheLastChannelLen), longArrayOf(1L)).also {
                    inputs[ENCODER_CACHE_LAST_CHANNEL_LEN] = it
                }

            encoder.run(inputs).use { result ->
                val encodedOutput = outputFrameMatrix(result, ENCODER_OUTPUT)
                val encodedLength = outputValue(result, ENCODER_LENGTH, IntArray::class.java)
                val newPreCache = outputValue(result, ENCODER_NEW_PRE_CACHE, FloatArray::class.java)
                val newCacheChannel = outputValue(result, ENCODER_NEW_CACHE_LAST_CHANNEL, FloatArray::class.java)
                val newCacheTime = outputValue(result, ENCODER_NEW_CACHE_LAST_TIME, FloatArray::class.java)
                val newCacheLen = outputValue(result, ENCODER_NEW_CACHE_LAST_CHANNEL_LEN, IntArray::class.java)

                val availableFrames = encodedOutput.size / hidden
                val reportedFrames = encodedLength.firstOrNull()?.toInt() ?: availableFrames
                // `encoded_length` can be -1 on some exports; never trust a negative
                // or oversized value over the real buffer.
                val frameCount = reportedFrames.coerceIn(0, availableFrames)
                val values = encodedOutput.copyOf(frameCount * hidden)
                return EncoderOutput(
                    values = values,
                    frameCount = frameCount,
                    state =
                        EncoderState(
                            preCache = newPreCache,
                            cacheLastChannel = newCacheChannel,
                            cacheLastTime = newCacheTime,
                            cacheLastChannelLen = newCacheLen.firstOrNull()?.toLong()?.coerceAtLeast(0L) ?: 0L,
                        ),
                )
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun decode(
        token: Int,
        h: FloatArray,
        c: FloatArray,
    ): DecoderOutput {
        require(h.size == decoderHidden) { "h must hold decoderHidden = $decoderHidden values, was ${h.size}" }
        require(c.size == decoderHidden) { "c must hold decoderHidden = $decoderHidden values, was ${c.size}" }

        val inputs = LinkedHashMap<String, OnnxTensor>()
        val tensors = mutableListOf<OnnxTensor>()
        try {
            tensors +=
                longTensor(longArrayOf(token.toLong()), longArrayOf(1L, 1L)).also {
                    inputs[DECODER_TOKEN] = it
                }
            tensors +=
                floatTensor(h, longArrayOf(1L, 1L, decoderHidden.toLong())).also {
                    inputs[DECODER_H] = it
                }
            tensors +=
                floatTensor(c, longArrayOf(1L, 1L, decoderHidden.toLong())).also {
                    inputs[DECODER_C] = it
                }

            decoder.run(inputs).use { result ->
                return DecoderOutput(
                    decoderOutput = outputFlat(result, DECODER_OUTPUT),
                    hOut = outputFlat(result, DECODER_H_OUT),
                    cOut = outputFlat(result, DECODER_C_OUT),
                )
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun joint(
        encoderFrame: FloatArray,
        decoderOutput: FloatArray,
    ): FloatArray {
        require(encoderFrame.size == hidden) {
            "encoderFrame must hold hidden = $hidden values, was ${encoderFrame.size}"
        }
        require(decoderOutput.size == decoderHidden) {
            "decoderOutput must hold decoderHidden = $decoderHidden values, was ${decoderOutput.size}"
        }

        val inputs = LinkedHashMap<String, OnnxTensor>()
        val tensors = mutableListOf<OnnxTensor>()
        try {
            tensors +=
                floatTensor(encoderFrame, longArrayOf(1L, 1L, hidden.toLong())).also {
                    inputs[JOINT_ENCODER_OUTPUT] = it
                }
            tensors +=
                floatTensor(decoderOutput, longArrayOf(1L, 1L, decoderHidden.toLong())).also {
                    inputs[JOINT_DECODER_OUTPUT] = it
                }

            joint.run(inputs).use { result ->
                val logits = outputFlat(result, JOINT_LOGITS)
                require(logits.size == this.logits) {
                    "joint produced ${logits.size} logits, expected ${this.logits}"
                }
                return logits
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    override fun close() {
        encoder.close()
        decoder.close()
        joint.close()
    }

    private fun preCacheFrames(): Long = (EncoderState.PRE_CACHE_ELEMENTS / PRE_CACHE_BINS).toLong()

    /**
     * Reads an output tensor flattened into a primitive array, failing loudly on
     * an unexpected type rather than guessing.
     */
    private fun <T> outputValue(
        result: OrtSession.Result,
        name: String,
        expected: Class<T>,
    ): T {
        val tensor =
            result.get(name).orElseThrow {
                UnexpectedParakeetTensorException("the graph has no output named \"$name\"")
            }
        val value = tensor.value
        if (!expected.isInstance(value)) {
            throw UnexpectedParakeetTensorException(
                "output \"$name\" was ${value?.let { it::class.java.name } ?: "null"}, expected ${expected.name}",
            )
        }
        return expected.cast(value)
    }

    /**
     * Flattens the encoder's `encoded_output` into a frame-major `[T * hidden]`
     * buffer.
     *
     * ONNX Runtime returns a rank-3 tensor `[1, T, hidden]` as a **nested**
     * `Array<Array<FloatArray>>` (batch -> frame -> hidden), not a flat
     * `FloatArray`; a rank-2 export returns `Array<FloatArray>`. Both are accepted
     * and laid out frame-major so [EncoderOutput.frame] indexes frame 0 first.
     */
    private fun outputFrameMatrix(
        result: OrtSession.Result,
        name: String,
    ): FloatArray {
        val value =
            result
                .get(name)
                .orElseThrow {
                    UnexpectedParakeetTensorException("the graph has no output named \"$name\"")
                }.value
        return when (value) {
            is Array<*> -> {
                val batch = value
                if (batch.isEmpty()) {
                    throw UnexpectedParakeetTensorException("output \"$name\" has an empty batch axis")
                }
                when (val first = batch[0]) {
                    // [1, T, hidden] -> Array<Array<FloatArray>>
                    is Array<*> -> {
                        val out = FloatArray(first.size * hidden)
                        var write = 0
                        for (frame in first) {
                            val row = frame as? FloatArray ?: throw unexpectedFrames(name, frame, "FloatArray")
                            if (row.size != hidden) throw unexpectedFrames(name, row, "length $hidden")
                            row.copyInto(out, write)
                            write += row.size
                        }
                        out
                    }

                    // [T, hidden] -> Array<FloatArray> (a rank-2 export)
                    is FloatArray -> {
                        first.copyOf()
                    }

                    else -> {
                        throw unexpectedFrames(name, first, "Array<FloatArray> or FloatArray")
                    }
                }
            }

            is FloatArray -> {
                value
            }

            else -> {
                throw unexpectedFrames(name, value, "a rank-2/3 float tensor")
            }
        }
    }

    /**
     * Flattens any rank of float output (`FloatArray`, `Array<FloatArray>`, or
     * `Array<Array<FloatArray>>`) into one contiguous buffer, in row-major order.
     */
    private fun outputFlat(
        result: OrtSession.Result,
        name: String,
    ): FloatArray {
        val value =
            result
                .get(name)
                .orElseThrow {
                    UnexpectedParakeetTensorException("the graph has no output named \"$name\"")
                }.value
        val out = FloatArray(floatElementCount(value))
        var write = 0
        val stack = ArrayDeque<Any>()
        value?.let { stack.addLast(it) }
        while (stack.isNotEmpty()) {
            when (val element = stack.removeLast()) {
                is FloatArray -> {
                    element.copyInto(out, write)
                    write += element.size
                }

                is Array<*> -> {
                    // Preserve row-major order: push children in reverse.
                    for (i in element.indices.reversed()) element[i]?.let { stack.addLast(it) }
                }

                else -> {
                    throw unexpectedFrames(name, element, "a float tensor")
                }
            }
        }
        return out
    }

    private fun floatElementCount(value: Any?): Int =
        when (value) {
            is FloatArray -> value.size

            is Array<*> -> value.sumOf { floatElementCount(it) }

            else -> throw UnexpectedParakeetTensorException(
                "float tensor element was ${value?.let { it::class.java.name } ?: "null"}",
            )
        }

    private fun unexpectedFrames(
        name: String,
        value: Any?,
        expected: String,
    ): UnexpectedParakeetTensorException =
        UnexpectedParakeetTensorException(
            "output \"$name\" element was ${value?.let { it::class.java.name } ?: "null"}, expected $expected",
        )

    private fun floatTensor(
        values: FloatArray,
        shape: LongArray,
    ): OnnxTensor = OnnxTensor.createTensor(environment, FloatBuffer.wrap(values), shape)

    private fun longTensor(
        values: LongArray,
        shape: LongArray,
    ): OnnxTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(values), shape)

    companion object {
        /** Intra-op threads for the CPU path; the publisher's reference run uses 4. */
        const val DEFAULT_INTRA_OP_THREADS: Int = 4

        /** `pre_cache` bin count (`1 * 128 * 9`). */
        private const val PRE_CACHE_BINS: Int = 128

        /** The three pinned graph file names, matching [ParakeetEouArtifact.PINNED]. */
        private const val ENCODER_FILE_NAME = "parakeet-eou-encoder.onnx"
        private const val DECODER_FILE_NAME = "parakeet-eou-decoder.onnx"
        private const val JOINT_FILE_NAME = "parakeet-eou-joint.onnx"

        private const val ENCODER_AUDIO_SIGNAL = "audio_signal"
        private const val ENCODER_AUDIO_LENGTH = "audio_length"
        private const val ENCODER_PRE_CACHE = "pre_cache"
        private const val ENCODER_CACHE_LAST_CHANNEL = "cache_last_channel"
        private const val ENCODER_CACHE_LAST_TIME = "cache_last_time"
        private const val ENCODER_CACHE_LAST_CHANNEL_LEN = "cache_last_channel_len"
        private const val ENCODER_OUTPUT = "encoded_output"
        private const val ENCODER_LENGTH = "encoded_length"
        private const val ENCODER_NEW_PRE_CACHE = "new_pre_cache"
        private const val ENCODER_NEW_CACHE_LAST_CHANNEL = "new_cache_last_channel"
        private const val ENCODER_NEW_CACHE_LAST_TIME = "new_cache_last_time"
        private const val ENCODER_NEW_CACHE_LAST_CHANNEL_LEN = "new_cache_last_channel_len"

        private const val DECODER_TOKEN = "token"
        private const val DECODER_H = "h"
        private const val DECODER_C = "c"
        private const val DECODER_OUTPUT = "decoder_output"
        private const val DECODER_H_OUT = "h_out"
        private const val DECODER_C_OUT = "c_out"

        private const val JOINT_ENCODER_OUTPUT = "encoder_output"
        private const val JOINT_DECODER_OUTPUT = "decoder_output"
        private const val JOINT_LOGITS = "logits"

        /**
         * Loads the three graphs from [directory].
         *
         * All five files must already be integrity-verified by
         * [ParakeetEouModelStore]. The [OrtEnvironment] is the shared singleton
         * and is never closed here.
         *
         * @param directory the app-private directory holding the five pinned files.
         * @param config the validated model dimensions; `encoderLayers`,
         *   `attentionContext`, and `convCacheSize` set the cache tensor shapes so
         *   they match the graph.
         * @param intraOpNumThreads CPU intra-op threads; defaults to
         *   [DEFAULT_INTRA_OP_THREADS].
         * @throws UnexpectedParakeetTensorException when a graph file is missing
         *   or cannot be opened.
         */
        fun load(
            directory: File,
            config: ParakeetEouConfig,
            intraOpNumThreads: Int = DEFAULT_INTRA_OP_THREADS,
        ): OnnxParakeetEouRuntime {
            require(intraOpNumThreads > 0) { "intraOpNumThreads must be positive, was $intraOpNumThreads" }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            var encoder: OrtSession? = null
            var decoder: OrtSession? = null
            var joint: OrtSession? = null
            try {
                options.setIntraOpNumThreads(intraOpNumThreads)
                encoder = open(environment, options, File(directory, ENCODER_FILE_NAME))
                decoder = open(environment, options, File(directory, DECODER_FILE_NAME))
                joint = open(environment, options, File(directory, JOINT_FILE_NAME))
                return OnnxParakeetEouRuntime(
                    environment = environment,
                    encoder = encoder,
                    decoder = decoder,
                    joint = joint,
                    hidden = config.encoderHidden,
                    decoderHidden = config.decoderHidden,
                    logits = config.logits,
                    numMelBins = config.numMelBins,
                    encoderLayers = config.encoderLayers,
                    attentionContext = config.attentionContext,
                    convCacheSize = config.convCacheSize,
                )
            } catch (failure: Throwable) {
                encoder?.close()
                decoder?.close()
                joint?.close()
                if (failure is UnexpectedParakeetTensorException) throw failure
                throw UnexpectedParakeetTensorException(
                    "failed to load the Parakeet-EOU graphs from ${directory.name}",
                    failure,
                )
            } finally {
                options.close()
            }
        }

        private fun open(
            environment: OrtEnvironment,
            options: OrtSession.SessionOptions,
            file: File,
        ): OrtSession {
            if (!file.isFile) {
                throw UnexpectedParakeetTensorException("missing model graph ${file.name}")
            }
            return try {
                environment.createSession(file.absolutePath, options)
            } catch (failure: IOException) {
                throw UnexpectedParakeetTensorException("could not open model graph ${file.name}", failure)
            }
        }
    }
}

/**
 * A model graph did not expose the exact tensor name/shape the contract pins.
 *
 * [OnnxParakeetEouRuntime] throws this instead of guessing, so a changed artifact
 * is a loud failure rather than a silently wrong transcript.
 */
class UnexpectedParakeetTensorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
