package com.voicechat.agent.turn

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Manages the pinned Smart Turn v3.2 model in **app-private** storage (M10).
 *
 * The artifact is never bundled in the APK or placed in `assets/`; it is
 * downloaded app-privately, verified (exact size + SHA-256), installed
 * atomically, and removable, matching the model-lifecycle policy in
 * [docs/model-runtime.md](../docs/model-runtime.md) and
 * [docs/decisions.md](../docs/decisions.md) §3.3.
 *
 * A missing, wrong-size, or wrong-hash file is reported as an explicit
 * [SmartTurnModelState] — never a successful initialization — and the caller
 * falls back to the M09 bounded VAD-only endpoint policy. All file I/O and hashing
 * run on [ioDispatcher], never the main thread.
 */
class SmartTurnModelStore(
    private val directory: File,
    private val artifact: SmartTurnArtifact = SmartTurnArtifact.PINNED,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The app-private file the verified model is installed at. */
    fun modelFile(): File = File(directory, artifact.fileName)

    /**
     * Resolves the current model state, verifying the size and SHA-256 of any
     * present file. Runs off the main thread.
     */
    suspend fun state(): SmartTurnModelState =
        withContext(ioDispatcher) {
            when (val integrity = integrityOf(modelFile())) {
                is SmartTurnIntegrity.Verified -> {
                    SmartTurnModelState.Installed(modelFile(), integrity.sizeBytes)
                }

                SmartTurnIntegrity.Missing -> {
                    SmartTurnModelState.Missing
                }

                is SmartTurnIntegrity.WrongSize -> {
                    SmartTurnModelState.Corrupt(
                        reason = "Smart Turn model size ${integrity.actualBytes} did not match the pinned ${integrity.expectedBytes} bytes",
                        error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Smart Turn model size mismatch"),
                    )
                }

                is SmartTurnIntegrity.WrongHash -> {
                    SmartTurnModelState.Corrupt(
                        reason = "Smart Turn model checksum did not match the pinned SHA-256",
                        error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Smart Turn model checksum mismatch"),
                    )
                }
            }
        }

    /**
     * Downloads, verifies, and atomically installs the artifact.
     *
     * The bytes go to a sibling `.part` file first; only a size- and
     * SHA-256-verified file is moved into place, so a crash or cancelled download
     * can never leave a half-written model that would later be loaded.
     */
    suspend fun install(source: SmartTurnModelSource): SmartTurnInstallResult =
        withContext(ioDispatcher) {
            val temporary = File(directory, "${artifact.fileName}.part")
            try {
                directory.mkdirs()
                temporary.delete()
                source.downloadTo(temporary)
                when (val integrity = integrityOf(temporary)) {
                    is SmartTurnIntegrity.Verified -> {
                        installAtomically(temporary, modelFile())
                        SmartTurnInstallResult.Installed(modelFile(), integrity.sizeBytes)
                    }

                    SmartTurnIntegrity.Missing -> {
                        temporary.delete()
                        SmartTurnInstallResult.Failed(
                            VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Smart Turn download produced no file"),
                        )
                    }

                    is SmartTurnIntegrity.WrongSize -> {
                        temporary.delete()
                        SmartTurnInstallResult.Failed(
                            VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Smart Turn download size mismatch"),
                        )
                    }

                    is SmartTurnIntegrity.WrongHash -> {
                        temporary.delete()
                        SmartTurnInstallResult.Failed(
                            VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Smart Turn download checksum mismatch"),
                        )
                    }
                }
            } catch (cancellation: CancellationException) {
                temporary.delete()
                throw cancellation
            } catch (failure: VoiceAgentException) {
                temporary.delete()
                SmartTurnInstallResult.Failed(failure.error)
            } catch (failure: Throwable) {
                temporary.delete()
                SmartTurnInstallResult.Failed(
                    VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Smart Turn download failed"),
                )
            }
        }

    /** Removes the installed model and any partial download. @return true when absent afterwards. */
    suspend fun remove(): Boolean =
        withContext(ioDispatcher) {
            File(directory, "${artifact.fileName}.part").delete()
            modelFile().delete() || !modelFile().exists()
        }

    /** Verifies a candidate file against the pinned artifact. Pure/deterministic, for tests. */
    internal fun integrityOf(file: File): SmartTurnIntegrity {
        if (!file.isFile) return SmartTurnIntegrity.Missing
        val actualSize = file.length()
        if (actualSize != artifact.sizeBytes) {
            return SmartTurnIntegrity.WrongSize(expectedBytes = artifact.sizeBytes, actualBytes = actualSize)
        }
        val actualHash = hasher.sha256(file)
        return if (actualHash.equals(artifact.sha256, ignoreCase = true)) {
            SmartTurnIntegrity.Verified(actualSize)
        } else {
            SmartTurnIntegrity.WrongHash(expectedSha256 = artifact.sha256, actualSha256 = actualHash)
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
        } catch (failure: IOException) {
            // A filesystem without atomic move support (or a cross-volume temp);
            // fall back to a plain replace but still only with a verified file.
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
        }
    }

    companion object {
        /** The app-private directory name that holds the Smart Turn model. */
        const val DIRECTORY_NAME: String = "smart-turn"
    }
}
