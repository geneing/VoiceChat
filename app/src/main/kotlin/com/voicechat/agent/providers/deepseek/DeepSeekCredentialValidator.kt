package com.voicechat.agent.providers.deepseek

import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.providers.CredentialValidationResult
import com.voicechat.agent.providers.CredentialValidator
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.providers.ServerDestination
import com.voicechat.agent.remote.RemoteHttpHeader
import com.voicechat.agent.remote.RemoteHttpRequest
import com.voicechat.agent.remote.RemoteStatusMapper
import com.voicechat.agent.remote.RemoteTransport

/**
 * The documented minimal DeepSeek credential check (M16): `GET /models`, which
 * both lists models and rejects an invalid key.
 *
 * The check never echoes or logs the credential: it puts it in an
 * `Authorization` header, reads only the HTTP status, and returns a typed
 * [CredentialValidationResult]. The response body is bounded by
 * [RemoteTransport.fetch] and never surfaced, so a provider error body cannot
 * leak into a result, trace, or log.
 *
 * DeepSeek documents a 401 for an authentication failure; the shared check also
 * treats a 403 as an authentication rejection.
 */
class DeepSeekCredentialValidator(
    private val transport: RemoteTransport,
) : CredentialValidator {
    override suspend fun validate(
        provider: ProviderCapabilities,
        destination: ServerDestination,
        credential: Credential,
    ): CredentialValidationResult {
        val request =
            RemoteHttpRequest(
                method = "GET",
                url = destination.url(DeepSeekModels.PATH),
                headers =
                    listOf(
                        RemoteHttpHeader(name = "Authorization", value = "Bearer ${credential.secret}"),
                        RemoteHttpHeader(name = "Accept", value = "application/json"),
                    ),
            )
        val response =
            try {
                transport.fetch(request)
            } catch (failure: VoiceAgentException) {
                // A transient failure keeps its typed code; validity is unknown.
                return CredentialValidationResult.Failed(failure.error)
            }
        return when {
            response.status in 200..299 -> CredentialValidationResult.Valid
            response.status == 401 || response.status == 403 -> CredentialValidationResult.authenticationRejected()
            else -> CredentialValidationResult.Invalid(RemoteStatusMapper.errorFor(response.status))
        }
    }
}
