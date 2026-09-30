package com.voicechat.agent.providers.openrouter

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRequestValidation
import com.voicechat.agent.contracts.LlmRequestValidator
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.LlmUsage
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialStore
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
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
 * The OpenRouter provider adapter (M15), the second complete remote provider
 * behind the M12 [LanguageModel] contract.
 *
 * The verified endpoint, authentication, model discovery, streaming shape,
 * parameters, usage, and error semantics are documented, with sources and access
 * date, in `docs/openrouter-adapter.md`. This class holds no provider type from
 * the HTTP or JSON layer; it speaks [RemoteHttpRequest] and
 * [OpenRouterChatCompletions] only.
 *
 * **No silent routing (R-0017).** OpenRouter will, by default, fall back to
 * another provider or GPU on a 5xx or a rate limit, and `models[]` / `route:
 * "fallback"` route across different models. `OpenRouterChatCompletions.encodeRequest`
 * therefore disables fallbacks and never sends a model list, so the selected
 * model is the only candidate. The model OpenRouter reports in the response is
 * surfaced on [LlmStreamEvent.Completed.model] so a request that reached a
 * different model than the selection is visible rather than silently accepted.
 *
 * **Credential handling.** The adapter loads the user credential from the M13
 * [CredentialStore] at request time and places it only in an `Authorization`
 * header. It never returns, logs, or traces the secret; the header-bearing
 * [RemoteHttpRequest] redacts itself on `toString`. No credential means
 * `LLM_NOT_CONFIGURED`, never a send with an empty key.
 *
 * **Failures.** A provider status, a transport failure, a malformed frame, a
 * mid-stream `error` object, or a terminal-less stream becomes a typed
 * [LlmStreamEvent.Failed]. Cancellation propagates as `CancellationException`.
 * Nothing is reported complete unless OpenRouter's `[DONE]` sentinel was
 * received.
 */
class OpenRouterLanguageModel(
    private val credentialStore: CredentialStore,
    private val transport: RemoteTransport,
    private val provider: ProviderCapabilities =
        requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENROUTER)) {
            "the OpenRouter provider entry is missing from the registry"
        },
    private val destination: ServerDestination = defaultDestination(provider),
) : LanguageModel {
    override val providerId: ProviderId
        get() = provider.providerId

    /**
     * The verified provider-level capability. It is adapter-declared, not
     * inferred: the reasoning union comes from the OpenRouter entry in the M13
     * registry, re-verified at M15.
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
                    url = destination.url(OpenRouterChatCompletions.PATH),
                    headers = authHeaders(credential),
                    body = OpenRouterChatCompletions.encodeRequest(request),
                )

            var terminal = false
            var usage: LlmUsage? = null
            var reportedModel: ModelId? = null
            try {
                transport.streamSse(httpRequest).collect { frame ->
                    if (terminal) return@collect
                    when (val parsed = OpenRouterChatCompletions.parse(frame)) {
                        is OpenRouterStreamFrame.Text -> {
                            emit(LlmStreamEvent.Delta(parsed.text))
                        }

                        // Reasoning is a separate channel, never assistant text (R-0066).
                        is OpenRouterStreamFrame.Reasoning -> {
                            Unit
                        }

                        // The accounting chunk carries usage/model but is not a
                        // completion; OpenRouter's [DONE] sentinel is.
                        is OpenRouterStreamFrame.Accounting -> {
                            usage = parsed.usage
                            parsed.model?.let { reportedModel = it }
                        }

                        OpenRouterStreamFrame.Done -> {
                            terminal = true
                            emit(
                                LlmStreamEvent.Completed(
                                    usage = usage,
                                    model = reportedModel,
                                    reasoning = null,
                                ),
                            )
                        }

                        is OpenRouterStreamFrame.Failed -> {
                            terminal = true
                            emit(LlmStreamEvent.Failed(error = parsed.error, partialText = ""))
                        }

                        OpenRouterStreamFrame.Ignored -> {
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
        /** Validates and returns the documented OpenRouter destination. */
        fun defaultDestination(
            provider: ProviderCapabilities =
                requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENROUTER)),
        ): ServerDestination =
            when (val validation = ProviderEndpointPolicy.destinationFor(provider)) {
                is EndpointValidation.Valid -> validation.destination
                is EndpointValidation.Invalid -> error("the OpenRouter destination is invalid: ${validation.error.code}")
            }
    }
}
