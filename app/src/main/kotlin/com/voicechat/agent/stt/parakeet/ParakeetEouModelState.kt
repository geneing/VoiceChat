package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.turn.ContentHasher
import java.io.File

/**
 * The integrity of one on-disk file against its pinned [ParakeetEouFile].
 *
 * A parallel to `SmartTurnIntegrity`, kept in this package so the five-file store
 * has its own typed result. The per-file check is still delegated to the shared
 * [ContentHasher] seam.
 */
sealed interface ParakeetIntegrity {
    /** The file is absent. */
    data object Missing : ParakeetIntegrity

    /** Size and SHA-256 both match the pinned artifact. */
    data class Verified(
        val sizeBytes: Long,
    ) : ParakeetIntegrity

    /** The file exists but its size differs from the pinned artifact. */
    data class WrongSize(
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : ParakeetIntegrity

    /** The file has the expected size but a different SHA-256 (or cannot be hashed). */
    data class WrongHash(
        val expectedSha256: String,
        val actualSha256: String?,
    ) : ParakeetIntegrity
}

/**
 * The resolved state of the app-private Parakeet-EOU model set.
 *
 * [Installed] means **all five** files are present and verified; a missing or
 * corrupt file is a first-class result, never a success-shaped fallback.
 */
sealed interface ParakeetEouModelState {
    /**
     * Every pinned file exists, has the pinned size, and matches the pinned
     * SHA-256, so the model set is ready to load.
     *
     * @param directory the app-private directory holding all five files.
     * @param totalBytes the sum of the five verified file sizes.
     */
    data class Installed(
        val directory: File,
        val totalBytes: Long,
    ) : ParakeetEouModelState

    /** No complete model set is present; it can be downloaded app-privately. */
    data object Missing : ParakeetEouModelState

    /** A file is present but fails the integrity check. */
    data class Corrupt(
        val reason: String,
        val error: VoiceAgentError,
    ) : ParakeetEouModelState
}

/** Result of a Parakeet-EOU install attempt. */
sealed interface ParakeetEouInstallResult {
    /** All five files were verified and atomically installed. */
    data class Installed(
        val directory: File,
        val totalBytes: Long,
    ) : ParakeetEouInstallResult

    /** A download or integrity check failed; every partial file was removed. */
    data class Failed(
        val error: VoiceAgentError,
    ) : ParakeetEouInstallResult
}
