package com.voicechat.agent.local

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import java.io.File
import java.security.MessageDigest

/**
 * A minimal app-private byte store for model bundles.
 *
 * It is the replaceable boundary over app-private storage so the install
 * lifecycle (bounded size, integrity verification, atomic rename, partial-file
 * cleanup, removal) is unit tested on the JVM with an in-memory store, while the
 * Android implementation ([AndroidLocalModelFileStore]) stays a thin file sink.
 * It is never backed by shared/external storage and exposes no path to callers.
 */
interface LocalModelFileStore {
    /** Names currently present (installed and partial). */
    fun names(): List<String>

    /** Size of [name] in bytes, or `null` when it does not exist. */
    fun size(name: String): Long?

    /** Full contents of [name], or `null` when it does not exist. */
    fun read(name: String): ByteArray?

    /**
     * An absolute, loadable path for [name] (for the native runtime), or `null`
     * when it does not exist. The path stays inside app-private storage.
     */
    fun pathFor(name: String): String?

    /** Writes [bytes] as [name], replacing any existing file. */
    fun write(
        name: String,
        bytes: ByteArray,
    )

    /** Atomically renames [from] to [to]; returns false when it could not. */
    fun rename(
        from: String,
        to: String,
    ): Boolean

    /** Deletes [name]; returns false when it was already absent. */
    fun delete(name: String): Boolean
}

/** Lifecycle state of one app-managed artifact. */
sealed interface LocalInstallState {
    /** No installed or partial file exists. */
    data object NotInstalled : LocalInstallState

    /** An install/verification is in progress. */
    data class Downloading(
        val receivedBytes: Long,
        val totalBytes: Long,
    ) : LocalInstallState

    /** The artifact is installed and passed integrity verification. */
    data class Installed(
        val path: String,
        val sizeBytes: Long,
    ) : LocalInstallState

    /** An installed/partial file exists but does not match the expected checksum. */
    data class IntegrityFailed(
        val expectedSha256: String,
        val actualSha256: String,
    ) : LocalInstallState

    /**
     * An installed file exists but its byte size differs from the declared size
     * (truncated, partial copy, or a different build). Used for the large-bundle
     * check that deliberately avoids hashing the whole file.
     */
    data class SizeMismatch(
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : LocalInstallState

    /** The install failed; the artifact is not usable. */
    data class Failed(
        val error: VoiceAgentError,
    ) : LocalInstallState
}

/** SHA-256 helpers used for the install-time integrity check. */
object LocalModelIntegrity {
    /** Lowercase hex SHA-256 of [bytes]. */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    /** Case-insensitive comparison of two hex digests. */
    fun matches(
        expected: String,
        actual: String,
    ): Boolean = expected.equals(actual, ignoreCase = true)
}

/**
 * Installs, verifies, and removes app-managed model bundles in app-private
 * storage.
 *
 * The rules are the `docs/model-runtime.md` lifecycle: a bounded artifact, an
 * integrity check before it can be loaded, an atomic rename from a `.partial`
 * file, partial-file cleanup, and an explicit removal path. No arbitrary URL or
 * file path is accepted: the file name is derived from the allow-listed id.
 */
class LocalModelInstaller(
    private val store: LocalModelFileStore,
) {
    /**
     * The current installed state of [model], verified against its declared
     * size.
     *
     * Verification is **size-only** on purpose: an allow-listed bundle can be
     * over a gigabyte, and hashing it on every availability check would read the
     * whole file every time (and once exhausted the heap, reporting a false
     * `IntegrityFailed`). The full SHA-256 is still enforced at install time in
     * [install], where the bytes are already in hand; here the exact byte size is
     * the integrity signal, which is enough to distinguish installed from
     * missing/truncated.
     */
    fun state(model: ValidatedLocalModel): LocalInstallState {
        val name = fileNameFor(model.artifact)
        val size = store.size(name) ?: return LocalInstallState.NotInstalled
        val expectedSize = model.artifact.downloadBytes ?: return LocalInstallState.NotInstalled
        return if (size == expectedSize) {
            LocalInstallState.Installed(path = store.pathFor(name) ?: name, sizeBytes = size)
        } else {
            LocalInstallState.SizeMismatch(expectedBytes = expectedSize, actualBytes = size)
        }
    }

    /**
     * Installs [bytes] for [model], enforcing the declared size bound and the
     * checksum. A failed verification removes the partial file and reports
     * [LocalInstallState.IntegrityFailed]; nothing is reported installed unless
     * the bytes actually match.
     */
    fun install(
        model: ValidatedLocalModel,
        bytes: ByteArray,
    ): LocalInstallState {
        val artifact = model.artifact
        val expected = artifact.sha256 ?: return failed("the artifact has no checksum")
        val limit = artifact.downloadBytes ?: return failed("the artifact has no declared size")
        if (bytes.size > limit) {
            return failed("the artifact exceeds its declared $limit-byte size")
        }

        val finalName = fileNameFor(artifact)
        val partialName = partialNameFor(artifact)
        return try {
            store.write(partialName, bytes)
            val actual = LocalModelIntegrity.sha256Hex(bytes)
            if (!LocalModelIntegrity.matches(expected, actual)) {
                store.delete(partialName)
                return LocalInstallState.IntegrityFailed(expectedSha256 = expected, actualSha256 = actual)
            }
            if (!store.rename(partialName, finalName)) {
                store.delete(partialName)
                return failed("the verified artifact could not be installed atomically")
            }
            LocalInstallState.Installed(path = finalName, sizeBytes = bytes.size.toLong())
        } catch (failure: Throwable) {
            runCatching { store.delete(partialName) }
            failed("install failed (${failure.javaClass.simpleName})")
        }
    }

    /** Removes the installed bundle and any leftover partial file. */
    fun remove(model: ValidatedLocalModel): Boolean {
        val installed = store.delete(fileNameFor(model.artifact))
        val partial = store.delete(partialNameFor(model.artifact))
        return installed || partial
    }

    /** Deletes partial files left by an interrupted install; returns how many. */
    fun cleanupPartial(): Int {
        val partials = store.names().filter { it.endsWith(PARTIAL_SUFFIX) }
        return partials.count { store.delete(it) }
    }

    private fun failed(detail: String): LocalInstallState.Failed =
        LocalInstallState.Failed(
            VoiceAgentError(code = ErrorCode.MODEL_DOWNLOAD_FAILED, detail = detail),
        )

    companion object {
        /** Suffix for an in-progress install. */
        const val PARTIAL_SUFFIX: String = ".partial"

        /** App-private file name for an allow-listed artifact id. */
        fun fileNameFor(artifact: LocalModelArtifact): String = "${artifact.id.value}.litertlm"

        /** Partial file name for an allow-listed artifact id. */
        fun partialNameFor(artifact: LocalModelArtifact): String = fileNameFor(artifact) + PARTIAL_SUFFIX
    }
}
