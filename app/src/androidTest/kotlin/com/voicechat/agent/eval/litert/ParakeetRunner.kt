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

/** Shared timing record for the Parakeet TDT/CTC comparison spike. */
internal data class ParakeetRunResult(
    val encodeMs: Long,
    val decodeMs: Long,
    val tokenIds: IntArray,
    val decodeIterations: Int,
    val encodeOutputShape: List<Int>,
    val modelLoadMs: Long,
)

/**
 * Port of the pinned sample's Parakeet encode plus the CTC and stateful TDT
 * decoders. The accelerator allowlist is explicit so a caller can request
 * CPU-only, or a single non-CPU accelerator with no CPU entry; the class does
 * not itself infer which accelerator executed. It emits token IDs only; text
 * mapping is a separate, explicitly-external step and is not accuracy.
 */
internal class ParakeetRunner(
    modelFile: File,
    private val mode: Mode,
    accelerators: Set<Accelerator> = setOf(Accelerator.CPU),
    gpuOptions: CompiledModel.GpuOptions? = null,
) : AutoCloseable {
    enum class Mode {
        CTC,
        TDT,
    }

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
    private val expectedInputFloats =
        compiledModel.getInputTensorType(inputBufferName(0), ENCODE_SIGNATURE).numElements
    private val encodeOutputFloats =
        compiledModel.getOutputTensorType(outputBufferName(0), ENCODE_SIGNATURE).numElements
    private val encodeOutputDims =
        compiledModel
            .getOutputTensorType(outputBufferName(0), ENCODE_SIGNATURE)
            .layout!!
            .dimensions
            .toList()

    val modelLoadMs: Long = 0

    override fun close() {
        compiledModel.close()
    }

    fun run(features: FloatArray): ParakeetRunResult {
        val loadStart = android.os.SystemClock.elapsedRealtimeNanos()
        val padded =
            when {
                features.size == expectedInputFloats -> features
                features.size < expectedInputFloats -> features.copyOf(expectedInputFloats)
                else -> features.takeLast(expectedInputFloats).toFloatArray()
            }
        inputBuffers[0].writeFloat(padded)
        val encodeStart = android.os.SystemClock.elapsedRealtimeNanos()
        compiledModel.run(inputBuffers, outputBuffers, ENCODE_SIGNATURE)
        val encodeMs = elapsed(encodeStart)
        val loadMs = elapsed(loadStart) - encodeMs

        return when (mode) {
            Mode.CTC -> ctc(outputBuffers[0], encodeMs, loadMs)
            Mode.TDT -> tdt(outputBuffers, encodeMs, loadMs)
        }
    }

    private fun ctc(
        logitsBuffer: TensorBuffer,
        encodeMs: Long,
        loadMs: Long,
    ): ParakeetRunResult {
        val decodeStart = android.os.SystemClock.elapsedRealtimeNanos()
        val logits = logitsBuffer.readFloat()
        val vocab = CTC_VOCAB
        val frames = logits.size / vocab
        val tokenIds = ArrayList<Int>()
        var previous = -1
        for (frame in 0 until frames) {
            val start = frame * vocab
            val best = (start until start + vocab).maxByOrNull { logits[it] } ?: start
            val token = best - start
            if (token != previous) {
                // CTC blank is the last index; keep blanks out of the raw stream
                // and let a later text step collapse them.
                if (token != CTC_BLANK) tokenIds.add(token)
                previous = token
            }
        }
        return ParakeetRunResult(
            encodeMs = encodeMs,
            decodeMs = elapsed(decodeStart),
            tokenIds = tokenIds.toIntArray(),
            decodeIterations = frames,
            encodeOutputShape = encodeOutputDims,
            modelLoadMs = loadMs,
        )
    }

    private fun tdt(
        encodeOutputs: List<TensorBuffer>,
        encodeMs: Long,
        loadMs: Long,
    ): ParakeetRunResult {
        val decodeStart = android.os.SystemClock.elapsedRealtimeNanos()
        val decodeInputs = compiledModel.createInputBuffers(DECODE_SIGNATURE)
        val decodeOutputs = compiledModel.createOutputBuffers(DECODE_SIGNATURE)
        val decodeOneInputs = compiledModel.createInputBuffers(DECODE_ONE_SIGNATURE)
        val decodeOneOutputs = compiledModel.createOutputBuffers(DECODE_ONE_SIGNATURE)
        try {
            // Encoder output is shared zero-copy by handing the same buffer to both
            // decode signatures; the sample assigns decoder inputs[0] = encode output.
            val decode0 = mutableListOf(encodeOutputs[0], decodeInputs[1], decodeInputs[2], decodeInputs[3])
            val decode1 = mutableListOf(encodeOutputs[0], decodeOneInputs[1], decodeOneInputs[2], decodeOneInputs[3])

            val maxTimeIndex = encodeOutputDims.last()
            val numTokenIds =
                compiledModel.getInputTensorType(inputBufferName(1), DECODE_SIGNATURE).numElements
            val logitsPer =
                compiledModel.getOutputTensorType(outputBufferName(0), DECODE_SIGNATURE).numElements /
                    numTokenIds / maxTimeIndex

            val numStates =
                compiledModel.getInputTensorType(inputBufferName(2), DECODE_SIGNATURE).numElements
            val zeroStates = FloatArray(numStates)
            // States: [input state buffers] and [output state buffers] ping-pong.
            val statesIn = listOf(decodeInputs[2], decodeInputs[3])
            val statesOut = listOf(decodeOutputs[1], decodeOutputs[2])
            var statesIndex = 0
            statesIn.forEach { it.writeFloat(zeroStates) }

            var signature = DECODE_SIGNATURE
            var numInferenceTokens = numTokenIds
            var tokenIdsBuffer = decodeInputs[1]
            var tokenIds = IntArray(numTokenIds).also { it[0] = TDT_BLANK }
            var logits = decodeOutputs[0]
            var tokenIndex = 0
            var timeIndex = 0
            var iterations = 0
            val emitted = ArrayList<Int>()

            while (timeIndex < maxTimeIndex) {
                tokenIdsBuffer.writeInt(tokenIds)
                val inputs = if (signature == DECODE_SIGNATURE) decode0 else decode1
                val outputs =
                    if (signature == DECODE_SIGNATURE) {
                        listOf(logits, statesOut[1 - statesIndex], statesOut[statesIndex])
                    } else {
                        listOf(logits, decodeOneOutputs[1], decodeOneOutputs[2])
                    }
                compiledModel.run(inputs, outputs, signature)
                iterations++

                val values = logits.readFloat()
                val base = timeIndex * numInferenceTokens * logitsPer + tokenIndex * logitsPer
                val endDuration = base + logitsPer
                val endToken = endDuration - NUM_DURATIONS
                val token = ((base until endToken).maxByOrNull { values[it] } ?: base) - base
                if (token != TDT_BLANK) {
                    emitted.add(token)
                    if (numInferenceTokens > 1) {
                        tokenIndex++
                        if (tokenIndex >= numInferenceTokens) {
                            signature = DECODE_ONE_SIGNATURE
                            numInferenceTokens = 1
                            tokenIdsBuffer = decodeOneInputs[1]
                            tokenIds = IntArray(1)
                            logits = decodeOneOutputs[0]
                            tokenIndex = 0
                        }
                    }
                    tokenIds[tokenIndex] = token
                }
                val duration = ((endToken until endDuration).maxByOrNull { values[it] } ?: endToken) - endToken
                timeIndex += if (duration == 0 && token == TDT_BLANK) 1 else duration
                if (numInferenceTokens == 1 && token != TDT_BLANK) statesIndex = 1 - statesIndex
            }

            return ParakeetRunResult(
                encodeMs = encodeMs,
                decodeMs = elapsed(decodeStart),
                tokenIds = emitted.toIntArray(),
                decodeIterations = iterations,
                encodeOutputShape = encodeOutputDims,
                modelLoadMs = loadMs,
            )
        } finally {
            // The buffers created for this decode are not owned by close();
            // release them so repeated runs do not leak native memory.
            (decodeInputs + decodeOutputs + decodeOneInputs + decodeOneOutputs).forEach { it.close() }
        }
    }

    private fun elapsed(startNanos: Long) = (android.os.SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000L

    private companion object {
        const val ENCODE_SIGNATURE = "encode"
        const val DECODE_SIGNATURE = "decode"
        const val DECODE_ONE_SIGNATURE = "decode_1"
        const val CTC_VOCAB = 1025
        const val CTC_BLANK = 1024
        const val TDT_BLANK = 8192
        const val NUM_DURATIONS = 5

        fun inputBufferName(index: Int) = "args_$index"

        fun outputBufferName(index: Int) = "output_$index"

        val TensorType.numElements: Int
            get() = layout!!.dimensions.fold(1, Int::times)
    }
}
