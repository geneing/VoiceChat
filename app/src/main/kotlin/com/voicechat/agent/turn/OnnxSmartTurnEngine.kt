package com.voicechat.agent.turn

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer

/**
 * The only ONNX Runtime-backed [SmartTurnInferenceEngine] (M10).
 *
 * It loads the pinned `smart-turn-v3.2-int8.onnx` graph from app-private storage
 * and runs the CPU execution provider. The architecture deliberately scopes ONNX
 * Runtime to this artifact only ([docs/decisions.md](../docs/decisions.md) §3.1):
 * no other ONNX model is loaded, and the NNAPI execution provider is not used
 * (the decision record starts with the deterministic CPU path and defers
 * accelerator benchmarking to the device run).
 *
 * The graph embeds the Whisper log-mel + normalization front-end, so
 * [probability] feeds the prepared float32 window and reads one output value. The
 * [OrtEnvironment] is the shared process singleton and is **not** closed here;
 * only the session is released, so closing one detector does not tear down the
 * runtime for another.
 *
 * This file is never referenced by JVM unit tests; they use a fake
 * [SmartTurnInferenceEngine], so the native library and the 11 MB model are not
 * required to prove the detector behavior.
 */
class OnnxSmartTurnEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val windowSamples: Int,
) : SmartTurnInferenceEngine {
    override fun probability(samples: FloatArray): Float {
        require(samples.size == windowSamples) {
            "Smart Turn expects a $windowSamples-sample window, was ${samples.size}"
        }
        OnnxTensor
            .createTensor(environment, FloatBuffer.wrap(samples), longArrayOf(1L, samples.size.toLong()))
            .use { input ->
                session.run(mapOf(SmartTurnArtifact.INPUT_NAME to input)).use { result ->
                    val output = result.get(0).value
                    return when (output) {
                        is Array<*> -> {
                            val row = output[0]
                            if (row is FloatArray && row.isNotEmpty()) {
                                row[0]
                            } else {
                                error("unexpected Smart Turn output elements")
                            }
                        }

                        else -> {
                            error("unexpected Smart Turn output type ${output::class.java.name}")
                        }
                    }
                }
            }
    }

    override fun close() {
        session.close()
    }

    companion object {
        /** Intra-op threads for the CPU path; the publisher's reference run uses 2. */
        private const val INTRA_OP_THREADS = 2

        /**
         * Loads the graph from [modelFile].
         *
         * The caller must have verified the file against the pinned artifact
         * (size + SHA-256) first: this only loads it.
         */
        fun load(
            modelFile: File,
            config: SmartTurnConfig = SmartTurnConfig.default(),
        ): OnnxSmartTurnEngine {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(INTRA_OP_THREADS)
                val session = environment.createSession(modelFile.absolutePath, options)
                return OnnxSmartTurnEngine(environment, session, config.windowSamples)
            } finally {
                options.close()
            }
        }
    }
}
