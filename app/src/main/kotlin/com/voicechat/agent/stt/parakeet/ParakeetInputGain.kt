package com.voicechat.agent.stt.parakeet

import kotlin.math.abs

/**
 * Bounded, peak-based input level normalizer for the Parakeet-EOU front end.
 *
 * **Why it exists.** The pinned Parakeet-EOU ONNX bundle feeds raw log-mel with
 * **no input normalization** (`StreamingMelFrontend`, and the host oracle). The
 * model is therefore sensitive to absolute input level. A Pixel 10 attenuation
 * sweep on a staged microphone capture (2026-10-07; source rms 0.081, peak 0.75)
 * located the raw-decode cliff between ~0.09 peak (decodes) and ~0.06 peak (no
 * tokens): the pass-through decode returned text down to ~0.09 peak and nothing
 * at or below ~0.06 peak, while this normalizer recovered the transcript down to
 * at least ~0.03 peak. Quiet live speech can fall below that cliff, so the live
 * path could return empty transcripts while file playback worked.
 *
 * This class scales each decode chunk toward [targetPeak] with a **bounded** gain,
 * so quiet microphone audio reaches the level the model expects without clipping
 * and without amplifying near-silence:
 *
 * - gain is `targetPeak / peak`, clamped to `[minGain, maxGain]`;
 * - a chunk whose peak is below [signalFloor] is left unchanged (gain `1`), so a
 *   silent chunk cannot be inflated into noise the model might hallucinate on.
 *
 * It is deliberately pure Kotlin (no `android.*`, no ONNX) and applied per 640 ms
 * decode chunk, before the streaming decoder splits the chunk into its
 * overlapping encoder windows.
 *
 * @param targetPeak level each non-silent chunk is normalized toward.
 * @param maxGain most a quiet chunk may be boosted (about +24 dB at 16).
 * @param minGain most a loud chunk may be attenuated, to avoid clipping.
 * @param signalFloor peak below which a chunk counts as silence and is untouched.
 */
class ParakeetInputGain(
    private val targetPeak: Float = DEFAULT_TARGET_PEAK,
    private val maxGain: Float = DEFAULT_MAX_GAIN,
    private val minGain: Float = DEFAULT_MIN_GAIN,
    private val signalFloor: Float = DEFAULT_SIGNAL_FLOOR,
) {
    init {
        require(targetPeak.isFinite() && targetPeak > 0f) { "targetPeak must be positive and finite" }
        require(maxGain.isFinite() && maxGain >= 1f) { "maxGain must be at least 1" }
        require(minGain.isFinite() && minGain > 0f && minGain <= 1f) { "minGain must be in (0, 1]" }
        require(signalFloor.isFinite() && signalFloor >= 0f) { "signalFloor must not be negative" }
    }

    /**
     * The gain [apply] uses for the first [count] samples of [chunk]: `1` when the
     * chunk is silence or already within range, otherwise the clamped
     * `targetPeak / peak`.
     */
    fun gainFor(
        chunk: FloatArray,
        count: Int = chunk.size,
    ): Float {
        var peak = 0f
        for (index in 0 until count) {
            val magnitude = abs(chunk[index])
            if (magnitude > peak) peak = magnitude
        }
        if (!peak.isFinite() || peak < signalFloor) return 1f
        return (targetPeak / peak).coerceIn(minGain, maxGain)
    }

    /** Scales the first [count] samples of [chunk] in place by [gainFor]. */
    fun apply(
        chunk: FloatArray,
        count: Int = chunk.size,
        gain: Float = gainFor(chunk, count),
    ) {
        if (gain == 1f) return
        for (index in 0 until count) chunk[index] *= gain
    }

    companion object {
        /** Matches the peak of the pinned evaluation clips (~0.85); a little headroom kept. */
        const val DEFAULT_TARGET_PEAK: Float = 0.8f

        /** ~+24 dB ceiling: enough for quiet far-field speech, not unbounded. */
        const val DEFAULT_MAX_GAIN: Float = 16f

        /** ~-12 dB ceiling: hot input is brought down rather than clipped. */
        const val DEFAULT_MIN_GAIN: Float = 0.25f

        /** ~-38 dBFS; below this a chunk is treated as silence and left alone. */
        const val DEFAULT_SIGNAL_FLOOR: Float = 0.012f

        /** Passes chunks through unchanged; used by tests that assert raw PCM conversion. */
        val PASSTHROUGH: ParakeetInputGain = ParakeetInputGain(targetPeak = 1f, maxGain = 1f, minGain = 1f, signalFloor = 0f)
    }
}
