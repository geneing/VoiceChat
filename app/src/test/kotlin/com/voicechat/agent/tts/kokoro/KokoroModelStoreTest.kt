package com.voicechat.agent.tts.kokoro

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.fake.FakeContentHasher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** M28 model lifecycle tests use small fake graphs and never load ONNX Runtime. */
@OptIn(ExperimentalCoroutinesApi::class)
class KokoroModelStoreTest {
    @get:Rule
    val temporaryFolder: TemporaryFolder = TemporaryFolder()

    private val pinnedHash = "a".repeat(64)
    private val artifact = voice(id = "af_heart", hash = pinnedHash)

    private fun voice(
        id: String,
        hash: String = pinnedHash,
    ) = KokoroVoiceArtifact(
        id = id,
        displayName = "test $id",
        languageTag = "en-US",
        fileName = "model.onnx",
        upstreamPath = "$id/model.onnx",
        sizeBytes = 4,
        sha256 = hash,
    )

    private fun root(): File = temporaryFolder.newFolder("kokoro")

    private fun store(
        root: File,
        hasher: FakeContentHasher = FakeContentHasher(pinnedHash),
    ) = KokoroModelStore(rootDirectory = root, hasher = hasher)

    @Test
    fun anAbsentFileIsMissing() =
        runTest {
            assertEquals(KokoroModelState.Missing, store(root()).state(artifact))
        }

    @Test
    fun aWrongSizeFileIsCorrupt() =
        runTest {
            val root = root()
            val store = store(root)
            store.modelFile(artifact).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(3))
            }

            val state = store.state(artifact)

            assertTrue(state is KokoroModelState.Corrupt)
            assertTrue((state as KokoroModelState.Corrupt).reason.contains("size"))
            assertEquals(ErrorCode.MODEL_CORRUPT, state.error.code)
        }

    @Test
    fun aWrongHashFileIsCorrupt() =
        runTest {
            val root = root()
            val store = store(root, FakeContentHasher("b".repeat(64)))
            store.modelFile(artifact).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(4))
            }

            val state = store.state(artifact)

            assertTrue(state is KokoroModelState.Corrupt)
            assertTrue((state as KokoroModelState.Corrupt).reason.contains("checksum"))
            assertEquals(ErrorCode.MODEL_CORRUPT, state.error.code)
        }

    @Test
    fun aVerifiedFileIsInstalled() =
        runTest {
            val root = root()
            val store = store(root)
            store.modelFile(artifact).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(4))
            }

            assertEquals(
                KokoroModelState.Installed(store.modelFile(artifact), artifact, 4),
                store.state(artifact),
            )
        }

    @Test
    fun installVerifiesAndAtomicallyPlacesTheFile() =
        runTest {
            val store = store(root())
            val source = KokoroModelSource { destination -> destination.writeBytes(ByteArray(4)) }

            val result = store.install(artifact, source)

            assertEquals(KokoroInstallResult.Installed(store.modelFile(artifact), 4), result)
            assertTrue(store.modelFile(artifact).isFile)
            assertFalse(partFile(store).exists())
        }

    @Test
    fun installRejectsAWrongSizeDownloadAndLeavesNoPartialFile() =
        runTest {
            val store = store(root())
            val source = KokoroModelSource { destination -> destination.writeBytes(ByteArray(3)) }

            val result = store.install(artifact, source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as KokoroInstallResult.Failed).error.code)
            assertFalse(store.modelFile(artifact).exists())
            assertFalse(partFile(store).exists())
        }

    @Test
    fun installRejectsAWrongHashDownloadAndLeavesNoPartialFile() =
        runTest {
            val store = store(root(), FakeContentHasher("b".repeat(64)))
            val source = KokoroModelSource { destination -> destination.writeBytes(ByteArray(4)) }

            val result = store.install(artifact, source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as KokoroInstallResult.Failed).error.code)
            assertFalse(store.modelFile(artifact).exists())
            assertFalse(partFile(store).exists())
        }

    @Test
    fun installRejectsASourceThatProducesNoFileAndLeavesNoPartialFile() =
        runTest {
            val store = store(root())

            val result = store.install(artifact, KokoroModelSource { })

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as KokoroInstallResult.Failed).error.code)
            assertFalse(store.modelFile(artifact).exists())
            assertFalse(partFile(store).exists())
        }

    @Test
    fun installMapsASourceFailureAndCleansUpItsPartialFile() =
        runTest {
            val store = store(root())
            val source =
                KokoroModelSource { destination ->
                    destination.writeBytes(ByteArray(2))
                    throw VoiceAgentException(
                        VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "network down"),
                    )
                }

            val result = store.install(artifact, source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as KokoroInstallResult.Failed).error.code)
            assertFalse(store.modelFile(artifact).exists())
            assertFalse(partFile(store).exists())
        }

    @Test
    fun installMapsAnUnexpectedSourceFailureAndCleansUp() =
        runTest {
            val store = store(root())
            val source =
                KokoroModelSource { destination ->
                    destination.writeBytes(ByteArray(2))
                    throw IOException("connection failed")
                }

            val result = store.install(artifact, source)

            assertEquals(ErrorCode.MODEL_DOWNLOAD_FAILED, (result as KokoroInstallResult.Failed).error.code)
            assertFalse(partFile(store).exists())
        }

    @Test
    fun installCancellationPropagatesAndRemovesThePartialFile() =
        runTest {
            val store =
                KokoroModelStore(
                    rootDirectory = root(),
                    hasher = FakeContentHasher(pinnedHash),
                    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
                )
            val source =
                KokoroModelSource { destination ->
                    destination.writeBytes(ByteArray(2))
                    awaitCancellation()
                }
            val job = launch { store.install(artifact, source) }

            runCurrent()
            assertTrue(partFile(store).exists())
            job.cancelAndJoin()

            assertTrue(job.isCancelled)
            assertFalse(partFile(store).exists())
            assertFalse(store.modelFile(artifact).exists())
        }

    @Test
    fun removeDeletesInstalledAndPartialFilesAndIsIdempotent() =
        runTest {
            val store = store(root())
            store.modelFile(artifact).apply {
                parentFile?.mkdirs()
                writeBytes(ByteArray(4))
            }
            partFile(store).writeBytes(ByteArray(1))

            assertTrue(store.remove(artifact))
            assertFalse(store.modelFile(artifact).exists())
            assertFalse(partFile(store).exists())
            assertTrue(store.remove(artifact))
        }

    @Test
    fun eachVoiceHasItsOwnDirectoryAndPinnedSize() =
        runTest {
            val root = root()
            val store = store(root)
            val secondVoice = voice("if_sara", hash = "c".repeat(64))

            assertEquals(File(root, artifact.id), store.voiceDirectory(artifact))
            assertEquals(File(root, secondVoice.id), store.voiceDirectory(secondVoice))
            assertEquals(4L, store.pinnedSizeBytes(artifact))
            assertTrue(store.modelFile(artifact) != store.modelFile(secondVoice))
        }

    private fun partFile(store: KokoroModelStore): File = File(store.modelFile(artifact).parentFile, "${artifact.fileName}.part")
}
