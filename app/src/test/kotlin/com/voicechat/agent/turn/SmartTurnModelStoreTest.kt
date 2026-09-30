package com.voicechat.agent.turn

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.fake.FakeContentHasher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * M10 model lifecycle: integrity (size + SHA-256), atomic install with a fake
 * transport, cleanup of partial files, and removal — all without a real 11 MB
 * artifact or the network.
 */
class SmartTurnModelStoreTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val pinnedHash = "a".repeat(64)
    private val artifact =
        SmartTurnArtifact(
            modelName = "test-smart-turn",
            fileName = "test.onnx",
            downloadUrl = "https://example.invalid/test.onnx",
            revision = "rev-test",
            sizeBytes = 4,
            sha256 = pinnedHash,
            license = "BSD-2-Clause",
            accessedOn = "2026-09-29",
        )

    private fun directory(): File = temporaryFolder.newFolder("smart-turn")

    private fun store(
        directory: File,
        hasher: ContentHasher,
    ) = SmartTurnModelStore(directory = directory, artifact = artifact, hasher = hasher)

    @Test
    fun anAbsentFileIsMissing() =
        runTest {
            val state = store(directory(), FakeContentHasher(pinnedHash)).state()

            assertEquals(SmartTurnModelState.Missing, state)
        }

    @Test
    fun aWrongSizeFileIsCorrupt() =
        runTest {
            val directory = directory()
            File(directory, artifact.fileName).writeBytes(ByteArray(3))

            val state = store(directory, FakeContentHasher(pinnedHash)).state()

            assertTrue(state is SmartTurnModelState.Corrupt)
            assertTrue((state as SmartTurnModelState.Corrupt).reason.contains("size"))
            assertEquals(ErrorCode.MODEL_CORRUPT, state.error.code)
        }

    @Test
    fun aWrongChecksumFileIsCorrupt() =
        runTest {
            val directory = directory()
            File(directory, artifact.fileName).writeBytes(ByteArray(4))

            val state = store(directory, FakeContentHasher("b".repeat(64))).state()

            assertTrue(state is SmartTurnModelState.Corrupt)
            assertTrue((state as SmartTurnModelState.Corrupt).reason.contains("checksum"))
        }

    @Test
    fun aVerifiedFileIsInstalled() =
        runTest {
            val directory = directory()
            File(directory, artifact.fileName).writeBytes(ByteArray(4))

            val state = store(directory, FakeContentHasher(pinnedHash)).state()

            assertEquals(SmartTurnModelState.Installed(File(directory, artifact.fileName), 4), state)
        }

    @Test
    fun installVerifiesAndAtomicallyPlacesTheFile() =
        runTest {
            val directory = directory()
            val store = store(directory, FakeContentHasher(pinnedHash))
            val source = SmartTurnModelSource { destination -> destination.writeBytes(ByteArray(4)) }

            val result = store.install(source)

            assertTrue(result is SmartTurnInstallResult.Installed)
            assertTrue(store.modelFile().isFile)
            assertFalse(File(directory, "${artifact.fileName}.part").exists())
        }

    @Test
    fun installRejectsAWrongSizeDownloadAndLeavesNothingBehind() =
        runTest {
            val directory = directory()
            val store = store(directory, FakeContentHasher(pinnedHash))
            val source = SmartTurnModelSource { destination -> destination.writeBytes(ByteArray(3)) }

            val result = store.install(source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as SmartTurnInstallResult.Failed).error.code)
            assertFalse(store.modelFile().exists())
            assertFalse(File(directory, "${artifact.fileName}.part").exists())
        }

    @Test
    fun installRejectsAWrongChecksumDownloadAndLeavesNothingBehind() =
        runTest {
            val directory = directory()
            val store = store(directory, FakeContentHasher("c".repeat(64)))
            val source = SmartTurnModelSource { destination -> destination.writeBytes(ByteArray(4)) }

            val result = store.install(source)

            assertTrue(result is SmartTurnInstallResult.Failed)
            assertFalse(store.modelFile().exists())
        }

    @Test
    fun installMapsASourceFailureToATypedErrorAndCleansUp() =
        runTest {
            val directory = directory()
            val store = store(directory, FakeContentHasher(pinnedHash))
            val source =
                SmartTurnModelSource { destination ->
                    destination.writeBytes(ByteArray(2))
                    throw VoiceAgentException(VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "network down"))
                }

            val result = store.install(source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as SmartTurnInstallResult.Failed).error.code)
            assertFalse(File(directory, "${artifact.fileName}.part").exists())
        }

    @Test
    fun removeDeletesTheInstalledFileAndAnyPartialFile() =
        runTest {
            val directory = directory()
            File(directory, artifact.fileName).writeBytes(ByteArray(4))
            File(directory, "${artifact.fileName}.part").writeBytes(ByteArray(1))
            val store = store(directory, FakeContentHasher(pinnedHash))

            assertTrue(store.remove())
            assertFalse(store.modelFile().exists())
            assertFalse(File(directory, "${artifact.fileName}.part").exists())
            assertTrue(store.remove())
        }

    @Test
    fun theRealHasherMatchesAKnownSha256() =
        runTest {
            val directory = directory()
            val file = File(directory, "abc.bin")
            file.writeBytes("abc".toByteArray(Charsets.US_ASCII))

            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                Sha256ContentHasher.sha256(file),
            )
            assertNull(Sha256ContentHasher.sha256(File(directory, "does-not-exist.bin")))
        }
}
