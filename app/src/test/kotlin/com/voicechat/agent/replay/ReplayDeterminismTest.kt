package com.voicechat.agent.replay

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M03 acceptance: replaying one fixture twice yields identical frames, labels,
 * and transformation metadata, with no microphone, network, or credentials.
 */
class ReplayDeterminismTest {
    private val transformations =
        listOf(
            GainTransformation(gainDb = -3.0),
            NoiseTransformation(profile = NoiseProfile.STREET, snrDb = 15.0, seed = 42L),
        )

    @Test
    fun replayingAFixtureTwiceYieldsIdenticalFramesLabelsAndMetadata() =
        runTest {
            val fixture = ReplayFixtures.syntheticPauseResume(transformations = transformations)

            val firstFrames = ReplayAudioInput(fixture).frames().toList()
            val secondFrames = ReplayAudioInput(fixture).frames().toList()

            assertEquals(firstFrames, secondFrames)
            assertTrue(firstFrames.isNotEmpty())
            assertTrue(firstFrames.all { it.capturedAtNanos == 0L })
            assertEquals(fixture.manifest.labels, ReplayFixtures.syntheticPauseResume(transformations = transformations).manifest.labels)
        }

    @Test
    fun regeneratingTheFixtureReproducesTheSameBytesAndManifest() =
        runTest {
            val fixture = ReplayFixtures.syntheticPauseResume(transformations = transformations)
            val rebuilt = ReplayFixtures.syntheticPauseResume(transformations = transformations)

            assertArrayEquals(PcmCodec.encode(fixture.samples), PcmCodec.encode(rebuilt.samples))
            assertEquals(fixture, rebuilt)
            assertEquals(fixture.manifest.pcmSha256, rebuilt.manifest.pcmSha256)
            assertEquals(
                FixtureManifestCodec.encode(fixture.manifest),
                FixtureManifestCodec.encode(rebuilt.manifest),
            )
        }

    @Test
    fun frameSlicingCoversEverySampleExactlyOnce() =
        runTest {
            val fixture = ReplayFixtures.syntheticPauseResume(transformations = transformations)
            val frames = ReplayAudioInput(fixture).frames().toList()

            val flattened = ShortArray(fixture.samples.size)
            var offset = 0
            frames.forEach { frame ->
                frame.samples.copyInto(flattened, offset)
                offset += frame.samples.size
            }

            assertArrayEquals(fixture.samples, flattened)
            assertEquals(offset, fixture.samples.size)
            assertEquals(fixture.samples.size, fixture.manifest.sampleCount)
        }

    @Test
    fun transformationChainIsRecordedInApplicationOrder() {
        val fixture = ReplayFixtures.syntheticPauseResume(transformations = transformations)

        assertEquals(
            listOf(GainTransformation.KIND, NoiseTransformation.KIND),
            fixture.manifest.transformations.map { it.kind },
        )
        assertEquals(
            listOf(GainTransformation.KIND, NoiseTransformation.KIND),
            FixtureManifestCodec
                .decode(FixtureManifestCodec.encode(fixture.manifest))
                .transformations
                .map { it.kind },
        )
    }

    @Test
    fun replayAudioInputReportsItsCollectionState() =
        runTest {
            val fixture = ReplayFixtures.syntheticPauseResume()
            val input = ReplayAudioInput(fixture)

            assertEquals(AudioFormat.MONO_16_KHZ, input.format)
            assertEquals(0, input.collectionCount)
            input.frames().toList()
            input.frames().toList()
            assertEquals(2, input.collectionCount)

            assertTrue(!input.closed)
            input.close()
            input.close()
            assertTrue(input.closed)
        }

    @Test
    fun replayFramesMatchTheFixturesDeclaredFormat() =
        runTest {
            val fixture = ReplayFixtures.syntheticPauseResume()
            val frames: List<AudioFrame> = ReplayAudioInput(fixture).frames().toList()

            assertTrue(frames.all { it.format == fixture.format })
            assertTrue(frames.dropLast(1).all { it.sampleCount == fixture.manifest.frameSizeSamples })
        }
}
