package com.voicechat.agent.providers.opencodezen

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
import com.voicechat.agent.remote.SseFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * The OpenCode Zen provider adapter (M18): a distinct integration behind the M12
 * [LanguageModel] contract, sharing only the M14 [RemoteTransport].
 *
 * **Zen is not OpenAI-compatible, and is not OpenCode Go.** Its own endpoint
 * table places each model on one of three chat protocols, so the adapter
 * classifies the selected model's family ([OpenCodeZenModels.familyFor]) and
 * builds that family's request and stream mapping. An id the table does not place
 * as a chat completion is refused with a typed `LLM_INVALID_REQUEST` before
 * anything is sent. That includes the `/systemone` Jev structured-decision ids,
 * which are **never** surfaced as a chat model, and the Google-family Gemini ids,
 * whose request/stream shape the Zen page does not print (R-0142).
 *
 * **Verified facts, sources, and the unverified list** are recorded, with the
 * access date, in `docs/opencode-zen-adapter.md`. Reasoning controls are not
 * documented for Zen, so the adapter declares **no** reasoning level: a request
 * that asks for one is refused by [LlmRequestValidator] before a request is built
 * (only `ReasoningLevel.NONE` is accepted, as everywhere). Usage is echoed only
 * when a family's protocol actually reports it; no usage is promised.
 *
 * **Credential handling.** The adapter loads the M13 credential at request time
 * and places it only in the `Authorization` header, which [RemoteHttpRequest]
 * redacts on `toString`. No credential means `LLM_NOT_CONFIGURED`, never an
 * empty-key send. Zen documents no client-session header; unlike Go, none is
 * sent.
 */
class OpenCodeZenLanguageModel(
    private val credentialStore: CredentialStore,
    private val transport: RemoteTransport,
    private val provider: ProviderCapabilities =
        requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENCODE_ZEN)) {
            "the OpenCode Zen provider entry is missing from the registry"
        },
    private val destination: ServerDestination = defaultDestination(provider),
) : LanguageModel {
    override val providerId: ProviderId
        get() = provider.providerId

    /**
     * The verified provider-level capability. Zen's documented reasoning control
     * is empty and its usage reporting is unverified, so neither is claimed.
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

            val family = OpenCodeZenModels.familyFor(request.model.modelId)
            if (family == null) {
                emit(
                    LlmStreamEvent.Failed(
                        error =
                            VoiceAgentError(
                                ErrorCode.LLM_INVALID_REQUEST,
                                "the selected model is not a verified OpenCode Zen chat completion model",
                            ),
                        partialText = "",
                    ),
                )
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
                    url = destination.url(family.path),
                    headers = headers(credential),
                    body = encode(family, request),
                )

            var terminal = false
            var reportedModel: ModelId? = null
            var reportedUsage: LlmUsage? = null

            try {
                transport.streamSse(httpRequest).collect { frame ->
                    if (terminal) return@collect
                    when (val parsed = parse(family, frame)) {
                        is OpenCodeZenStreamFrame.Delta -> {
                            parsed.model?.let { reportedModel = it }
                            emit(LlmStreamEvent.Delta(parsed.text))
                        }

                        is OpenCodeZenStreamFrame.Reported -> {
                            parsed.model?.let { reportedModel = it }
                            reportedUsage = mergeUsage(reportedUsage, parsed.usage)
                        }

                        is OpenCodeZenStreamFrame.Completed -> {
                            terminal = true
                            emit(
                                LlmStreamEvent.Completed(
                                    usage = parsed.usage ?: reportedUsage,
                                    model = parsed.model ?: reportedModel,
                                ),
                            )
                        }

                        is OpenCodeZenStreamFrame.Failed -> {
                            terminal = true
                            emit(LlmStreamEvent.Failed(error = parsed.error, partialText = ""))
                        }

                        OpenCodeZenStreamFrame.Ignored -> {
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

    private fun encode(
        family: OpenCodeZenFamily,
        request: LlmRequest,
    ): String =
        when (family) {
            OpenCodeZenFamily.CHAT_COMPLETIONS -> OpenCodeZenChatCompletions.encodeRequest(request)
            OpenCodeZenFamily.RESPONSES -> OpenCodeZenResponses.encodeRequest(request)
            OpenCodeZenFamily.MESSAGES -> OpenCodeZenMessages.encodeRequest(request)
        }

    private fun parse(
        family: OpenCodeZenFamily,
        frame: SseFrame,
    ): OpenCodeZenStreamFrame =
        when (family) {
            OpenCodeZenFamily.CHAT_COMPLETIONS -> OpenCodeZenChatCompletions.parse(frame)
            OpenCodeZenFamily.RESPONSES -> OpenCodeZenResponses.parse(frame)
            OpenCodeZenFamily.MESSAGES -> OpenCodeZenMessages.parse(frame)
        }

    /**
     * Merges two partial usage observations field by field, because a family may
     * report input tokens on one frame and output tokens on another (Anthropic
     * Messages does). A field is never overwritten by `null` and no missing count
     * is invented as `0`.
     */
    private fun mergeUsage(
        accumulated: LlmUsage?,
        next: LlmUsage?,
    ): LlmUsage? {
        if (next == null) return accumulated
        if (accumulated == null) return next
        return LlmUsage(
            promptTokens = next.promptTokens ?: accumulated.promptTokens,
            completionTokens = next.completionTokens ?: accumulated.completionTokens,
            totalTokens = next.totalTokens ?: accumulated.totalTokens,
            details = accumulated.details + next.details,
        )
    }

    /**
     * Zen documents API-key bearer auth and no client-session header (unlike
     * OpenCode Go's `x-opencode-session`), so only the documented headers are
     * sent.
     */
    private fun headers(credential: Credential): List<RemoteHttpHeader> =
        listOf(
            RemoteHttpHeader(name = "Authorization", value = "Bearer ${credential.secret}"),
            RemoteHttpHeader(name = "Content-Type", value = "application/json"),
            RemoteHttpHeader(name = "Accept", value = "text/event-stream"),
        )

    companion object {
        /** Validates and returns the documented OpenCode Zen destination. */
        fun defaultDestination(
            provider: ProviderCapabilities =
                requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENCODE_ZEN)),
        ): ServerDestination =
            when (val validation = ProviderEndpointPolicy.destinationFor(provider)) {
                is EndpointValidation.Valid -> validation.destination
                is EndpointValidation.Invalid -> error("the OpenCode Zen destination is invalid: ${validation.error.code}")
            }
    }
}
