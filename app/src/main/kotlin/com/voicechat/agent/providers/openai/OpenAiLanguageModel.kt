package com.voicechat.agent.providers.openai

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRequestValidation
import com.voicechat.agent.contracts.LlmRequestValidator
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialStore
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.providers.EndpointValidation
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderEndpointPolicy
import com.voicechat.agent.providers.ServerDestination
import com.voicechat.agent.remote.RemoteHttpHeader
import com.voicechat.agent.remote.RemoteHttpRequest
import com.voicechat.agent.remote.RemoteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The OpenAI provider adapter: the first complete remote provider behind the M12
 * [LanguageModel] contract (M14).
 *
 * The verified endpoint, authentication, streaming event names, usage shape,
 * reasoning controls, and the `store: false` privacy requirement are documented,
 * with sources and access date, in `docs/openai-adapter.md`. This class holds no
 * provider type from the HTTP or JSON layer; it speaks [RemoteHttpRequest] and
 * [OpenAiResponses] only.
 *
 * **Credential handling.** The adapter loads the user credential from the M13
 * [CredentialStore] at request time and places it only in an `Authorization`
 * header. It never returns, logs, or traces the secret; the header-bearing
 * [RemoteHttpRequest] redacts itself on `toString`. No credential means
 * `LLM_NOT_CONFIGURED`, never a send with an empty key.
 *
 * **Failures.** A provider status, a transport failure, a malformed frame, or a
 * terminal-less stream becomes a typed [LlmStreamEvent.Failed]. Cancellation
 * propagates as `CancellationException`. Nothing is reported complete unless
 * `response.completed` was actually received.
 */
class OpenAiLanguageModel(
    private val credentialStore: CredentialStore,
    private val transport: RemoteTransport,
    private val provider: ProviderCapabilities =
        requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENAI)) {
            "the OpenAI provider entry is missing from the registry"
        },
    private val destination: ServerDestination = defaultDestination(provider),
) : LanguageModel {
    override val providerId: ProviderId
        get() = provider.providerId

    /**
     * The verified provider-level capability. It is adapter-declared, not
     * inferred: the reasoning union comes from the OpenAI entry in the M13
     * registry, re-verified at M14.
     */
    override val capabilities: LlmCapabilities
        get() = provider.toLlmCapabilities()

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            val validation = LlmRequestValidator.validate(request, capabilities)
            if (validation is LlmRequestValidation.Unsupported) {
                emit(LlmStreamEvent.Failed(error = validation.error, partialText = ""))
                return@flow
            }

            val credential = credentialStore.load(providerId)
            if (credential == null) {
                emit(
                    LlmStreamEvent.Failed(
                        error =
                            VoiceAgentError(
                                ErrorCode.LLM_NOT_CONFIGURED,
                                "no ${provider.displayName} credential is stored",
                            ),
                        partialText = "",
                    ),
                )
                return@flow
            }

            val httpRequest =
                RemoteHttpRequest(
                    method = "POST",
                    url = destination.url(OpenAiResponses.PATH),
                    headers = authHeaders(credential),
                    body = OpenAiResponses.encodeRequest(request),
                )

            var terminal = false
            try {
                transport.streamSse(httpRequest).collect { frame ->
                    if (terminal) return@collect
                    when (val parsed = OpenAiResponses.parse(frame)) {
                        is OpenAiStreamFrame.Text -> {
                            emit(LlmStreamEvent.Delta(parsed.text))
                        }

                        // Reasoning is a separate channel, never assistant text (R-0066).
                        is OpenAiStreamFrame.Reasoning -> {
                            Unit
                        }

                        is OpenAiStreamFrame.Completed -> {
                            terminal = true
                            emit(
                                LlmStreamEvent.Completed(
                                    usage = parsed.usage,
                                    model = parsed.model,
                                    reasoning = parsed.reasoning,
                                ),
                            )
                        }

                        is OpenAiStreamFrame.Failed -> {
                            terminal = true
                            emit(LlmStreamEvent.Failed(error = parsed.error, partialText = ""))
                        }

                        OpenAiStreamFrame.Ignored -> {
                            Unit
                        }
                    }
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: VoiceAgentException) {
                // The reference consumer keeps the deltas received so far.
                terminal = true
                emit(LlmStreamEvent.Failed(error = failure.error, partialText = ""))
            }

            if (!terminal) {
                // R-0067: a stream that ends without a terminal event is not a completion.
                emit(
                    LlmStreamEvent.Failed(
                        error =
                            VoiceAgentError(
                                ErrorCode.LLM_MALFORMED_RESPONSE,
                                "the stream ended without a terminal event",
                            ),
                        partialText = "",
                    ),
                )
            }
        }

    override suspend fun close() = transport.close()

    private fun authHeaders(credential: Credential): List<RemoteHttpHeader> =
        listOf(
            RemoteHttpHeader(name = "Authorization", value = "Bearer ${credential.secret}"),
            RemoteHttpHeader(name = "Content-Type", value = "application/json"),
            RemoteHttpHeader(name = "Accept", value = "text/event-stream"),
        )

    companion object {
        /** Validates and returns the documented OpenAI destination. */
        fun defaultDestination(
            provider: ProviderCapabilities =
                requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENAI)),
        ): ServerDestination =
            when (val validation = ProviderEndpointPolicy.destinationFor(provider)) {
                is EndpointValidation.Valid -> validation.destination
                is EndpointValidation.Invalid -> error("the OpenAI destination is invalid: ${validation.error.code}")
            }
    }
}
