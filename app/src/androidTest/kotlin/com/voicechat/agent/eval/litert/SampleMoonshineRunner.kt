/*
 * Derived from Google AI Edge's LiteRT speech-recognition sample, pinned at
 * scripts/litert-sample-source.commit. Original sample source is Apache-2.0:
 * https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition
 *
 * Copyright 2026 Google LLC.
 * Licensed under the Apache License, Version 2.0.
 */
package com.voicechat.agent.eval.litert

import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.TensorBuffer
import com.google.ai.edge.litert.TensorType
import java.io.File
import kotlin.math.sqrt

/**
 * Port of the sample runner's encoder plus default autoregressive decoder path.
 *
 * The accelerator allowlist is explicit so a caller can request CPU-only, or a
 * single non-CPU accelerator with no CPU entry. The class does not itself infer
 * which accelerator executed; it only reports the runtime's available
 * accelerators and the set it requested. Placement evidence must come from the
 * runtime or a profiler, never from this request.
 */
internal class SampleMoonshineRunner(
    modelFile: File,
    accelerators: Set<Accelerator> = setOf(Accelerator.CPU),
    gpuOptions: CompiledModel.GpuOptions? = null,
) : AutoCloseable {
    private val environment = Environment.create()

    /**
     * Accelerators the loaded runtime reports as available on this device.
     * Resolved reflectively because the Kotlin API surface exposes this only as
     * a JVM method; an unavailable query yields an empty set rather than failing
     * the run.
     */
    val availableAccelerators: Set<Accelerator> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            Environment::class.java
                .getMethod("getAvailableAccelerators")
                .invoke(environment) as Set<Accelerator>
        }.getOrElse { emptySet() }

    private val compiledModel =
        CompiledModel.create(
            modelFile.absolutePath,
            CompiledModel.Options(accelerators).also { options ->
                if (gpuOptions != null) options.gpuOptions = gpuOptions
            },
            environment,
        )

    private val inputBuffers = compiledModel.createInputBuffers()
    private val outputBuffers = compiledModel.createOutputBuffers()
    private val decoder = DefaultDecoder(compiledModel)
    private val expectedInputFloats =
        compiledModel.getInputTensorType(inputBufferName(0), ENCODE_SIGNATURE).numElements

    override fun close() {
        compiledModel.close()
    }

    /** Runs the sample's encode signature and returns its output buffers. */
    fun encode(features: FloatArray): List<TensorBuffer> {
        val paddedFeatures =
            when {
                features.size == expectedInputFloats -> features
                features.size < expectedInputFloats -> features.copyOf(expectedInputFloats)
                else -> features.takeLast(expectedInputFloats).toFloatArray()
            }
        inputBuffers[0].writeFloat(paddedFeatures)
        compiledModel.run(inputBuffers, outputBuffers, ENCODE_SIGNATURE)
        return outputBuffers
    }

    /** Runs the sample's default autoregressive decoder over encoded buffers. */
    fun decode(encodedOutputs: List<TensorBuffer>): Sequence<Int> = decoder.decode(encodedOutputs)

    /** Mirrors LiteRtRunner.DefaultDecoder from the pinned sample for Moonshine. */
    private class DefaultDecoder(
        private val model: CompiledModel,
    ) {
        private val inputBuffers = model.createInputBuffers(DECODE_SIGNATURE)
        private val tokenIdsBuffer = inputBuffers[inputBuffers.size - 2]
        private val maskBuffer = inputBuffers[inputBuffers.size - 1]
        private val outputBuffers = model.createOutputBuffers(DECODE_SIGNATURE)

        private val numTokenIds =
            model
                .getInputTensorType(inputBufferName(inputBuffers.size - 2), DECODE_SIGNATURE)
                .numElements
        private val logitsPerToken =
            model.getOutputTensorType(outputBufferName(0), DECODE_SIGNATURE).numElements /
                numTokenIds

        private val causalMask: FloatArray =
            run {
                val entries =
                    model
                        .getInputTensorType(inputBufferName(inputBuffers.size - 1), DECODE_SIGNATURE)
                        .numElements
                val side = sqrt(entries.toDouble()).toInt()
                FloatArray(entries) { MASKED_OUT }
                    .also { mask ->
                        for (row in 0 until side) {
                            for (column in 0..row) {
                                mask[row * side + column] = MASKED_IN
                            }
                        }
                    }
            }

        fun decode(encodeOutputs: List<TensorBuffer>): Sequence<Int> =
            sequence {
                val decodeInputs =
                    buildList {
                        addAll(encodeOutputs)
                        add(tokenIdsBuffer)
                        add(maskBuffer)
                    }
                maskBuffer.writeFloat(causalMask)

                val tokenIds = IntArray(numTokenIds)
                tokenIds[0] = MOONSHINE_START_TOKEN
                for (index in 0 until numTokenIds - 1) {
                    tokenIdsBuffer.writeInt(tokenIds)
                    model.run(decodeInputs, outputBuffers, DECODE_SIGNATURE)

                    val logits = outputBuffers[0].readFloat()
                    val start = index * logitsPerToken
                    val end = (index + 1) * logitsPerToken
                    val token =
                        ((start until end).maxByOrNull { logits[it] } ?: start) - start
                    if (token == MOONSHINE_STOP_TOKEN) break
                    yield(token)
                    tokenIds[index + 1] = token
                }
                yield(END_OF_SEQUENCE)
            }
    }

    private companion object {
        const val ENCODE_SIGNATURE = "encode"
        const val DECODE_SIGNATURE = "decode"
        const val END_OF_SEQUENCE = -1
        const val MOONSHINE_START_TOKEN = 1
        const val MOONSHINE_STOP_TOKEN = 2
        const val MASKED_IN = 0.0f
        const val MASKED_OUT = -0.7f * Float.MAX_VALUE

        fun inputBufferName(index: Int) = "args_$index"

        fun outputBufferName(index: Int) = "output_$index"

        val TensorType.numElements: Int
            get() = layout!!.dimensions.fold(1, Int::times)
    }
}
