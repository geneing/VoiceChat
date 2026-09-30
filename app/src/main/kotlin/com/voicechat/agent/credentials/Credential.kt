package com.voicechat.agent.credentials

import com.voicechat.agent.diagnostics.Redaction
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.VoiceAgentError

/**
 * The kind of secret a provider accepts.
 *
 * A provider password is deliberately **not** a kind: the app never collects or
 * stores a provider account password (only a provider-issued key or, where the
 * provider documents it, an OAuth token — see `docs/llm-providers.md`). A
 * future OAuth refresh token must add its own kind here, not reuse the password
 * field.
 */
enum class CredentialKind {
    /** A user-created API key / bearer token. */
    API_KEY,

    /** A provider-issued OAuth access token (short-lived). */
    OAUTH_ACCESS_TOKEN,
}

/**
 * A user-supplied credential value for one provider.
 *
 * The [secret] is never shown, logged, or persisted in the clear. [toString] is
 * overridden to redact it so that even an accidental `"stored $credential"` log
 * or crash message cannot leak it; callers must still route any sensitive value
 * through `Redaction`/`AppLog.secret` on purpose-built paths.
 */
data class Credential(
    val providerId: ProviderId,
    val kind: CredentialKind,
    val secret: String,
) {
    init {
        require(secret.isNotBlank()) { "a credential value must not be blank" }
    }

    override fun toString(): String = "Credential(providerId=$providerId, kind=$kind, secret=${Redaction.PLACEHOLDER})"
}

/**
 * The redacted state of one provider's credential.
 *
 * The status never carries the secret or a value derived from it, so it is safe
 * to keep in UI state, logs, and trace metadata. [NotStored] is the honest state
 * for "no credential" and for a stored blob whose KeyStore key was lost (for
 * example after a device restore), which requires the user to re-enter it.
 */
sealed interface CredentialStatus {
    /** No usable credential is stored for the provider. */
    data object NotStored : CredentialStatus

    /** A credential of [kind] is stored for [providerId]. */
    data class Stored(
        val providerId: ProviderId,
        val kind: CredentialKind,
    ) : CredentialStatus
}

/**
 * Outcome of a store/remove operation.
 *
 * Failure is first-class and typed; there is no success-shaped fallback. The
 * error detail is a stable explanation and never contains key material.
 */
sealed interface CredentialStoreOutcome {
    /** The credential was stored (or replaced) or removed. */
    data object Success : CredentialStoreOutcome

    /** A removal found nothing to remove; the provider had no stored credential. */
    data object NotStored : CredentialStoreOutcome

    /** The operation failed; [error] is safe to show and log. */
    data class Failed(
        val error: VoiceAgentError,
    ) : CredentialStoreOutcome
}

/**
 * Stores, replaces, removes, and loads one provider's user-supplied credential.
 *
 * **Security contract.** An implementation must protect the value at rest with
 * an Android Keystore-backed design on device, must not write it to logs, crash
 * metadata, backups, or any export, and must expose only the redacted
 * [CredentialStatus] — never the secret — to UI and diagnostics. [load] is the
 * one method that returns the secret, and its caller is a provider adapter that
 * is about to put it in an auth header; it must not be called from the UI path.
 *
 * **Ownership and lifecycle.** Implementations are app-scoped and stateless
 * between calls (state lives in the KeyStore/preferences), so a new instance
 * reads the same value after a process restart. Calls are `suspend` and do their
 * blocking I/O off the main thread.
 */
interface CredentialStore {
    /** The redacted status for [providerId]. Cheap; no secret is read out. */
    suspend fun status(providerId: ProviderId): CredentialStatus

    /** Stores or **replaces** the credential in [credential]. */
    suspend fun store(credential: Credential): CredentialStoreOutcome

    /** Removes the stored credential for [providerId]. */
    suspend fun remove(providerId: ProviderId): CredentialStoreOutcome

    /**
     * Returns the credential for [providerId], or `null` when none is stored or
     * it can no longer be decrypted. Only a provider adapter may call this.
     */
    suspend fun load(providerId: ProviderId): Credential?
}
