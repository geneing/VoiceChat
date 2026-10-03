package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.turn.ContentHasher
import com.voicechat.agent.turn.Sha256ContentHasher
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
 * Parakeet-EOU five-file model lifecycle: per-file integrity (size + SHA-256),
 * all-or-nothing install, partial cleanup, and removal — no 150 MB artifact and
 * no network.
 */
class ParakeetEouModelStoreTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    /** A tiny pinned set of two files, so tests stay small and explicit. */
    private val artifact =
        ParakeetEouArtifact(
            modelName = "test-parakeet",
            files =
                listOf(
                    ParakeetEouFile("a.onnx", sizeBytes = 4, sha256 = "a".repeat(64), role = ParakeetEouFileRole.ENCODER),
                    ParakeetEouFile("b.onnx", sizeBytes = 3, sha256 = "b".repeat(64), role = ParakeetEouFileRole.DECODER),
                ),
            revision = "main",
            baseUrl = "https://example.invalid/",
            license = "cc-by-4.0",
            accessedOn = "2026-10-03",
        )

    private fun directory(): File = temporaryFolder.newFolder("parakeet-eou")

    /** Hashes a file by its pinned name, so a multi-file set verifies per file. */
    private fun hasher(map: Map<String, String?> = emptyMap()): ContentHasher =
        ContentHasher { file ->
            val name = file.name.removeSuffix(ParakeetEouModelStore.PART_SUFFIX)
            map[name] ?: artifact.file(name)?.sha256
        }

    private fun store(
        directory: File,
        hasher: ContentHasher = hasher(),
    ) = ParakeetEouModelStore(directory = directory, artifact = artifact, hasher = hasher)

    @Test
    fun anAbsentSetIsMissing() =
        runTest {
            assertEquals(ParakeetEouModelState.Missing, store(directory()).state())
        }

    @Test
    fun aPartiallyPresentSetIsMissing() =
        runTest {
            val directory = directory()
            File(directory, "a.onnx").writeBytes(ByteArray(4))

            assertEquals(ParakeetEouModelState.Missing, store(directory).state())
        }

    @Test
    fun aWrongSizeFileIsCorrupt() =
        runTest {
            val directory = directory()
            File(directory, "a.onnx").writeBytes(ByteArray(3))
            File(directory, "b.onnx").writeBytes(ByteArray(3))

            val state = store(directory).state()

            assertTrue(state is ParakeetEouModelState.Corrupt)
            state as ParakeetEouModelState.Corrupt
            assertTrue(state.reason.contains("a.onnx"))
            assertTrue(state.reason.contains("size"))
            assertEquals(ErrorCode.MODEL_CORRUPT, state.error.code)
        }

    @Test
    fun aWrongChecksumFileIsCorrupt() =
        runTest {
            val directory = directory()
            File(directory, "a.onnx").writeBytes(ByteArray(4))
            File(directory, "b.onnx").writeBytes(ByteArray(3))

            val state = store(directory, hasher(mapOf("a.onnx" to "c".repeat(64)))).state()

            assertTrue(state is ParakeetEouModelState.Corrupt)
            state as ParakeetEouModelState.Corrupt
            assertTrue(state.reason.contains("a.onnx"))
            assertTrue(state.reason.contains("checksum"))
        }

    @Test
    fun aFullyVerifiedSetIsInstalled() =
        runTest {
            val directory = directory()
            File(directory, "a.onnx").writeBytes(ByteArray(4))
            File(directory, "b.onnx").writeBytes(ByteArray(3))

            val state = store(directory).state()

            assertEquals(ParakeetEouModelState.Installed(directory, 7), state)
        }

    @Test
    fun installDownloadsAllFilesAndAtomicallyPlacesThem() =
        runTest {
            val directory = directory()
            val store = store(directory)
            val sizes = mapOf("a.onnx" to 4, "b.onnx" to 3)
            val source =
                ParakeetEouModelSource { url, destination ->
                    val name = url.substringAfterLast('/')
                    destination.writeBytes(ByteArray(sizes.getValue(name)))
                }

            val result = store.install(source)

            assertTrue(result is ParakeetEouInstallResult.Installed)
            assertEquals(ParakeetEouInstallResult.Installed(directory, 7), result)
            assertTrue(File(directory, "a.onnx").isFile)
            assertTrue(File(directory, "b.onnx").isFile)
            assertFalse(File(directory, "a.onnx.part").exists())
            assertFalse(File(directory, "b.onnx.part").exists())
        }

    @Test
    fun aWrongSizeDownloadOnTheSecondFileLeavesNoInstalledStateAndNoPartials() =
        runTest {
            val directory = directory()
            val store = store(directory)
            val sizes = mapOf("a.onnx" to 4, "b.onnx" to 2) // b is 3 pinned
            val source =
                ParakeetEouModelSource { url, destination ->
                    val name = url.substringAfterLast('/')
                    destination.writeBytes(ByteArray(sizes.getValue(name)))
                }

            val result = store.install(source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as ParakeetEouInstallResult.Failed).error.code)
            // The set is still missing (a.onnx may have been staged but has no
            // verified companion), and no .part remains.
            assertTrue(store.state() is ParakeetEouModelState.Missing)
            assertFalse(File(directory, "a.onnx.part").exists())
            assertFalse(File(directory, "b.onnx.part").exists())
        }

    @Test
    fun aWrongChecksumDownloadFailsAndCleansUp() =
        runTest {
            val directory = directory()
            val store = store(directory, hasher(mapOf("a.onnx" to "d".repeat(64))))
            val sizes = mapOf("a.onnx" to 4, "b.onnx" to 3)
            val source =
                ParakeetEouModelSource { url, destination ->
                    destination.writeBytes(ByteArray(sizes.getValue(url.substringAfterLast('/'))))
                }

            val result = store.install(source)

            assertTrue(result is ParakeetEouInstallResult.Failed)
            assertFalse(File(directory, "a.onnx.part").exists())
            assertFalse(File(directory, "b.onnx.part").exists())
        }

    @Test
    fun aSourceFailureMapsToATypedErrorAndCleansUp() =
        runTest {
            val directory = directory()
            val store = store(directory)
            val source =
                ParakeetEouModelSource { _, destination ->
                    destination.writeBytes(ByteArray(4))
                    throw VoiceAgentException(VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "network down"))
                }

            val result = store.install(source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as ParakeetEouInstallResult.Failed).error.code)
            assertFalse(File(directory, "a.onnx.part").exists())
            assertFalse(File(directory, "b.onnx.part").exists())
        }

    @Test
    fun removeDeletesAllFilesAndAnyPartialFile() =
        runTest {
            val directory = directory()
            File(directory, "a.onnx").writeBytes(ByteArray(4))
            File(directory, "b.onnx").writeBytes(ByteArray(3))
            File(directory, "a.onnx.part").writeBytes(ByteArray(1))
            File(directory, "b.onnx.part").writeBytes(ByteArray(1))
            val store = store(directory)

            assertTrue(store.remove())
            assertFalse(File(directory, "a.onnx").exists())
            assertFalse(File(directory, "b.onnx").exists())
            assertFalse(File(directory, "a.onnx.part").exists())
            assertFalse(File(directory, "b.onnx.part").exists())
            assertTrue(store.remove())
        }

    @Test
    fun theDirectoryNameConstantIsStable() {
        assertEquals("parakeet-eou", ParakeetEouModelStore.DIRECTORY_NAME)
        assertEquals(".part", ParakeetEouModelStore.PART_SUFFIX)
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
