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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages pinned Kokoro voice graphs in **app-private** storage (M28).
 *
 * Each graph is downloaded to `root/<voice>/<file>.part`, verified by exact
 * size and SHA-256, and moved into place only after verification. The graph is
 * never bundled in the APK; see `docs/model-runtime.md` and `docs/decisions.md`.
 * All file I/O and hashing run on [ioDispatcher].
 */
class KokoroModelStore(
    private val rootDirectory: File,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The app-private file the verified graph is installed at. */
    fun modelFile(voice: KokoroVoiceArtifact): File = File(voiceDirectory(voice), voice.fileName)

    /** The app-private directory for one voice, separate from every other voice. */
    fun voiceDirectory(voice: KokoroVoiceArtifact): File = File(rootDirectory, voice.id)

    /** The pinned graph's expected byte size, for progress/description totals. */
    fun pinnedSizeBytes(voice: KokoroVoiceArtifact): Long = voice.sizeBytes

    /** Resolves the graph's current state, verifying size and SHA-256 off the main thread. */
    suspend fun state(voice: KokoroVoiceArtifact): KokoroModelState =
        withContext(ioDispatcher) {
            val file = modelFile(voice)
            when (val integrity = integrityOf(file, voice)) {
                is KokoroIntegrity.Verified -> {
                    KokoroModelState.Installed(file, voice, integrity.sizeBytes)
                }

                KokoroIntegrity.Missing -> {
                    KokoroModelState.Missing
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

    /** Downloads, verifies, and atomically installs one voice graph. */
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
