package com.voicechat.agent.providers.deepseek

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
 * The DeepSeek provider adapter (M16): an independent remote provider behind the
 * M12 [LanguageModel] contract.
 *
 * The verified endpoint, authentication, streaming shape, thinking/effort
 * controls, usage fields, and error codes are documented, with sources and the
 * access date, in `docs/deepseek-adapter.md`. The adapter holds no provider type
 * from the HTTP or JSON layer; it speaks [RemoteHttpRequest] and [DeepSeekChat]
 * only, and reuses the M14 [RemoteTransport].
 *
 * **Credential handling.** The adapter loads the user credential from the M13
 * [CredentialStore] at request time and places it only in an `Authorization`
 * header. It never returns, logs, or traces the secret; the header-bearing
 * [RemoteHttpRequest] redacts itself on `toString`. No credential means
 * `LLM_NOT_CONFIGURED`, never a send with an empty key.
 *
 * **Failures.** A provider status, a transport failure, a malformed frame, or a
 * terminal-less stream becomes a typed [LlmStreamEvent.Failed]. Cancellation
 * propagates as `CancellationException`. Nothing is reported complete unless the
 * stream reached DeepSeek's terminal chunk (a non-null `finish_reason`) or its
 * `data: [DONE]` sentinel.
 */
class DeepSeekLanguageModel(
    private val credentialStore: CredentialStore,
    private val transport: RemoteTransport,
    private val provider: ProviderCapabilities =
        requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.DEEPSEEK)) {
            "the DeepSeek provider entry is missing from the registry"
        },
    private val destination: ServerDestination = defaultDestination(provider),
) : LanguageModel {
    override val providerId: ProviderId
        get() = provider.providerId

    /**
     * The verified provider-level capability. It is adapter-declared, not
     * inferred: the reasoning union comes from the DeepSeek entry in the M13
     * registry, re-verified at M16.
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
                    url = destination.url(DeepSeekChat.PATH),
                    headers = authHeaders(credential),
                    body = DeepSeekChat.encodeRequest(request),
                )

            var terminal = false
            try {
                transport.streamSse(httpRequest).collect { frame ->
                    if (terminal) return@collect
                    // One DeepSeek frame can carry more than one meaning (the last
                    // chunk carries the finish reason and the usage together), so
                    // the mapping returns them in wire order.
                    for (parsed in DeepSeekChat.parse(frame)) {
                        if (terminal) break
                        when (parsed) {
                            is DeepSeekStreamFrame.Text -> {
                                emit(LlmStreamEvent.Delta(parsed.text))
                            }

                            // Reasoning is a separate channel, never assistant text (R-0066).
                            is DeepSeekStreamFrame.Reasoning -> {
                                Unit
                            }

                            is DeepSeekStreamFrame.Completed -> {
                                terminal = true
                                emit(
                                    LlmStreamEvent.Completed(
                                        usage = parsed.usage,
                                        model = parsed.model,
                                        // DeepSeek does not report the served effort, so it is
                                        // never fabricated.
                                        reasoning = null,
                                    ),
                                )
                            }

                            is DeepSeekStreamFrame.Failed -> {
                                terminal = true
                                emit(LlmStreamEvent.Failed(error = parsed.error, partialText = ""))
                            }

                            DeepSeekStreamFrame.Done -> {
                                // The documented usage/terminal chunk (a non-null finish_reason)
                                // always precedes this sentinel and already emitted the terminal
                                // event. Reaching it un-terminated is an edge case; the provider
                                // said the stream is done, so complete with no fabricated usage.
                                terminal = true
                                emit(LlmStreamEvent.Completed(usage = null, model = null, reasoning = null))
                            }

                            DeepSeekStreamFrame.Ignored -> {
                                Unit
                            }
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
        /** Validates and returns the documented DeepSeek destination. */
        fun defaultDestination(
            provider: ProviderCapabilities =
                requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.DEEPSEEK)),
        ): ServerDestination =
            when (val validation = ProviderEndpointPolicy.destinationFor(provider)) {
                is EndpointValidation.Valid -> validation.destination
                is EndpointValidation.Invalid -> error("the DeepSeek destination is invalid: ${validation.error.code}")
            }
    }
}
