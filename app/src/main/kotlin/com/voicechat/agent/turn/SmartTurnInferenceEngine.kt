package com.voicechat.agent.turn

/**
 * Runs the pinned Smart Turn v3.2 graph on one prepared window.
 *
 * This is a deliberately tiny seam with **no ONNX or Android type in it**, so the
 * detector contract, the window handling, and the probability→verdict mapping are
 * all proven by JVM unit tests with a fake engine and no native library or 11 MB
 * model (mirroring the M09 `TurnCompletionDetector` fake approach). The only
 * implementation that touches `ai.onnxruntime` is
 * [OnnxSmartTurnEngine]/`OnnxSmartTurnEngine.kt`.
 *
 * **Threading.** [probability] is a blocking call; callers run it off the main
 * thread (the detector does this on [kotlinx.coroutines.Dispatchers.Default]).
 */
interface SmartTurnInferenceEngine : AutoCloseable {
    /**
     * @param samples a float32 buffer of exactly the model's window length,
     *   already right-aligned and zero-padded by [SmartTurnWindow].
     * @return the probability in `[0, 1]` that the window completes the turn.
     */
    fun probability(samples: FloatArray): Float

    /** Releases the loaded session. Idempotent; safe to call from any thread. */
    override fun close()
}
