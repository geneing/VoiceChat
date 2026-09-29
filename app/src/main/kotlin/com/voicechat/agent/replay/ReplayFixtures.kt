package com.voicechat.agent.replay

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.domain.AudioFormat

/**
 * Builder for the canonical in-repo synthetic fixtures.
 *
 * The pause/resume clip is the reference fixture: it contains a voiced phrase,
 * a labeled candidate pause, resumed speech, and revised partial hypotheses, so
 * it exercises the speech boundaries the app depends on. It is fully
 * deterministic for a given seed and phrase, and it needs no TTS service,
 * network, credentials, or microphone to replay.
 */
object ReplayFixtures {
    const val SYNTHETIC_ENGINE_ID: String = "replay-generator"
    const val SYNTHETIC_ENGINE_MODEL_ID: String = "formant-v1"
    const val SYNTHETIC_ENGINE_VERSION: String = "1"
    const val DEFAULT_PAUSE_RESUME_PHRASE: String = "hello | again"
    const val DEFAULT_SEED: Long = 20_260_928L
    const val DEFAULT_FRAME_SIZE_SAMPLES: Int = 320

    fun syntheticEngine(): FixtureEngine =
        FixtureEngine(
            engineId = SYNTHETIC_ENGINE_ID,
            modelId = SYNTHETIC_ENGINE_MODEL_ID,
            version = SYNTHETIC_ENGINE_VERSION,
        )

    fun syntheticPauseResume(
        seed: Long = DEFAULT_SEED,
        phrase: String = DEFAULT_PAUSE_RESUME_PHRASE,
        frameSizeSamples: Int = DEFAULT_FRAME_SIZE_SAMPLES,
        transformations: List<AudioTransformation> = emptyList(),
    ): PcmFixture {
        require(frameSizeSamples > 0) { "frameSizeSamples must be positive" }
        val format = AudioFormat.MONO_16_KHZ
        val generated = DeterministicSpeechGenerator(seed).synthesize(phrase, format)
        val samples = transformations.fold(generated.samples) { current, transformation -> transformation.transform(current, format) }
        val manifest =
            FixtureManifest(
                id = "synthetic/pause-resume",
                source =
                    FixtureSource(
                        origin = FixtureOrigin.SYNTHETIC,
                        name = "Deterministic pause/resume phrase",
                        description = "Synthetic formant phrase with a labeled pause and resumed speech.",
                        license = GENERATED_LICENSE,
                        provenance = "DeterministicSpeechGenerator(seed=$seed, phrase=\"$phrase\")",
                    ),
                format = format,
                engine = syntheticEngine(),
                frameSizeSamples = frameSizeSamples,
                sampleCount = samples.size,
                pcmSha256 = PcmCodec.sha256Hex(samples),
                seed = seed,
                labels = pauseResumeLabels(generated, phrase, frameSizeSamples),
                transformations = transformations,
            )
        return PcmFixture(manifest, samples)
    }

    /** Collapses the phrase's pause marker and whitespace into a plain transcript. */
    fun normalizeTranscript(phrase: String): String =
        phrase
            .replace('|', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ")

    private fun pauseResumeLabels(
        generated: SynthesizedSpeech,
        phrase: String,
        frameSizeSamples: Int,
    ): FixtureLabels {
        val transcript = normalizeTranscript(phrase)
        val firstPart = normalizeTranscript(phrase.substringBefore('|'))
        val firstVoiced =
            generated.segments.firstOrNull { it.kind == SyntheticSegmentKind.VOICED }
                ?: error("generated phrase has no voiced segment")
        val pause =
            generated.segments.firstOrNull { it.kind == SyntheticSegmentKind.PAUSE }
                ?: error("pause/resume fixture requires a '|' pause marker")
        val resumed =
            generated.segments
                .firstOrNull { it.kind == SyntheticSegmentKind.VOICED && it.startSample > pause.startSample }
                ?: error("generated phrase has no speech after the pause")
        val startFrame = firstVoiced.startSample / frameSizeSamples
        val pauseFrame = pause.startSample / frameSizeSamples
        val resumeFrame = resumed.startSample / frameSizeSamples
        val finalFrame = (generated.samples.size - 1).coerceAtLeast(0) / frameSizeSamples
        return FixtureLabels(
            finalTranscript = transcript,
            transcriptRevisions =
                listOf(
                    TranscriptLabel(startFrame, firstPart, isFinal = false),
                    TranscriptLabel(resumeFrame, transcript, isFinal = false),
                    TranscriptLabel(finalFrame, transcript, isFinal = true),
                ),
            vadEvents =
                listOf(
                    VadLabel(startFrame, SpeechActivity.SPEECH_STARTED),
                    VadLabel(pauseFrame, SpeechActivity.CANDIDATE_PAUSE),
                    VadLabel(resumeFrame, SpeechActivity.SPEECH_RESUMED),
                ),
            turnCompletions = listOf(TurnCompletion.INCOMPLETE, TurnCompletion.COMPLETE),
        )
    }

    private const val GENERATED_LICENSE =
        "Generated in-repo by DeterministicSpeechGenerator; no third-party rights."
}

/**
 * The condition families the replay corpus must cover.
 *
 * [CLEAN] is the untransformed clip; every other condition applies exactly one
 * transformation so a regression can be attributed to one degradation.
 */
enum class FixtureCondition {
    CLEAN,
    STREET_NOISE,
    CAR_NOISE,
    COMPETING_SPEECH,
    ECHO,
    REVERBERATION,
    GAIN,
    CLIPPING,
    COMPRESSION,
    CODEC_ARTIFACTS,
}

/**
 * Builds repeatable single-condition variants of a base fixture.
 *
 * Each condition has a fixed seed and fixed parameters, so the same base fixture
 * always produces byte-identical variants. Variants keep the base labels:
 * synthetic labels describe the source phrase and are used for pipeline and
 * robustness coverage, not as an accuracy measure.
 */
object FixtureVariants {
    const val VARIANT_SEED_BASE: Long = 7_159_000L

    fun transformationFor(
        condition: FixtureCondition,
        seed: Long,
    ): AudioTransformation? =
        when (condition) {
            FixtureCondition.CLEAN -> {
                null
            }

            FixtureCondition.STREET_NOISE -> {
                NoiseTransformation(NoiseProfile.STREET, snrDb = 12.0, seed = seed)
            }

            FixtureCondition.CAR_NOISE -> {
                NoiseTransformation(NoiseProfile.CAR, snrDb = 10.0, seed = seed)
            }

            FixtureCondition.COMPETING_SPEECH -> {
                CompetingSpeechTransformation(phrase = "move the meeting", levelDb = -6.0, seed = seed)
            }

            FixtureCondition.ECHO -> {
                EchoTransformation(delayMillis = 120, decay = 0.45, reflections = 3, seed = seed)
            }

            FixtureCondition.REVERBERATION -> {
                ReverberationTransformation(decayMillis = 180, wetLevel = 0.35, seed = seed)
            }

            FixtureCondition.GAIN -> {
                GainTransformation(gainDb = -6.0, seed = seed)
            }

            FixtureCondition.CLIPPING -> {
                ClippingTransformation(ceiling = 0.35, seed = seed)
            }

            FixtureCondition.COMPRESSION -> {
                CompressionTransformation(thresholdDb = -18.0, ratio = 4.0, makeupDb = 6.0, seed = seed)
            }

            FixtureCondition.CODEC_ARTIFACTS -> {
                CodecArtifactTransformation(CodecProfile.OPUS_VOIP, seed = seed)
            }
        }

    fun variant(
        base: PcmFixture,
        condition: FixtureCondition,
    ): PcmFixture {
        val transformation = transformationFor(condition, VARIANT_SEED_BASE + condition.ordinal)
        val samples = transformation?.transform(base.samples, base.format) ?: base.samples.copyOf()
        val manifest =
            base.manifest.copy(
                id = "${base.manifest.id}:${condition.name.lowercase()}",
                sampleCount = samples.size,
                pcmSha256 = PcmCodec.sha256Hex(samples),
                transformations = listOfNotNull(transformation),
            )
        return PcmFixture(manifest, samples)
    }

    fun standardVariants(base: PcmFixture): List<PcmFixture> = FixtureCondition.entries.map { variant(base, it) }
}
