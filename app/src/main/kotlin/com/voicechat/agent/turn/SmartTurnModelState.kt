package com.voicechat.agent.turn

import com.voicechat.agent.domain.VoiceAgentError
import java.io.File

/** The integrity of the on-disk model file against the pinned [SmartTurnArtifact]. */
sealed interface SmartTurnIntegrity {
    /** The file is absent. */
    data object Missing : SmartTurnIntegrity

    /** Size and SHA-256 both match the pinned artifact. */
    data class Verified(
        val sizeBytes: Long,
    ) : SmartTurnIntegrity

    /** The file exists but its size differs from the pinned artifact. */
    data class WrongSize(
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : SmartTurnIntegrity

    /** The file has the expected size but a different SHA-256 (or cannot be hashed). */
    data class WrongHash(
        val expectedSha256: String,
        val actualSha256: String?,
    ) : SmartTurnIntegrity
}

/**
 * The resolved state of the app-private Smart Turn model.
 *
 * [Corrupt] carries a typed [VoiceAgentError] and a safe [reason]; a missing or
 * corrupt model is a first-class result, never a success-shaped fallback.
 */
sealed interface SmartTurnModelState {
    /** The verified model file, ready to load. */
    data class Installed(
        val file: File,
        val sizeBytes: Long,
    ) : SmartTurnModelState

    /** No model file is present; it can be downloaded app-privately. */
    data object Missing : SmartTurnModelState

    /** A model file is present but fails the integrity check. */
    data class Corrupt(
        val reason: String,
        val error: VoiceAgentError,
    ) : SmartTurnModelState
}

/**
 * Reads model bytes into a destination file.
 *
 * The interface exists so the installer's integrity/atomic-install logic is
 * proven by JVM tests with a fake source, and so the single network
 * implementation can be swapped. Implementations must be cancellable and must
 * throw rather than leaving a partial destination on failure.
 */
fun interface SmartTurnModelSource {
    /** Downloads the pinned artifact into [destination], replacing it. */
    suspend fun downloadTo(destination: File)
}

/** Result of a model install attempt. */
sealed interface SmartTurnInstallResult {
    /** The artifact was verified and atomically installed. */
    data class Installed(
        val file: File,
        val sizeBytes: Long,
    ) : SmartTurnInstallResult

    /** The download or integrity check failed; the partial file was removed. */
    data class Failed(
        val error: VoiceAgentError,
    ) : SmartTurnInstallResult
}
