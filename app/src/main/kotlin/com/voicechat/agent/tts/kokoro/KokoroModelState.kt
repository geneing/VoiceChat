package com.voicechat.agent.tts.kokoro

import com.voicechat.agent.domain.VoiceAgentError
import java.io.File

/** The integrity of one on-disk Kokoro graph against its pinned [KokoroVoiceArtifact]. */
sealed interface KokoroIntegrity {
    /** The file is absent. */
    data object Missing : KokoroIntegrity

    /** Size and SHA-256 both match the pinned artifact. */
    data class Verified(
        val sizeBytes: Long,
    ) : KokoroIntegrity

    /** The file exists but its size differs from the pinned artifact. */
    data class WrongSize(
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : KokoroIntegrity

    /** The file has the expected size but a different SHA-256 (or cannot be hashed). */
    data class WrongHash(
        val expectedSha256: String,
        val actualSha256: String?,
    ) : KokoroIntegrity
}

/**
 * Where a verified voice graph's bytes live.
 *
 * The pinned graph is normally **bundled** in the APK's assets, because it is the
 * app's default voice and a fresh install must be able to speak with no download
 * (`docs/decisions.md` §2.4). A build without the bundled asset — a clean
 * checkout, before `scripts/fetch-models.sh` — reports the voice as installable
 * and uses an **installed** graph in app-private storage instead, which is what
 * the settings action and `scripts/push-models.sh` produce.
 */
sealed interface KokoroGraphSource {
    /** The graph packaged at [assetPath] inside the APK. */
    data class Bundled(
        val assetPath: String,
    ) : KokoroGraphSource

    /** A verified graph in app-private storage, which overrides the bundled one. */
    data class Installed(
        val file: File,
    ) : KokoroGraphSource
}

/**
 * The resolved state of one Kokoro voice graph.
 *
 * [Corrupt] carries a typed [VoiceAgentError] and a safe [reason]; a missing or
 * corrupt graph is a first-class result, never a success-shaped fallback, so the
 * app reports "install the voice model" instead of speaking with a broken engine
 * (`docs/decisions.md` §2.2).
 */
sealed interface KokoroModelState {
    /** The verified graph, ready to load, and where it came from. */
    data class Installed(
        val source: KokoroGraphSource,
        val voice: KokoroVoiceArtifact,
        val sizeBytes: Long,
    ) : KokoroModelState

    /** No graph is present for this voice; the app can install one app-privately. */
    data object Missing : KokoroModelState

    /** A graph is present but fails the integrity check. */
    data class Corrupt(
        val reason: String,
        val error: VoiceAgentError,
    ) : KokoroModelState
}

/**
 * Reads model bytes into a destination file.
 *
 * The interface exists so the installer's integrity/atomic-install logic is
 * proven by JVM tests with a fake source, and so the single network
 * implementation can be swapped. Implementations must be cancellable and must
 * throw rather than leaving a partial destination on failure.
 */
fun interface KokoroModelSource {
    /** Downloads the pinned artifact into [destination], replacing it. */
    suspend fun downloadTo(destination: File)
}

/** Result of one voice install attempt. */
sealed interface KokoroInstallResult {
    /** The artifact was verified and atomically installed. */
    data class Installed(
        val file: File,
        val sizeBytes: Long,
    ) : KokoroInstallResult

    /** The download or integrity check failed; the partial file was removed. */
    data class Failed(
        val error: VoiceAgentError,
    ) : KokoroInstallResult
}
