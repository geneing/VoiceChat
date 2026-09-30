package com.voicechat.agent.providers.hermes

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
import com.voicechat.agent.providers.EndpointSource
import com.voicechat.agent.providers.EndpointValidation
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ServerDestination
import com.voicechat.agent.remote.RemoteHttpHeader
import com.voicechat.agent.remote.RemoteHttpRequest
import com.voicechat.agent.remote.RemoteTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The Hermes Agent API Server provider adapter (M19): the last remote provider
 * behind the M12 [LanguageModel] contract, sharing only the M14 [RemoteTransport].
 *
 * **Hermes is an agent runtime, not a pure proxy (R-0019).** A configured Hermes
 * server executes tools (`pwd`, file, browser, MCP) on its own host; the adapter
 * therefore requires an explicit, user/admin-supplied [destination] (there is no
 * default endpoint) and exposes [destinationDisclosure] so the UI can show where
 * text will go before it is sent. Non-local hosts require TLS, enforced by
 * [HermesServerAddress] via the M13
 * [com.voicechat.agent.providers.ServerDestinationValidator] rules.
 *
 * **Protocol.** Hermes is OpenAI Chat Completions compatible: the adapter POSTs to
 * `{base}/chat/completions`, emits a `Delta` per `choices[].delta.content`,
 * excludes the DeepSeek-style `choices[].delta.reasoning_content` channel from
 * assistant text (R-0066), ignores the custom `hermes.tool.progress` named event,
 * and completes on the documented `data: [DONE]` sentinel (with a non-null
 * `finish_reason` as the fallback terminal signal). `model`/`usage` reported by
 * the server are echoed on [LlmStreamEvent.Completed] so a mismatch is visible
 * rather than silently accepted (R-0017). Verified facts and sources are in
 * `docs/hermes-adapter.md` (accessed 2026-09-29).
 *
 * **Credential handling.** The adapter loads the M13 credential at request time
 * and places it only in the `Authorization` header, which [RemoteHttpRequest]
 * redacts on `toString`. No credential means `LLM_NOT_CONFIGURED`, never an
 * empty-key send.
 *
 * **Failures.** A server status, a transport failure, a malformed frame, a
 * mid-stream `error` object, or a terminal-less stream becomes a typed
 * [LlmStreamEvent.Failed]. Cancellation propagates as `CancellationException`.
 * Nothing is reported complete unless the stream ended with `[DONE]` (or a
 * `finish_reason` that resolves to a whole answer).
 */
class HermesLanguageModel(
    private val credentialStore: CredentialStore,
    private val transport: RemoteTransport,
    private val destination: ServerDestination,
    private val provider: ProviderCapabilities =
        requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.HERMES)) {
            "the Hermes provider entry is missing from the registry"
        },
    private val sessionId: String? = null,
) : LanguageModel {
    init {
        require(sessionId == null || sessionId.isNotBlank()) { "a Hermes session id must not be blank" }
    }

    override val providerId: ProviderId
        get() = provider.providerId

    /**
     * The verified provider-level capability. Hermes streams (verified), but no
     * usage reporting is claimed for the stream and the reasoning-effort
     * vocabulary is not documented, so neither is claimed.
     */
    override val capabilities: LlmCapabilities
        get() = provider.toLlmCapabilities()

    /**
     * The user-visible destination, shown before text leaves the device. The UI
     * surfaces this (with the server-tools notice) before a request is sent.
     */
    val destinationDisclosure: String
        get() = destination.disclosure()

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
                    url = destination.url(HermesChatCompletions.PATH),
                    headers = headers(credential),
                    body = HermesChatCompletions.encodeRequest(request),
                )

            var terminal = false
            var sawDone = false
            var finishReason: String? = null
            var usage: LlmUsage? = null
            var reportedModel: ModelId? = null

            try {
                transport.streamSse(httpRequest).collect { frame ->
                    if (terminal || sawDone) return@collect
                    when (val parsed = HermesChatCompletions.parse(frame)) {
                        is HermesStreamFrame.Delta -> {
                            emit(LlmStreamEvent.Delta(parsed.text))
                        }

                        // Reasoning is a separate channel, never assistant text (R-0066).
                        is HermesStreamFrame.Reasoning -> {
                            Unit
                        }

                        // The serving model, usage, and finish_reason may arrive on a
                        // frame that is not itself terminal; accumulate and resolve later.
                        is HermesStreamFrame.Reported -> {
                            parsed.model?.let { reportedModel = it }
                            parsed.usage?.let { usage = it }
                            parsed.finishReason?.let { finishReason = it }
                        }

                        HermesStreamFrame.Done -> {
                            sawDone = true
                        }

                        is HermesStreamFrame.Failed -> {
                            terminal = true
                            emit(LlmStreamEvent.Failed(error = parsed.error, partialText = ""))
                        }

                        HermesStreamFrame.Ignored -> {
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

            if (terminal) return@flow
            if (sawDone || finishReason != null) {
                when (val completion = HermesChatCompletions.outcomeFor(finishReason)) {
                    HermesCompletion.Completed -> {
                        emit(LlmStreamEvent.Completed(usage = usage, model = reportedModel))
                    }

                    is HermesCompletion.Failed -> {
                        emit(LlmStreamEvent.Failed(error = completion.error, partialText = ""))
                    }
                }
            } else {
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

    private fun headers(credential: Credential): List<RemoteHttpHeader> =
        buildList {
            add(RemoteHttpHeader(name = "Authorization", value = "Bearer ${credential.secret}"))
            add(RemoteHttpHeader(name = "Content-Type", value = "application/json"))
            add(RemoteHttpHeader(name = "Accept", value = "text/event-stream"))
            sessionId?.let { add(RemoteHttpHeader(name = SESSION_HEADER, value = it)) }
        }

    companion object {
        /** The documented transcript-scoped session header (optional). */
        const val SESSION_HEADER: String = "X-Hermes-Session-Id"

        /**
         * Validates a user/admin-supplied Hermes server address with the M13 rules
         * (TLS required for non-local hosts, QR never trusted).
         */
        fun validateDestination(
            raw: String,
            source: EndpointSource = EndpointSource.USER_ENTERED,
        ): EndpointValidation = HermesServerAddress.validate(raw, source)
    }
}
