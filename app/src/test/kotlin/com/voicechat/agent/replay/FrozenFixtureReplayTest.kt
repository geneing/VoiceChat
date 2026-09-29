package com.voicechat.agent.replay

import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves replay is byte-stable against a committed fixture: the checked-in PCM
 * and manifest must match the in-repo generator exactly, and replaying them
 * twice must be identical. This is what makes a recorded regression reproducible
 * without a live TTS service, network, or microphone.
 */
class FrozenFixtureReplayTest {
    private val pcmResource = "/replay/frozen/synthetic-pause-resume.pcm"
    private val manifestResource = "/replay/frozen/synthetic-pause-resume.manifest"

    private fun loadFixture(): PcmFixture {
        val bytes = ReplayTestSupport.resourceBytes(pcmResource)
        val manifest = FixtureManifestCodec.decode(ReplayTestSupport.resourceText(manifestResource))
        return PcmFixture(manifest, PcmCodec.decode(bytes))
    }

    @Test
    fun theCommittedFixtureMatchesItsManifestHash() {
        val fixture = loadFixture()

        assertNotNull(fixture.manifest.pcmSha256)
        assertEquals(fixture.manifest.pcmSha256, PcmCodec.sha256Hex(fixture.samples))
        assertEquals(FixtureOrigin.SYNTHETIC, fixture.manifest.source.origin)
        assertTrue(fixture.samples.isNotEmpty())
    }

    @Test
    fun theCommittedFixtureReplaysByteIdenticallyTwice() =
        runTest {
            val fixture = loadFixture()

            val first = ReplayAudioInput(fixture).frames().toList()
            val second = ReplayAudioInput(fixture).frames().toList()

            assertEquals(first, second)
            assertArrayEquals(
                PcmCodec.encode(fixture.samples),
                PcmCodec.encode(ReplayAudioInput(fixture).frames().toList().flatMapSamples()),
            )
        }

    @Test
    fun theCommittedFixtureIsReproducibleFromTheGenerator() {
        val fixture = loadFixture()
        val regenerated = ReplayFixtures.syntheticPauseResume()

        assertArrayEquals(PcmCodec.encode(regenerated.samples), PcmCodec.encode(fixture.samples))
        assertEquals(regenerated.manifest, fixture.manifest)
        assertEquals(
            ReplayTestSupport.resourceText(manifestResource),
            FixtureManifestCodec.encode(regenerated.manifest),
        )
    }

    private fun List<AudioFrame>.flatMapSamples(): ShortArray {
        val total = sumOf { it.samples.size }
        val flattened = ShortArray(total)
        var offset = 0
        forEach { frame ->
            frame.samples.copyInto(flattened, offset)
            offset += frame.samples.size
        }
        return flattened
    }
}
