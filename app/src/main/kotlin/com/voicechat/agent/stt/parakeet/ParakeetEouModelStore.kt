package com.voicechat.agent.stt.parakeet

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
 * Manages the pinned Parakeet-EOU model set in **app-private** storage (stream 2).
 *
 * The five files are never bundled in the APK or placed in `assets/`; they are
 * downloaded app-privately, each verified (exact size + SHA-256), installed
 * atomically as a set, and removable, matching the model-lifecycle policy in
 * [docs/model-runtime.md](../../../../../../../../../../docs/model-runtime.md) and
 * the Smart Turn precedent (`SmartTurnModelStore`).
 *
 * A missing, wrong-size, or wrong-hash file is reported as an explicit
 * [ParakeetEouModelState] — never a successful initialization. All file I/O and
 * hashing run on [ioDispatcher], never the main thread.
 *
 * @param directory the app-private directory that holds the five files (usually
 *   `<filesDir>/parakeet-eou`; see [DIRECTORY_NAME]).
 */
class ParakeetEouModelStore(
    private val directory: File,
    private val artifact: ParakeetEouArtifact = ParakeetEouArtifact.PINNED,
    private val hasher: ContentHasher = Sha256ContentHasher,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** The app-private file that pinned [fileName] is installed at. */
    fun file(fileName: String): File = File(directory, fileName)

    /** The encoder graph file, the largest of the five. */
    fun encoderFile(): File = file(ENCODER_FILE_NAME)

    /** @return the pinned file named [fileName], or `null` when unknown. */
    fun pinned(fileName: String): ParakeetEouFile? = artifact.file(fileName)

    /** The pinned set's total expected byte size, for a progress/description total. */
    fun pinnedTotalBytes(): Long = artifact.totalBytes

    /**
     * Resolves the current model state, verifying the size and SHA-256 of every
     * one of the five files. Runs off the main thread.
     *
     * A missing file reports [ParakeetEouModelState.Missing]; the first file that
     * exists but fails integrity reports [ParakeetEouModelState.Corrupt] with that
     * file's name in the reason.
     */
    suspend fun state(): ParakeetEouModelState =
        withContext(ioDispatcher) {
            var total = 0L
            for (pinned in artifact.files) {
                when (val integrity = integrityOf(pinned, file(pinned.fileName))) {
                    is ParakeetIntegrity.Verified -> {
                        total += integrity.sizeBytes
                    }

                    ParakeetIntegrity.Missing -> {
                        return@withContext ParakeetEouModelState.Missing
                    }

                    is ParakeetIntegrity.WrongSize -> {
                        return@withContext ParakeetEouModelState.Corrupt(
                            reason =
                                "${pinned.fileName}: size ${integrity.actualBytes} did not match " +
                                    "the pinned ${integrity.expectedBytes} bytes",
                            error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Parakeet-EOU model size mismatch"),
                        )
                    }

                    is ParakeetIntegrity.WrongHash -> {
                        return@withContext ParakeetEouModelState.Corrupt(
                            reason = "${pinned.fileName}: checksum did not match the pinned SHA-256",
                            error = VoiceAgentError(ErrorCode.MODEL_CORRUPT, "Parakeet-EOU model checksum mismatch"),
                        )
                    }
                }
            }
            ParakeetEouModelState.Installed(directory, total)
        }

    /**
     * Downloads, verifies, and atomically installs all five files.
     *
     * Each file's bytes go to a sibling `.part` file first; only after **all
     * five** verify is each moved into place. A crash, cancellation, or failed
     * download can never leave a half-verified model set: every `.part` is
     * deleted and, on failure, the target files for this attempt are removed.
     */
    suspend fun install(source: ParakeetEouModelSource): ParakeetEouInstallResult =
        withContext(ioDispatcher) {
            val temporaryFiles = mutableListOf<File>()
            try {
                directory.mkdirs()
                for (pinned in artifact.files) {
                    val temporary = File(directory, "${pinned.fileName}$PART_SUFFIX")
                    temporary.delete()
                    temporaryFiles += temporary
                    source.downloadTo(artifact.downloadUrl(pinned), temporary)
                    when (val integrity = integrityOf(pinned, temporary)) {
                        is ParakeetIntegrity.Verified -> {
                            Unit
                        }

                        ParakeetIntegrity.Missing -> {
                            return@withContext fail(
                                ErrorCode.MODEL_DOWNLOAD_FAILED,
                                "Parakeet-EOU download produced no ${pinned.fileName}",
                                temporaryFiles,
                            )
                        }

                        is ParakeetIntegrity.WrongSize -> {
                            return@withContext fail(
                                ErrorCode.MODEL_DOWNLOAD_FAILED,
                                "Parakeet-EOU download size mismatch for ${pinned.fileName}",
                                temporaryFiles,
                            )
                        }

                        is ParakeetIntegrity.WrongHash -> {
                            return@withContext fail(
                                ErrorCode.MODEL_DOWNLOAD_FAILED,
                                "Parakeet-EOU download checksum mismatch for ${pinned.fileName}",
                                temporaryFiles,
                            )
                        }
                    }
                }

                // Every file verified; move each into place. REPLACE_EXISTING keeps
                // a re-install idempotent.
                for (pinned in artifact.files) {
                    installAtomically(
                        File(directory, "${pinned.fileName}$PART_SUFFIX"),
                        file(pinned.fileName),
                    )
                }
                ParakeetEouInstallResult.Installed(directory, artifact.totalBytes)
            } catch (cancellation: CancellationException) {
                deletePartials(temporaryFiles)
                throw cancellation
            } catch (failure: VoiceAgentException) {
                deletePartials(temporaryFiles)
                ParakeetEouInstallResult.Failed(failure.error)
            } catch (failure: Throwable) {
                deletePartials(temporaryFiles)
                ParakeetEouInstallResult.Failed(
                    VoiceAgentError(ErrorCode.MODEL_DOWNLOAD_FAILED, "Parakeet-EOU download failed"),
                )
            }
        }

    /** Removes all five installed files and any partial download. @return true when absent afterwards. */
    suspend fun remove(): Boolean =
        withContext(ioDispatcher) {
            for (pinned in artifact.files) {
                File(directory, "${pinned.fileName}$PART_SUFFIX").delete()
                file(pinned.fileName).delete()
            }
            artifact.files.none { file(it.fileName).exists() }
        }

    /**
     * Verifies a candidate file against its pinned identity. Pure/deterministic,
     * for tests; [hasher] is the injected [ContentHasher].
     */
    internal fun integrityOf(
        pinned: ParakeetEouFile,
        candidate: File,
    ): ParakeetIntegrity {
        if (!candidate.isFile) return ParakeetIntegrity.Missing
        val actualSize = candidate.length()
        if (actualSize != pinned.sizeBytes) {
            return ParakeetIntegrity.WrongSize(expectedBytes = pinned.sizeBytes, actualBytes = actualSize)
        }
        val actualHash = hasher.sha256(candidate)
        return if (actualHash.equals(pinned.sha256, ignoreCase = true)) {
            ParakeetIntegrity.Verified(actualSize)
        } else {
            ParakeetIntegrity.WrongHash(expectedSha256 = pinned.sha256, actualSha256 = actualHash)
        }
    }

    private fun fail(
        code: ErrorCode,
        message: String,
        temporaryFiles: List<File>,
    ): ParakeetEouInstallResult.Failed {
        deletePartials(temporaryFiles)
        return ParakeetEouInstallResult.Failed(VoiceAgentError(code, message))
    }

    private fun deletePartials(temporaryFiles: List<File>) {
        temporaryFiles.forEach { it.delete() }
        artifact.files.forEach { File(directory, "${it.fileName}$PART_SUFFIX").delete() }
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
        /** The app-private directory name that holds the Parakeet-EOU model set. */
        const val DIRECTORY_NAME: String = "parakeet-eou"

        /** Suffix for an in-flight download that has not been verified yet. */
        const val PART_SUFFIX: String = ".part"

        /** The encoder graph file's name, used by [encoderFile]. */
        const val ENCODER_FILE_NAME: String = "parakeet-eou-encoder.onnx"
    }
}
