package com.voicechat.agent.providers

import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError

/**
 * Outcome of an optional minimal credential check.
 *
 * [Unsupported] is a first-class, honest result: the provider does not document
 * a cheap check, so the app must not report the credential as valid or invalid.
 * [Valid] and [Invalid] are only produced by a provider adapter that actually
 * performed the documented check (M14+).
 */
sealed interface CredentialValidationResult {
    /** The provider's documented check accepted the credential. */
    data object Valid : CredentialValidationResult

    /** The provider documents no minimal check; validity is unknown. */
    data class Unsupported(
        val reason: String,
    ) : CredentialValidationResult

    /** The provider rejected the credential (for example HTTP 401/403). */
    data class Invalid(
        val error: VoiceAgentError,
    ) : CredentialValidationResult

    /** The check could not complete (network, timeout); validity is unknown. */
    data class Failed(
        val error: VoiceAgentError,
    ) : CredentialValidationResult

    companion object {
        /** The standard authentication-rejected result an adapter returns. */
        fun authenticationRejected(): Invalid = Invalid(VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED))
    }
}

/**
 * Performs the provider's documented minimal credential check.
 *
 * Implementations are provider adapters (M14+). A validator must never log or
 * echo the credential and must map an auth failure to
 * [CredentialValidationResult.authenticationRejected].
 */
interface CredentialValidator {
    suspend fun validate(
        provider: ProviderCapabilities,
        destination: ServerDestination,
        credential: Credential,
    ): CredentialValidationResult
}

/** The honest default for a provider with no documented minimal check. */
object UnsupportedCredentialValidator : CredentialValidator {
    override suspend fun validate(
        provider: ProviderCapabilities,
        destination: ServerDestination,
        credential: Credential,
    ): CredentialValidationResult = CredentialValidationResult.Unsupported("${provider.displayName} documents no minimal credential check")
}

/** Whether a provider's semantics allow any minimal credential check. */
object CredentialValidationPolicy {
    fun supports(provider: ProviderCapabilities): Boolean = provider.auth.validation != CredentialValidationSupport.NONE
}

/**
 * Applies the credential-validation capability before delegating to a validator.
 *
 * This is the enforcement of "implement minimal validation only where provider
 * semantics support it": a provider whose registry entry says [NONE] is never
 * handed to a validator, so the app cannot claim a key is valid for a provider
 * it cannot check.
 */
class CredentialValidationCoordinator(
    private val validator: CredentialValidator = UnsupportedCredentialValidator,
) {
    suspend fun validate(
        provider: ProviderCapabilities,
        destination: ServerDestination,
        credential: Credential,
    ): CredentialValidationResult {
        if (!CredentialValidationPolicy.supports(provider)) {
            return CredentialValidationResult.Unsupported(
                "${provider.displayName} documents no minimal credential check",
            )
        }
        return validator.validate(provider, destination, credential)
    }
}
