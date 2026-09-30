package com.voicechat.agent.vad

import com.voicechat.agent.audio.AudioRouteType

/**
 * Validated thresholds for the measured-audio voice activity detector (M09).
 *
 * M00 deferred VAD to M09 with the constraint that **no VAD model is
 * allow-listed**: ONNX Runtime is scoped to Smart Turn and no TFLite VAD
 * artifact is verified (`docs/decisions.md` §2.3). The detector therefore works
 * from measured audio characteristics — frame RMS plus zero-crossing rate — and
 * this value type is the one place those thresholds are declared, validated,
 * and made route-aware.
 *
 * **Validation is mandatory and happens in the constructor**, before any audio
 * is processed, so an invalid threshold or silence cap can never silently
 * disable endpointing. RMS thresholds are normalized to `[0, 1]` against 16-bit
 * full scale; [maxSilenceMillis] is a `Long` (always finite) and must be
 * non-negative.
 *
 * **Provisional values.** Every default here is a starting point, not a measured
 * Pixel 10 baseline: the voice-quality plan forbids copying another project's
 * gain/RMS thresholds, and M25 owns calibration (`docs/decisions.md` §2.3,
 * `docs/voice-quality-and-latency.md`, R-0045).
 */
data class VadConfig(
    /** Frame RMS at or above which speech onset may start, normalized to `[0, 1]`. */
    val onsetRmsThreshold: Float = DEFAULT_ONSET_RMS_THRESHOLD,
    /** Frame RMS below which speech is considered paused; lower than onset for hysteresis. */
    val hangoverRmsThreshold: Float = DEFAULT_HANGOVER_RMS_THRESHOLD,
    /** Maximum zero-crossing rate still considered speech-like; rejects hiss/transients. */
    val maxZeroCrossingRate: Float = DEFAULT_MAX_ZERO_CROSSING_RATE,
    /** Consecutive onset-qualifying frames required before speech starts (~60 ms at 20 ms frames). */
    val onsetFrames: Int = DEFAULT_ONSET_FRAMES,
    /** Consecutive sub-hangover frames required before a candidate pause (~280 ms). */
    val pauseFrames: Int = DEFAULT_PAUSE_FRAMES,
    /**
     * Bounded maximum silence, in milliseconds, after a candidate pause. When it
     * elapses the turn always finalizes, with or without Smart Turn, so a
     * trailing turn can never hang open. Zero endpoints at the candidate pause.
     */
    val maxSilenceMillis: Long = DEFAULT_MAX_SILENCE_MILLIS,
    /** Recent-audio window handed to an optional semantic detector at a candidate pause. */
    val semanticWindowMillis: Long = DEFAULT_SEMANTIC_WINDOW_MILLIS,
) {
    init {
        require(onsetRmsThreshold.isFinite() && onsetRmsThreshold > 0f) {
            "onsetRmsThreshold must be a positive finite value, was $onsetRmsThreshold"
        }
        require(hangoverRmsThreshold.isFinite() && hangoverRmsThreshold > 0f) {
            "hangoverRmsThreshold must be a positive finite value, was $hangoverRmsThreshold"
        }
        require(hangoverRmsThreshold <= onsetRmsThreshold) {
            "hangoverRmsThreshold ($hangoverRmsThreshold) must not exceed onsetRmsThreshold ($onsetRmsThreshold)"
        }
        require(maxZeroCrossingRate.isFinite() && maxZeroCrossingRate in 0f..1f) {
            "maxZeroCrossingRate must be finite and within [0, 1], was $maxZeroCrossingRate"
        }
        require(onsetFrames >= 1) { "onsetFrames must be at least 1, was $onsetFrames" }
        require(pauseFrames >= 1) { "pauseFrames must be at least 1, was $pauseFrames" }
        require(maxSilenceMillis >= 0L) { "maxSilenceMillis must not be negative, was $maxSilenceMillis" }
        require(semanticWindowMillis >= 0L) { "semanticWindowMillis must not be negative, was $semanticWindowMillis" }
    }

    companion object {
        /** ~0.02 RMS, deliberately low; M25 calibrates this on device. */
        const val DEFAULT_ONSET_RMS_THRESHOLD: Float = 0.02f

        /** ~0.01 RMS hysteresis floor that keeps an active turn open through soft frames. */
        const val DEFAULT_HANGOVER_RMS_THRESHOLD: Float = 0.01f

        /** 0.5 is permissive; energy is the primary gate and this rejects clearly hissy frames. */
        const val DEFAULT_MAX_ZERO_CROSSING_RATE: Float = 0.5f

        /** 3 frames = ~60 ms onset latency at the 20 ms capture frame. */
        const val DEFAULT_ONSET_FRAMES: Int = 3

        /** 14 frames = ~280 ms; longer than a word gap, shorter than the 400 ms `|` pause. */
        const val DEFAULT_PAUSE_FRAMES: Int = 14

        /** 2000 ms cap; matches no vendor default and must be re-measured (R-0045). */
        const val DEFAULT_MAX_SILENCE_MILLIS: Long = 2_000L

        /** 8000 ms is the window Smart Turn v3.2 evaluates (M10). */
        const val DEFAULT_SEMANTIC_WINDOW_MILLIS: Long = 8_000L

        /** Provisional wired-headset thresholds: a fixed link is usually cleaner and louder. */
        private const val WIRED_ONSET_RMS_THRESHOLD: Float = 0.024f
        private const val WIRED_HANGOVER_RMS_THRESHOLD: Float = 0.012f

        /** Provisional Bluetooth thresholds: link noise raises the floor, so onset/floor rise. */
        private const val BLUETOOTH_ONSET_RMS_THRESHOLD: Float = 0.028f
        private const val BLUETOOTH_HANGOVER_RMS_THRESHOLD: Float = 0.014f

        /** The unmeasured built-in-mic defaults. */
        fun default(): VadConfig = VadConfig()

        /**
         * Returns the provisional tuning for one input route kind.
         *
         * Route kinds are the privacy-safe [AudioRouteType] values measured at the
         * capture boundary, not device names. These thresholds are placeholder
         * starting points only; a device run records and replaces them (R-0045).
         */
        fun forRoute(route: AudioRouteType): VadConfig =
            when {
                route.isBluetooth -> {
                    VadConfig(
                        onsetRmsThreshold = BLUETOOTH_ONSET_RMS_THRESHOLD,
                        hangoverRmsThreshold = BLUETOOTH_HANGOVER_RMS_THRESHOLD,
                    )
                }

                route == AudioRouteType.WIRED_HEADSET || route == AudioRouteType.USB_HEADSET -> {
                    VadConfig(
                        onsetRmsThreshold = WIRED_ONSET_RMS_THRESHOLD,
                        hangoverRmsThreshold = WIRED_HANGOVER_RMS_THRESHOLD,
                    )
                }

                else -> {
                    default()
                }
            }
    }
}
