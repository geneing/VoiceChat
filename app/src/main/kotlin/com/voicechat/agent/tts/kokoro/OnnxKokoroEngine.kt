package com.voicechat.agent.tts.kokoro

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ONNX Runtime-backed inference for one pinned distilled Kokoro voice graph (M28).
 *
 * ONNX Runtime is scoped to the reviewed Kokoro and Smart Turn artifacts
 * (`docs/decisions.md` §3.1/§3.4, `docs/model-runtime.md`); this engine does not
 * load arbitrary user-provided graphs. It uses the deterministic CPU path with
 * two intra-op threads. The [OrtEnvironment] is the shared process singleton
 * and is not closed here; only this voice's session is released.
 *
 * The graph is verified by [KokoroModelStore] before load. Its excitation is
 * random, so repeated calls are not bit-identical; callers must not depend on
 * deterministic samples.
 */
class OnnxKokoroEngine private constructor(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
) : KokoroSynthesizer {
    private val closed = AtomicBoolean(false)

    override fun synthesize(
        ids: IntArray,
        speed: Float,
    ): FloatArray {
        check(!closed.get()) { "Kokoro engine is closed" }
        require(ids.size in 2..(KokoroArtifact.MAX_PHONEMES + 2)) {
            "Kokoro expects 2 to ${KokoroArtifact.MAX_PHONEMES + 2} padded ids, was ${ids.size}"
        }
        require(ids.first() == PADDING_ID && ids.last() == PADDING_ID) {
            "Kokoro input_ids must have padding id 0 at both ends"
        }
        require(speed.isFinite() && speed > 0f) { "Kokoro speed must be a positive finite value" }

        val inputIds = LongArray(ids.size) { ids[it].toLong() }
        val inputsShape = longArrayOf(1L, ids.size.toLong())
        OnnxTensor
            .createTensor(environment, LongBuffer.wrap(inputIds), inputsShape)
            .use { idsTensor ->
                OnnxTensor
                    .createTensor(environment, FloatBuffer.wrap(floatArrayOf(speed)), longArrayOf(1L))
                    .use { speedTensor ->
                        session
                            .run(
                                mapOf(
                                    KokoroArtifact.INPUT_IDS to idsTensor,
                                    KokoroArtifact.INPUT_SPEED to speedTensor,
                                ),
                                setOf(KokoroArtifact.OUTPUT_WAVEFORM),
                            ).use { result ->
                                check(result.size() == 1) { "Kokoro graph did not return its waveform output" }
                                return waveform(result.get(0).value)
                            }
                    }
            }
    }

    private fun waveform(value: Any?): FloatArray =
        when (value) {
            is Array<*> -> {
                check(value.size == 1) { "Kokoro waveform must have batch size 1" }
                val samples = value[0]
                check(samples is FloatArray && samples.isNotEmpty()) {
                    "Kokoro waveform output must be a non-empty float32 [1, N] tensor"
                }
                samples
            }

            null -> {
                error("Kokoro graph returned no waveform value")
            }

            else -> {
                error("Kokoro waveform output had unexpected type ${value::class.java.name}")
            }
        }

    override fun close() {
        if (closed.compareAndSet(false, true)) session.close()
    }

    companion object {
        /** The deterministic CPU path uses the publisher's two intra-op threads. */
        private const val INTRA_OP_THREADS = 2
        private const val PADDING_ID = 0

        /**
         * Loads the graph at [modelFile]. Callers must verify it with KokoroModelStore
         * first and invoke this blocking load away from the main thread.
         */
        fun load(modelFile: File): OnnxKokoroEngine {
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions()
            try {
                options.setIntraOpNumThreads(INTRA_OP_THREADS)
                val session = environment.createSession(modelFile.absolutePath, options)
                return OnnxKokoroEngine(environment, session)
            } finally {
                options.close()
            }
        }
    }
}
