package com.voicechat.agent.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** The manifest must round-trip through its deterministic text codec unchanged. */
class FixtureManifestCodecTest {
    private val manifest =
        ReplayFixtures
            .syntheticPauseResume(
                transformations =
                    listOf(
                        GainTransformation(gainDb = -6.0),
                        ClippingTransformation(ceiling = 0.4),
                        NoiseTransformation(NoiseProfile.CAR, snrDb = 9.0, seed = 3L),
                        CompetingSpeechTransformation(phrase = "second speaker", levelDb = -8.0, seed = 4L),
                        EchoTransformation(delayMillis = 90, decay = 0.5, reflections = 2, seed = 5L),
                        ReverberationTransformation(decayMillis = 150, wetLevel = 0.3, seed = 6L),
                        CompressionTransformation(thresholdDb = -20.0, ratio = 3.0, makeupDb = 4.0, seed = 7L),
                        CodecArtifactTransformation(CodecProfile.TELEPHONE, seed = 8L),
                    ),
            ).manifest

    @Test
    fun encodeDecodeRoundTripsEveryField() {
        val decoded = FixtureManifestCodec.decode(FixtureManifestCodec.encode(manifest))

        assertEquals(manifest, decoded)
    }

    @Test
    fun encodingIsStableAcrossReEncode() {
        val once = FixtureManifestCodec.encode(manifest)
        val twice = FixtureManifestCodec.encode(FixtureManifestCodec.decode(once))

        assertEquals(once, twice)
    }

    @Test
    fun aTranscriptContainingEqualsSignsRoundTrips() {
        val withEquals = manifest.copy(labels = manifest.labels.copy(finalTranscript = "x = y"))

        assertEquals(withEquals, FixtureManifestCodec.decode(FixtureManifestCodec.encode(withEquals)))
    }

    @Test
    fun anUnsupportedManifestVersionIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FixtureManifestCodec.decode("manifest.version=99\n")
        }
    }

    @Test
    fun humanAndSyntheticOriginsStayDistinctThroughTheCodec() {
        val human =
            manifest.copy(
                source =
                    manifest.source.copy(
                        origin = FixtureOrigin.HUMAN,
                        license = "written consent ref intake-2026-001",
                        provenance = "intake-2026-001 / anonymized speaker A",
                    ),
            )

        val decoded = FixtureManifestCodec.decode(FixtureManifestCodec.encode(human))

        assertEquals(FixtureOrigin.HUMAN, decoded.source.origin)
        assertNotEquals(FixtureOrigin.SYNTHETIC, decoded.source.origin)
        assertEquals(human, decoded)
    }
}
