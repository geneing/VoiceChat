package com.voicechat.agent.tts.kokoro

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.turn.ContentHasher
import com.voicechat.agent.turn.Sha256ContentHasher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages pinned Kokoro voice graphs (M28).
 *
 * Two sources, in priority order:
 *
 * 1. an **app-private install** at `root/<voice>/<file>`, which is what the
 *    settings action and `scripts/push-models.sh` produce and which overrides the
 *    bundled copy (so a developer or a user can replace the graph);
 * 2. the **bundled asset** `kokoro/<voice>/<file>` inside the APK, which is how
 *    the pinned graph ships and why a fresh install speaks with no download
 *    (`docs/decisions.md` §2.4).
 *
 * Either way the bytes are verified by exact size and SHA-256 before they are
 * handed to ONNX Runtime, and a graph that is present but wrong is reported as an
 * explicit state rather than a success-shaped fallback. A download goes to a
 * sibling `.part` file and is moved into place only after verification. The
 * bundled asset is immutable for the life of the process, so its verification is
 * cached; an installed file is re-verified every time because it can change. All
 * file I/O and hashing run on [ioDispatcher].
 */
class KokoroModelStore(
    private val rootDirectory: File,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Opens a bundled asset, or `null` when this build has no asset source.
     *
     * It is the only Android touch point, so JVM tests supply their own or none.
     */
    private val openAsset: ((String) -> InputStream)? = null,
) {
    /** Verification of the immutable bundled graphs, once per process. */
    private val bundledStates = ConcurrentHashMap<String, KokoroModelState>()

    /** The app-private file an installed graph lives at. */
    fun modelFile(voice: KokoroVoiceArtifact): File = File(voiceDirectory(voice), voice.fileName)

    /** The app-private directory for one voice, separate from every other voice. */
    fun voiceDirectory(voice: KokoroVoiceArtifact): File = File(rootDirectory, voice.id)

    /** The pinned graph's expected byte size, for progress/description totals. */
    fun pinnedSizeBytes(voice: KokoroVoiceArtifact): Long = voice.sizeBytes

    /** The asset path the pinned graph is packaged at in a build that bundles it. */
    fun bundledAssetPath(voice: KokoroVoiceArtifact): String = KokoroArtifact.bundledAssetPath(voice)

    /**
     * Resolves the graph's current state, verifying size and SHA-256 off the main
     * thread: an app-private install first, then the bundled asset.
     */
    suspend fun state(voice: KokoroVoiceArtifact): KokoroModelState =
        withContext(ioDispatcher) {
            val file = modelFile(voice)
            when (val integrity = integrityOf(file, voice)) {
                is KokoroIntegrity.Verified -> {
                    KokoroModelState.Installed(KokoroGraphSource.Installed(file), voice, integrity.sizeBytes)
                }

                KokoroIntegrity.Missing -> {
                    bundledState(voice)
                }

                is KokoroIntegrity.WrongSize -> {
                    KokoroModelState.Corrupt(
                        reason =
                            "Kokoro ${voice.id} model size ${integrity.actualBytes} did not match the pinned " +
                                "${integrity.expectedBytes} bytes",
                        error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Kokoro model size mismatch"),
                    )
                }

                is KokoroIntegrity.WrongHash -> {
                    KokoroModelState.Corrupt(
                        reason = "Kokoro ${voice.id} model checksum did not match the pinned SHA-256",
                        error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Kokoro model checksum mismatch"),
                    )
                }
            }
        }

    /**
     * The graph bytes for a resolved [source], verified by the caller's [state]
     * check. The bundled form is the only one that needs reading: an installed
     * graph is loaded from its path so ONNX Runtime can map the file.
     */
    suspend fun read(source: KokoroGraphSource): ByteArray =
        withContext(ioDispatcher) {
            when (source) {
                is KokoroGraphSource.Installed -> {
                    source.file.readBytes()
                }

                is KokoroGraphSource.Bundled -> {
                    val opener = requireNotNull(openAsset) { "this build has no bundled Kokoro asset source" }
                    opener(source.assetPath).use { it.readBytes() }
                }
            }
        }

    /** The bundled asset's state, verified once per process because assets cannot change. */
    private fun bundledState(voice: KokoroVoiceArtifact): KokoroModelState {
        val opener = openAsset ?: return KokoroModelState.Missing
        val path = bundledAssetPath(voice)
        return bundledStates.getOrPut(voice.id) {
            val bytes =
                try {
                    opener(path).use { it.readBytes() }
                } catch (_: FileNotFoundException) {
                    return@getOrPut KokoroModelState.Missing
                } catch (_: IOException) {
                    return@getOrPut KokoroModelState.Missing
                }
            when {
                bytes.size.toLong() != voice.sizeBytes -> {
                    KokoroModelState.Corrupt(
                        reason = "the bundled Kokoro ${voice.id} graph is ${bytes.size} bytes, not the pinned ${voice.sizeBytes}",
                        error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Bundled Kokoro model size mismatch"),
                    )
                }

                !hasher.sha256(bytes).equals(voice.sha256, ignoreCase = true) -> {
                    KokoroModelState.Corrupt(
                        reason = "the bundled Kokoro ${voice.id} graph did not match the pinned SHA-256",
                        error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Bundled Kokoro model checksum mismatch"),
                    )
                }

                else -> {
                    KokoroModelState.Installed(KokoroGraphSource.Bundled(path), voice, bytes.size.toLong())
                }
            }
        }
    }

    /** Downloads, verifies, and atomically installs one voice graph app-privately. */
    suspend fun install(
        voice: KokoroVoiceArtifact,
        source: KokoroModelSource,
    ): KokoroInstallResult =
        withContext(ioDispatcher) {
            val directory = voiceDirectory(voice)
            val temporary = File(directory, "${voice.fileName}.part")
            try {
                if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
                    throw IOException("could not create Kokoro model directory")
                }
                temporary.delete()
                source.downloadTo(temporary)
                when (val integrity = integrityOf(temporary, voice)) {
                    is KokoroIntegrity.Verified -> {
                        val target = modelFile(voice)
                        installAtomically(temporary, target)
                        KokoroInstallResult.Installed(target, integrity.sizeBytes)
                    }

                    KokoroIntegrity.Missing -> {
                        KokoroInstallResult.Failed(
                            VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Kokoro download produced no file"),
                        )
                    }

                    is KokoroIntegrity.WrongSize -> {
                        KokoroInstallResult.Failed(
                            VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Kokoro download size mismatch"),
                        )
                    }

                    is KokoroIntegrity.WrongHash -> {
                        KokoroInstallResult.Failed(
                            VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Kokoro download checksum mismatch"),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: VoiceAgentException) {
                KokoroInstallResult.Failed(failure.error)
            } catch (_: Throwable) {
                KokoroInstallResult.Failed(
                    VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Kokoro model download failed"),
                )
            } finally {
                temporary.delete()
            }
        }

    /** Removes the installed graph and any interrupted download. */
    suspend fun remove(voice: KokoroVoiceArtifact): Boolean =
        withContext(ioDispatcher) {
            val file = modelFile(voice)
            File(file.parentFile, "${voice.fileName}.part").delete()
            file.delete() || !file.exists()
        }

    /** Verifies a candidate file against its pinned voice artifact. */
    internal fun integrityOf(
        file: File,
        voice: KokoroVoiceArtifact,
    ): KokoroIntegrity {
        if (!file.isFile) return KokoroIntegrity.Missing
        val actualSize = file.length()
        if (actualSize != voice.sizeBytes) {
            return KokoroIntegrity.WrongSize(expectedBytes = voice.sizeBytes, actualBytes = actualSize)
        }
        val actualHash = hasher.sha256(file)
        return if (actualHash.equals(voice.sha256, ignoreCase = true)) {
            KokoroIntegrity.Verified(actualSize)
        } else {
            KokoroIntegrity.WrongHash(expectedSha256 = voice.sha256, actualSha256 = actualHash)
        }
    }

    private fun installAtomically(
        temporary: File,
        target: File,
    ) {
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            // Some filesystems lack atomic move support; the candidate is verified before this fallback.
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }
}
