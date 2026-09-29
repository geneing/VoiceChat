package com.voicechat.agent.replay

import com.voicechat.agent.domain.AudioFormat
import java.lang.StrictMath

/** Noise beds the replay corpus must cover (see `docs/audio-replay-harness.md`). */
enum class NoiseProfile {
    /** Open-street broadband noise with low rumble. */
    STREET,

    /** In-car engine hum plus road noise. */
    CAR,

    /** Low-frequency, slowly modulated wind noise. */
    WIND,
}

/** Codec/degradation profiles approximated for reproducible coverage. */
enum class CodecProfile {
    /** Wideband lossy codec: mild band limit and light quantization. */
    AAC_LOW,

    /** Voice codec: 8 kHz band limit with moderate quantization. */
    OPUS_VOIP,

    /** Narrowband telephone: 3.4 kHz band limit with coarse quantization. */
    TELEPHONE,
}

/**
 * One deterministic, seedable transformation applied to fixture PCM.
 *
 * Transformations are pure functions of their input and [seed], so the same
 * transformation always reproduces the same bytes. [parameters] is the
 * canonical, ordered metadata recorded in the fixture manifest; replaying a
 * fixture must yield identical transformation metadata.
 *
 * Synthetic fixtures exist for pipeline and robustness coverage. Applying a
 * transformation does not change the labeled transcript: labels describe the
 * source phrase, not what an engine would recognize (see the harness doc).
 */
sealed interface AudioTransformation {
    /** Stable manifest discriminator. */
    val kind: String

    /** Seed that makes a stochastic transformation reproducible. */
    val seed: Long

    /** Canonical ordered parameter metadata for the manifest. */
    fun parameters(): List<Pair<String, String>>

    /** Applies the transformation; [input] is never modified. */
    fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray
}

/** Scales amplitude by [gainDb]; negative values attenuate. */
data class GainTransformation(
    val gainDb: Double,
    override val seed: Long = 0L,
) : AudioTransformation {
    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> = listOf("gainDb" to gainDb.toString())

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val factor = StrictMath.pow(10.0, gainDb / 20.0)
        return ShortArray(input.size) { index -> Dsp.clampToShort(input[index].toDouble() * factor) }
    }

    companion object {
        const val KIND: String = "gain"
    }
}

/** Hard-clips at [ceiling] of full scale (0..1), modelling overdriven capture. */
data class ClippingTransformation(
    val ceiling: Double,
    override val seed: Long = 0L,
) : AudioTransformation {
    init {
        require(ceiling in 0.0..1.0) { "ceiling must be within 0..1, was $ceiling" }
    }

    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> = listOf("ceiling" to ceiling.toString())

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val limit = ceiling * Short.MAX_VALUE
        return ShortArray(input.size) { index ->
            input[index].toDouble().coerceIn(-limit, limit).let(Dsp::clampToShort)
        }
    }

    companion object {
        const val KIND: String = "clipping"
    }
}

/** Adds a generated noise bed at [snrDb] relative to the input RMS. */
data class NoiseTransformation(
    val profile: NoiseProfile,
    val snrDb: Double,
    override val seed: Long,
) : AudioTransformation {
    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> =
        listOf(
            "profile" to profile.name,
            "snrDb" to snrDb.toString(),
        )

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val noise = NoiseBeds.render(profile, input.size, format.sampleRateHz, SplitMix64(seed))
        val signalRms = Dsp.rms(input).takeIf { it > 0.0 } ?: DEFAULT_SIGNAL_RMS
        val targetRms = signalRms / StrictMath.pow(10.0, snrDb / 20.0)
        val noiseRms = Dsp.rms(noise)
        val scale = if (noiseRms > 0.0) targetRms / noiseRms else 0.0
        return ShortArray(input.size) { index ->
            Dsp.clampToShort(input[index] + noise[index] * scale)
        }
    }

    companion object {
        const val KIND: String = "noise"
        private const val DEFAULT_SIGNAL_RMS = 1_000.0
    }
}

/** Mixes a second synthetic speaker at [levelDb] relative to the input RMS. */
data class CompetingSpeechTransformation(
    val phrase: String,
    val levelDb: Double,
    override val seed: Long,
) : AudioTransformation {
    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> =
        listOf(
            "phrase" to phrase,
            "levelDb" to levelDb.toString(),
        )

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val competing =
            DeterministicSpeechGenerator(seed)
                .synthesize(phrase, format)
                .samples
        val signalRms = Dsp.rms(input).takeIf { it > 0.0 } ?: DEFAULT_SIGNAL_RMS
        val targetRms = signalRms * StrictMath.pow(10.0, levelDb / 20.0)
        val competingRms = Dsp.rms(competing)
        val scale = if (competingRms > 0.0) targetRms / competingRms else 0.0
        return ShortArray(input.size) { index ->
            val other = if (index < competing.size) competing[index] * scale else 0.0
            Dsp.clampToShort(input[index] + other)
        }
    }

    companion object {
        const val KIND: String = "competing_speech"
        private const val DEFAULT_SIGNAL_RMS = 1_000.0
    }
}

/** Adds discrete delayed reflections (speaker echo), uniformly spaced. */
data class EchoTransformation(
    val delayMillis: Int,
    val decay: Double,
    val reflections: Int,
    override val seed: Long = 0L,
) : AudioTransformation {
    init {
        require(delayMillis > 0) { "delayMillis must be positive" }
        require(decay in 0.0..1.0) { "decay must be within 0..1" }
        require(reflections >= 0) { "reflections must not be negative" }
    }

    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> =
        listOf(
            "delayMillis" to delayMillis.toString(),
            "decay" to decay.toString(),
            "reflections" to reflections.toString(),
        )

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val delaySamples = (delayMillis.toLong() * format.sampleRateHz / 1000L).toInt().coerceAtLeast(1)
        val output = Dsp.toFloats(input)
        for (reflection in 1..reflections) {
            val offset = delaySamples.toLong() * reflection
            if (offset >= input.size) break
            val attenuation = StrictMath.pow(decay, reflection.toDouble())
            for (index in offset.toInt() until input.size) {
                output[index] += (input[index - offset.toInt()] * attenuation).toFloat()
            }
        }
        return ShortArray(input.size) { Dsp.clampToShort(output[it].toDouble()) }
    }

    companion object {
        const val KIND: String = "echo"
    }
}

/** Adds a short reverberant tail using parallel feedback comb filters. */
data class ReverberationTransformation(
    val decayMillis: Int,
    val wetLevel: Double,
    override val seed: Long = 0L,
) : AudioTransformation {
    init {
        require(decayMillis > 0) { "decayMillis must be positive" }
        require(wetLevel in 0.0..1.0) { "wetLevel must be within 0..1" }
    }

    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> =
        listOf(
            "decayMillis" to decayMillis.toString(),
            "wetLevel" to wetLevel.toString(),
        )

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val delays =
            intArrayOf(
                (decayMillis.toLong() * format.sampleRateHz / 1000L).toInt().coerceAtLeast(1),
                (decayMillis.toLong() * format.sampleRateHz / 1500L).toInt().coerceAtLeast(1),
                (decayMillis.toLong() * format.sampleRateHz / 2200L).toInt().coerceAtLeast(1),
            )
        val gains = doubleArrayOf(0.72, 0.64, 0.55)
        // Each comb is a feedback delay line: y[n] = x[n] + g * y[n - d].
        val combs =
            delays.mapIndexed { combIndex, delay ->
                val output = FloatArray(input.size)
                for (index in input.indices) {
                    val delayed = if (index >= delay) output[index - delay] else 0f
                    output[index] = input[index] + (gains[combIndex] * delayed).toFloat()
                }
                output
            }
        val averageWet = 1.0 / combs.size
        return ShortArray(input.size) { index ->
            val dry = input[index].toDouble()
            val reverberant = combs.sumOf { it[index].toDouble() } * averageWet
            Dsp.clampToShort((1.0 - wetLevel) * dry + wetLevel * reverberant)
        }
    }

    companion object {
        const val KIND: String = "reverberation"
    }
}

/** Feed-forward dynamic range compression with a smoothed envelope detector. */
data class CompressionTransformation(
    val thresholdDb: Double,
    val ratio: Double,
    val makeupDb: Double,
    override val seed: Long = 0L,
) : AudioTransformation {
    init {
        require(ratio >= 1.0) { "ratio must be at least 1" }
    }

    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> =
        listOf(
            "thresholdDb" to thresholdDb.toString(),
            "ratio" to ratio.toString(),
            "makeupDb" to makeupDb.toString(),
        )

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val threshold = StrictMath.pow(10.0, thresholdDb / 20.0) * Short.MAX_VALUE
        val attack = envelopeCoefficient(format.sampleRateHz, ATTACK_MILLIS)
        val release = envelopeCoefficient(format.sampleRateHz, RELEASE_MILLIS)
        var envelope = 0.0
        return ShortArray(input.size) { index ->
            val level = StrictMath.abs(input[index].toDouble())
            val coefficient = if (level > envelope) attack else release
            envelope += coefficient * (level - envelope)
            val overDb =
                if (envelope > threshold && threshold > 0.0) {
                    20.0 * StrictMath.log10(envelope / threshold)
                } else {
                    0.0
                }
            val reductionDb = if (overDb > 0.0) overDb * (1.0 - 1.0 / ratio) else 0.0
            val gain = StrictMath.pow(10.0, (makeupDb - reductionDb) / 20.0)
            Dsp.clampToShort(input[index].toDouble() * gain)
        }
    }

    companion object {
        const val KIND: String = "compression"
        private const val ATTACK_MILLIS = 5.0
        private const val RELEASE_MILLIS = 80.0

        private fun envelopeCoefficient(
            sampleRateHz: Int,
            millis: Double,
        ): Double = 1.0 - StrictMath.exp(-1.0 / (millis / 1000.0 * sampleRateHz))
    }
}

/** Approximates lossy-codec artifacts with band limiting and coarse quantization. */
data class CodecArtifactTransformation(
    val profile: CodecProfile,
    override val seed: Long,
) : AudioTransformation {
    override val kind: String get() = KIND

    override fun parameters(): List<Pair<String, String>> = listOf("profile" to profile.name)

    override fun transform(
        input: ShortArray,
        format: AudioFormat,
    ): ShortArray {
        val cutoff =
            when (profile) {
                CodecProfile.AAC_LOW -> 15_000.0
                CodecProfile.OPUS_VOIP -> 8_000.0
                CodecProfile.TELEPHONE -> 3_400.0
            }
        val bits =
            when (profile) {
                CodecProfile.AAC_LOW -> 12
                CodecProfile.OPUS_VOIP -> 10
                CodecProfile.TELEPHONE -> 8
            }
        val filtered = Dsp.lowPass(Dsp.toFloats(input), cutoff, format.sampleRateHz)
        val levels = (1 shl (bits - 1)).toDouble()
        val step = Short.MAX_VALUE / levels
        val random = SplitMix64(seed)
        return ShortArray(input.size) { index ->
            val dither = random.nextBipolar() * step * 0.5
            val quantized = StrictMath.round((filtered[index] + dither) / step) * step
            Dsp.clampToShort(quantized)
        }
    }

    companion object {
        const val KIND: String = "codec_artifacts"
    }
}

/** Deterministic noise-bed generation, shared by [NoiseTransformation]. */
private object NoiseBeds {
    fun render(
        profile: NoiseProfile,
        size: Int,
        sampleRateHz: Int,
        random: SplitMix64,
    ): FloatArray {
        if (size == 0) return FloatArray(0)
        val white = FloatArray(size) { random.nextBipolar().toFloat() }
        return when (profile) {
            NoiseProfile.STREET -> {
                val rumble = sineWave(size, sampleRateHz, 70.0)
                val low = Dsp.lowPass(white, 200.0, sampleRateHz)
                Dsp.mix(
                    listOf(
                        white to 0.55,
                        low to 0.75,
                        rumble to 0.35,
                    ),
                    size,
                )
            }

            NoiseProfile.CAR -> {
                val hum = sineWave(size, sampleRateHz, 95.0)
                val harmonic = sineWave(size, sampleRateHz, 190.0)
                val low = Dsp.lowPass(white, 350.0, sampleRateHz)
                Dsp.mix(
                    listOf(
                        low to 0.6,
                        hum to 0.5,
                        harmonic to 0.25,
                    ),
                    size,
                )
            }

            NoiseProfile.WIND -> {
                val low = Dsp.lowPass(Dsp.lowPass(white, 500.0, sampleRateHz), 220.0, sampleRateHz)
                val modulation = sineWave(size, sampleRateHz, 0.6)
                FloatArray(size) { index ->
                    val envelope = 0.6 + 0.4 * modulation[index].toDouble()
                    (low[index] * envelope).toFloat()
                }
            }
        }
    }

    private fun sineWave(
        size: Int,
        sampleRateHz: Int,
        frequencyHz: Double,
    ): FloatArray =
        FloatArray(size) { index ->
            StrictMath.sin(2.0 * StrictMath.PI * frequencyHz * index / sampleRateHz).toFloat()
        }
}
