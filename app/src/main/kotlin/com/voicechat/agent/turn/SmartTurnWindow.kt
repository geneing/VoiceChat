package com.voicechat.agent.turn

/**
 * Prepares one raw 16-bit PCM window for the pinned Smart Turn graph.
 *
 * The graph's contract is "most recent audio last, zeros at the front"
 * (`docs/decisions.md` §3.3): the current logical turn's audio is right-aligned
 * in a fixed [windowSamples]-wide buffer, and any missing samples at the front
 * are zeros. When the turn is longer than the window the earliest audio is
 * dropped, so only the most recent window is classified.
 *
 * The samples are fed as raw `Float` values (not divided to `[-1, 1]`): the
 * graph embeds the zero-mean/unit-variance waveform normalization, so scaling is
 * mathematically irrelevant, and the publisher's reference usage feeds the raw
 * PCM cast to float32. No log-mel feature extraction happens here.
 */
internal object SmartTurnWindow {
    /** Right-aligns [samples] into a zero-padded [windowSamples] float32 buffer. */
    fun prepare(
        samples: ShortArray,
        windowSamples: Int,
    ): FloatArray {
        require(windowSamples > 0) { "windowSamples must be positive, was $windowSamples" }
        val result = FloatArray(windowSamples)
        val take = minOf(samples.size, windowSamples)
        val padStart = windowSamples - take
        val sourceStart = samples.size - take
        for (index in 0 until take) {
            result[padStart + index] = samples[sourceStart + index].toFloat()
        }
        return result
    }
}
