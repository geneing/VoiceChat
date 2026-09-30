package com.voicechat.agent.turn

/**
 * Validated configuration for the Smart Turn v3.2 completion decision (M10).
 *
 * Mirrors the style of the M09 [com.voicechat.agent.vad.VadConfig]: the value
 * type is the single place the decision parameters are declared **and validated**,
 * and validation happens in the constructor before any audio is processed, so the
 * window length or threshold can never silently become something the pinned graph
 * cannot honour.
 *
 * **The pinned graph has a fixed contract** (`docs/decisions.md` §3.3): float32
 * `[1, 128000]` at 16 kHz mono, `probability` in `[0, 1]`, complete when the
 * probability is above 0.5. [windowSamples] stays configurable for a future
 * artifact but defaults to that exact graph shape, and the sample rate is
 * constrained to the model's 16 kHz assumption.
 */
data class SmartTurnConfig(
    /** Sample rate the model expects; the pinned graph requires 16 kHz. */
    val sampleRateHz: Int = SUPPORTED_SAMPLE_RATE_HZ,
    /** Window length in samples; the pinned graph requires [WINDOW_SAMPLES]. */
    val windowSamples: Int = WINDOW_SAMPLES,
    /** A candidate pause is complete when the probability is greater than this. */
    val completionThreshold: Float = DEFAULT_COMPLETION_THRESHOLD,
) {
    init {
        require(sampleRateHz == SUPPORTED_SAMPLE_RATE_HZ) {
            "Smart Turn requires a $SUPPORTED_SAMPLE_RATE_HZ Hz sample rate, was $sampleRateHz"
        }
        require(windowSamples > 0) { "windowSamples must be positive, was $windowSamples" }
        require(completionThreshold.isFinite() && completionThreshold > 0f && completionThreshold < 1f) {
            "completionThreshold must be a finite value strictly between 0 and 1, was $completionThreshold"
        }
    }

    companion object {
        /** The pinned graph's sample rate (`config.json`). */
        const val SUPPORTED_SAMPLE_RATE_HZ: Int = 16_000

        /** The pinned graph's window: 8 s at 16 kHz (`config.json`). */
        const val WINDOW_SAMPLES: Int = 128_000

        /** The publisher's documented threshold: complete if probability > 0.5. */
        const val DEFAULT_COMPLETION_THRESHOLD: Float = 0.5f

        /** The unmeasured defaults; M25 decides whether the artifact/default changes. */
        fun default(): SmartTurnConfig = SmartTurnConfig()
    }
}
