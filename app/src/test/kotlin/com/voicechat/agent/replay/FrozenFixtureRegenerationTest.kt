package com.voicechat.agent.replay

import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Regenerates the committed frozen fixture from the in-repo generator.
 *
 * It is skipped by default so routine tests never write to the source tree;
 * set `REGENERATE_REPLAY_FIXTURES=1` to refresh the `.pcm` and `.manifest`
 * after an intentional change. The frozen-fixture test then proves the
 * committed bytes still match the generator.
 */
class FrozenFixtureRegenerationTest {
    @Test
    fun regeneratesTheFrozenFixtureWhenRequested() {
        Assume.assumeTrue(
            "set REGENERATE_REPLAY_FIXTURES=1 to regenerate committed fixtures",
            System.getenv("REGENERATE_REPLAY_FIXTURES") == "1",
        )

        val fixture = ReplayFixtures.syntheticPauseResume()
        val directory = ReplayTestSupport.frozenResourceDirectory()
        assertTrue("could not create $directory", directory.isDirectory || directory.mkdirs())

        File(directory, "synthetic-pause-resume.pcm").writeBytes(PcmCodec.encode(fixture.samples))
        File(directory, "synthetic-pause-resume.manifest")
            .writeText(FixtureManifestCodec.encode(fixture.manifest))
    }
}
