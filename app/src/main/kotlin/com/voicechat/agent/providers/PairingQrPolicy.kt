package com.voicechat.agent.providers

/** Why a scanned QR payload must not be used. */
enum class QrRejection {
    /** The payload was empty. */
    EMPTY,

    /** The payload embeds an API key, token, password, or other reusable credential. */
    CARRIES_CREDENTIAL,

    /** The payload is not a provider pairing challenge this app can complete. */
    NOT_A_PAIRING_CHALLENGE,
}

/** Outcome of inspecting a QR payload. */
sealed interface QrInspection {
    /**
     * The payload is a provider-supported, short-lived pairing challenge.
     *
     * No provider documents a QR pairing flow today (`docs/decisions.md` §4), so
     * [PairingQrPolicy.inspect] does not currently produce this. The type exists
     * so a future, provider-documented flow has a place to land without ever
     * accepting a reusable credential.
     */
    data class PairingChallenge(
        val authority: String,
        val challengeId: String,
    ) : QrInspection

    /** The payload must be refused; [reason] says why. */
    data class Rejected(
        val reason: QrRejection,
    ) : QrInspection
}

/**
 * Inspects scanned QR payloads.
 *
 * Two rules are absolute (`docs/privacy-and-security.md`, `AGENTS.md`):
 * a QR payload must **never** carry a reusable credential (API key, access
 * token, refresh token, password), and an arbitrary QR URL must **never** be
 * trusted as an endpoint ([ServerDestinationValidator] refuses a
 * [EndpointSource.QR_PAYLOAD]). Because no provider documents a QR pairing flow,
 * every payload is rejected today; the credential check is the part that must
 * never regress.
 */
object PairingQrPolicy {
    private val providerSecret = Regex("\\b(sk|rk)-[A-Za-z0-9_-]{8,}")
    private val bearerToken = Regex("\\bbearer\\s+[A-Za-z0-9._-]{8,}")
    private val credentialKeys =
        listOf(
            "api_key",
            "apikey",
            "api-key",
            "access_token",
            "refresh_token",
            "client_secret",
            "secret",
            "password",
            "credential",
            "authorization",
            "token",
        )

    /** True when [payload] embeds a reusable credential in any common encoding. */
    fun carriesReusableCredential(payload: String): Boolean {
        if (providerSecret.containsMatchIn(payload)) return true
        val lower = payload.lowercase()
        if (bearerToken.containsMatchIn(lower)) return true
        return credentialKeys.any { key ->
            Regex("(^|[^a-z0-9_])${Regex.escape(key)}\"?\\s*[:=]\\s*[\"']?[a-z0-9._-]{4,}").containsMatchIn(lower)
        }
    }

    /** Inspects [payload]; see [QrInspection] for the outcomes. */
    fun inspect(payload: String): QrInspection {
        val trimmed = payload.trim()
        if (trimmed.isEmpty()) return QrInspection.Rejected(QrRejection.EMPTY)
        if (carriesReusableCredential(trimmed)) return QrInspection.Rejected(QrRejection.CARRIES_CREDENTIAL)
        return QrInspection.Rejected(QrRejection.NOT_A_PAIRING_CHALLENGE)
    }
}
