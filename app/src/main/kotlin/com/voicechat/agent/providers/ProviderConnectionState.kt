package com.voicechat.agent.providers

import com.voicechat.agent.credentials.CredentialStatus
import com.voicechat.agent.domain.ConnectionState
import com.voicechat.agent.domain.UnavailableReason

/**
 * Derives the user-visible connection state for a provider from capability
 * metadata and the redacted credential status.
 *
 * It ties the two M13 halves together without a UI dependency: a provider whose
 * credential is not stored is honestly [UnavailableReason.CREDENTIALS_REQUIRED]
 * (never "connected"), and a provider with a stored credential is merely
 * [ConnectionState.Disconnected] until a real request connects. It never reports
 * success on the basis of a stored secret alone.
 */
object ProviderConnectionState {
    fun derive(
        provider: ProviderCapabilities,
        credential: CredentialStatus,
    ): ConnectionState =
        when {
            !provider.requiresCredential -> {
                ConnectionState.Disconnected
            }

            credential is CredentialStatus.Stored -> {
                ConnectionState.Disconnected
            }

            else -> {
                ConnectionState.Unavailable(
                    reason = UnavailableReason.CREDENTIALS_REQUIRED,
                    detail = "${provider.displayName} needs a credential before it can be used.",
                )
            }
        }
}
