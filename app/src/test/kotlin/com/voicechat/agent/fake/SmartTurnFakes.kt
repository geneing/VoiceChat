package com.voicechat.agent.fake

import com.voicechat.agent.turn.ContentHasher
import com.voicechat.agent.turn.SmartTurnInferenceEngine
import java.io.File

/**
 * Deterministic [SmartTurnInferenceEngine] for JVM tests.
 *
 * It returns a fixed [probability] (or throws [failure]) and counts calls, so a
 * test can prove the detector is evaluated **once per candidate pause** and that
 * no native ONNX runtime or 11 MB model is needed.
 */
class FakeSmartTurnInferenceEngine(
    var probability: Float = 0.9f,
    var failure: Throwable? = null,
) : SmartTurnInferenceEngine {
    /** Number of [probability] calls. */
    var inferenceCount: Int = 0
        private set

    /** Every window handed to [probability], for alignment assertions. */
    val inputs: MutableList<FloatArray> = mutableListOf()

    /** True once [close] has been called. */
    var closed: Boolean = false
        private set

    override fun probability(samples: FloatArray): Float {
        inferenceCount++
        inputs += samples
        failure?.let { throw it }
        return probability
    }

    override fun close() {
        closed = true
    }
}

/**
 * Deterministic [ContentHasher] that returns a fixed value, so install/verify
 * tests never hash a real artifact.
 */
class FakeContentHasher(
    var sha256: String? = null,
) : ContentHasher {
    /** Number of [sha256] calls. */
    var hashCount: Int = 0
        private set

    override fun sha256(file: File): String? {
        hashCount++
        return sha256
    }
}
