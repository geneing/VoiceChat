package com.voicechat.agent.replay

import com.voicechat.agent.domain.AudioFormat
import java.lang.StrictMath

/** Shape and pace of the in-repo synthetic voice. */
data class SyntheticVoiceProfile(
    val baseFrequencyHz: Double = 140.0,
    val syllableMillis: Int = 100,
    val wordGapMillis: Int = 80,
    val shortPauseMillis: Int = 150,
    val longPauseMillis: Int = 320,
    val pauseMillis: Int = 400,
    val noiseFloor: Double = 0.002,
    val amplitude: Double = 0.32,
)

/** Kind of one generated timeline segment. */
enum class SyntheticSegmentKind {
    VOICED,
    GAP,
    SHORT_PAUSE,
    LONG_PAUSE,
    PAUSE,
}

/** A generated segment's kind and sample range in [SynthesizedSpeech.samples]. */
data class SyntheticSegment(
    val kind: SyntheticSegmentKind,
    val startSample: Int,
    val endSample: Int,
)

/**
 * Deterministic synthetic speech-like audio plus its segment timeline.
 *
 * The segment list is what lets a fixture label speech onset, a candidate
 * pause, and resumed speech without hard-coding frame indices.
 */
data class SynthesizedSpeech(
    val samples: ShortArray,
    val segments: List<SyntheticSegment>,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthesizedSpeech) return false
        return segments == other.segments && samples.contentEquals(other.samples)
    }

    override fun hashCode(): Int = 31 * samples.contentHashCode() + segments.hashCode()

    override fun toString(): String = "SynthesizedSpeech(sampleCount=${samples.size}, segments=${segments.size})"
}

/**
 * Seedable in-repo replacement for a cloud/host TTS step.
 *
 * It renders a phrase as a sequence of formant-like voiced syllables separated
 * by configurable gaps and pauses. The output is deliberately *not* intelligible
 * speech: it exists so the replay pipeline, endpointing boundaries, and noise
 * transformations have deterministic, non-silent input without a live TTS
 * service, network, credentials, or microphone. Accuracy claims must come from
 * the permissioned human-speech corpus, never from these fixtures (see
 * `docs/audio-replay-harness.md`).
 *
 * Punctuation drives the timeline: letters and digits are voiced syllables,
 * whitespace is an inter-word gap, `,` is a short pause, `.`/`!`/`?` are long
 * pauses, and `|` is the configurable pause used to build pause/resume clips.
 */
class DeterministicSpeechGenerator(
    private val seed: Long,
    private val profile: SyntheticVoiceProfile = SyntheticVoiceProfile(),
) {
    fun synthesize(
        text: String,
        format: AudioFormat,
    ): SynthesizedSpeech {
        require(format.isMono) { "deterministic speech expects mono audio" }
        val plan = plan(text, format.sampleRateHz)
        val totalSamples = plan.sumOf { it.sampleCount }
        val buffer = FloatArray(totalSamples)
        val segments = ArrayList<SyntheticSegment>(plan.size)
        val random = SplitMix64(seed)
        var cursor = 0
        for (step in plan) {
            when (step.kind) {
                SyntheticSegmentKind.VOICED -> {
                    renderVoiced(buffer, cursor, step.sampleCount, format.sampleRateHz, random)
                }

                else -> {
                    renderSilence(buffer, cursor, step.sampleCount, random)
                }
            }
            segments += SyntheticSegment(step.kind, cursor, cursor + step.sampleCount)
            cursor += step.sampleCount
        }
        return SynthesizedSpeech(
            samples = ShortArray(totalSamples) { Dsp.clampToShort(buffer[it].toDouble() * Short.MAX_VALUE) },
            segments = segments,
        )
    }

    private fun plan(
        text: String,
        sampleRateHz: Int,
    ): List<PlannedSegment> {
        val result = ArrayList<PlannedSegment>(text.length)
        for (character in text) {
            val step =
                when {
                    character.isLetterOrDigit() -> {
                        PlannedSegment(SyntheticSegmentKind.VOICED, millisToSamples(profile.syllableMillis, sampleRateHz))
                    }

                    character == ',' -> {
                        PlannedSegment(SyntheticSegmentKind.SHORT_PAUSE, millisToSamples(profile.shortPauseMillis, sampleRateHz))
                    }

                    character == '.' || character == '!' || character == '?' -> {
                        PlannedSegment(SyntheticSegmentKind.LONG_PAUSE, millisToSamples(profile.longPauseMillis, sampleRateHz))
                    }

                    character == '|' -> {
                        PlannedSegment(SyntheticSegmentKind.PAUSE, millisToSamples(profile.pauseMillis, sampleRateHz))
                    }

                    else -> {
                        PlannedSegment(SyntheticSegmentKind.GAP, millisToSamples(profile.wordGapMillis, sampleRateHz))
                    }
                }
            if (step.sampleCount > 0) result += step
        }
        return result
    }

    private fun renderVoiced(
        buffer: FloatArray,
        offset: Int,
        count: Int,
        sampleRateHz: Int,
        random: SplitMix64,
    ) {
        val baseFrequency = profile.baseFrequencyHz * (0.9 + 0.2 * random.nextDouble())
        val wobbleHz = 2.0 + 3.0 * random.nextDouble()
        val harmonics = doubleArrayOf(1.0, 0.5, 0.28, 0.15)
        val phasePerSample = 2.0 * StrictMath.PI * baseFrequency / sampleRateHz
        var angle = 0.0
        for (index in 0 until count) {
            val position = index.toDouble() / count
            val envelope = StrictMath.sin(StrictMath.PI * position).let { it * it }
            angle += phasePerSample * (1.0 + 0.03 * StrictMath.sin(2.0 * StrictMath.PI * wobbleHz * index / sampleRateHz))
            var value = 0.0
            for (harmonic in harmonics.indices) {
                value += harmonics[harmonic] * StrictMath.sin((harmonic + 1) * angle)
            }
            buffer[offset + index] =
                (value * profile.amplitude * envelope + profile.noiseFloor * random.nextBipolar()).toFloat()
        }
    }

    private fun renderSilence(
        buffer: FloatArray,
        offset: Int,
        count: Int,
        random: SplitMix64,
    ) {
        for (index in 0 until count) {
            buffer[offset + index] = (profile.noiseFloor * random.nextBipolar()).toFloat()
        }
    }

    private fun millisToSamples(
        millis: Int,
        sampleRateHz: Int,
    ): Int = (millis.toLong() * sampleRateHz / 1000L).toInt()

    private data class PlannedSegment(
        val kind: SyntheticSegmentKind,
        val sampleCount: Int,
    )
}
