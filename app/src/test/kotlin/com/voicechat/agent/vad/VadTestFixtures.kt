package com.voicechat.agent.vad

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.replay.AudioTransformation
import com.voicechat.agent.replay.FixtureEngine
import com.voicechat.agent.replay.FixtureLabels
import com.voicechat.agent.replay.FixtureManifest
import com.voicechat.agent.replay.FixtureOrigin
import com.voicechat.agent.replay.FixtureSource
import com.voicechat.agent.replay.PcmCodec
import com.voicechat.agent.replay.PcmFixture
import com.voicechat.agent.replay.ReplayFixtures
import kotlin.math.PI
import kotlin.math.sin

/**
 * M09 test fixtures built **on** the M03 replay infrastructure rather than a
 * parallel one: they reuse [PcmFixture], [FixtureManifest], [PcmCodec], and the
 * `ReplayFixtures` generator, and only add the shapes M09 needs — trailing
 * silence, pure silence, and a music-like tone.
 *
 * These helpers live in the `vad` test package on purpose; the replay harness
 * itself is left untouched.
 */
internal object VadTestFixtures {
    private const val GENERATED_LICENSE = "Generated in-repo for M09 tests; no third-party rights."

    /** The canonical M03 pause/resume fixture ("hello | again"). */
    fun pauseResume(phrase: String = "hello | again"): PcmFixture = ReplayFixtures.syntheticPauseResume(phrase = phrase)

    /**
     * Any phrase rendered through the M03 [com.voicechat.agent.replay.DeterministicSpeechGenerator].
     *
     * `ReplayFixtures.syntheticPauseResume` insists on a `|` pause followed by
     * speech because it labels pause/resume; M09 also needs plain phrases
     * ("hello"), trailing pauses ("hello |"), short acknowledgements ("yes"),
     * and a sub-threshold internal pause ("hi, there"). This helper reuses the
     * same generator and fixture types without duplicating the harness.
     */
    fun phrase(
        text: String,
        seed: Long = ReplayFixtures.DEFAULT_SEED,
    ): PcmFixture {
        val format = AudioFormat.MONO_16_KHZ
        val generated =
            com.voicechat.agent.replay
                .DeterministicSpeechGenerator(seed)
                .synthesize(text, format)
        return build(
            id = "synthetic/m09-phrase",
            description = "Deterministic phrase \"$text\".",
            format = format,
            samples = generated.samples,
        )
    }

    /** Appends [millis] of digital silence to a fixture's samples. */
    fun withTrailingSilence(
        fixture: PcmFixture,
        millis: Long,
    ): PcmFixture {
        val samples = fixture.samples + ShortArray(samplesFor(millis, fixture.format))
        return rebuild(fixture, samples, idSuffix = "trailing-${millis}ms", transformations = emptyList())
    }

    /** A fixture that is pure silence (the empty/no-speech case). */
    fun silence(millis: Long): PcmFixture {
        val format = AudioFormat.MONO_16_KHZ
        val samples = ShortArray(samplesFor(millis, format))
        return build(
            id = "synthetic/m09-silence",
            description = "Digital silence, no speech.",
            format = format,
            samples = samples,
        )
    }

    /**
     * A sustained harmonic tone standing in for non-speech "music".
     *
     * It is deliberately periodic and continuous (no pauses), so it exercises
     * the endpoint policy against non-speech energy without pretending the
     * detector can semantically classify music.
     */
    fun tone(
        millis: Long,
        frequencyHz: Double,
    ): PcmFixture {
        val format = AudioFormat.MONO_16_KHZ
        val count = samplesFor(millis, format)
        val amplitude = 0.3 * Short.MAX_VALUE
        val samples =
            ShortArray(count) { index ->
                (amplitude * sin(2.0 * PI * frequencyHz * index / format.sampleRateHz)).toInt().toShort()
            }
        return build(
            id = "synthetic/m09-tone",
            description = "Sustained ${frequencyHz}Hz harmonic tone (non-speech).",
            format = format,
            samples = samples,
        )
    }

    /** Applies one transformation to a fixture and rebuilds its identity. */
    fun transformed(
        fixture: PcmFixture,
        transformation: AudioTransformation,
    ): PcmFixture {
        val samples = transformation.transform(fixture.samples, fixture.format)
        return rebuild(fixture, samples, idSuffix = transformation.kind, transformations = listOf(transformation))
    }

    private fun rebuild(
        fixture: PcmFixture,
        samples: ShortArray,
        idSuffix: String,
        transformations: List<AudioTransformation>,
    ): PcmFixture =
        build(
            id = "${fixture.manifest.id}:$idSuffix",
            description = fixture.manifest.source.description,
            format = fixture.format,
            samples = samples,
            engine = fixture.manifest.engine,
            transformations = transformations,
        )

    private fun build(
        id: String,
        description: String,
        format: AudioFormat,
        samples: ShortArray,
        engine: FixtureEngine = ReplayFixtures.syntheticEngine(),
        transformations: List<AudioTransformation> = emptyList(),
    ): PcmFixture {
        val manifest =
            FixtureManifest(
                id = id,
                source =
                    FixtureSource(
                        origin = FixtureOrigin.SYNTHETIC,
                        name = "M09 VAD test fixture",
                        description = description,
                        license = GENERATED_LICENSE,
                        provenance = "VadTestFixtures",
                    ),
                format = format,
                engine = engine,
                frameSizeSamples = ReplayFixtures.DEFAULT_FRAME_SIZE_SAMPLES,
                sampleCount = samples.size,
                pcmSha256 = PcmCodec.sha256Hex(samples),
                seed = ReplayFixtures.DEFAULT_SEED,
                labels =
                    FixtureLabels(
                        finalTranscript = "",
                        transcriptRevisions = emptyList(),
                        vadEvents = emptyList(),
                        turnCompletions = emptyList(),
                    ),
                transformations = transformations,
            )
        return PcmFixture(manifest, samples)
    }

    private fun samplesFor(
        millis: Long,
        format: AudioFormat,
    ): Int = (millis * format.sampleRateHz / 1000L).toInt()
}
