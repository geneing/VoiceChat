package com.voicechat.agent.providers.opencodego

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
import java.util.UUID

/**
 * The OpenCode Go provider adapter (M17): a distinct integration behind the M12
 * [LanguageModel] contract, sharing only the M14 [RemoteTransport].
 *
 * **Go is not OpenAI-compatible.** Its official model table places each model on
 * one of three protocols, so the adapter classifies the selected model's family
 * ([OpenCodeGoModels.familyFor]) and builds that family's request and stream
 * mapping. An id the table does not place is refused with a typed
 * `LLM_INVALID_REQUEST` before anything is sent, rather than being forced through
 * a guessed protocol.
 *
 * **Verified facts, sources, and the unverified list** are recorded, with the
 * access date, in `docs/opencode-go-adapter.md`. The live gateway was measured on
 * 2026-10-16/17 (`docs/voice-llm-selection.md`): reasoning control is not
 * documented but **is** accepted and honoured per model, so the adapter maps a
 * requested level through [OpenCodeGoReasoning] and refuses a level the selected
 * model was not measured to take, before anything is sent (R-0135). Usage is
 * echoed when a family's protocol reports it, and all three families do.
 *
 * **Credential handling.** The adapter loads the M13 credential at request time
 * and places it only in the request headers, which [RemoteHttpRequest] redacts on
 * `toString`. No credential means `LLM_NOT_CONFIGURED`, never an empty-key send.
 * The two documented client headers are always sent and neither carries a
 * credential: a client-identifying user agent, and a stable `x-opencode-session`
 * conversation id the gateway **requires** (HTTP 400 `MissingSessionID` without
 * it). Authentication is per family: Chat Completions and Responses take
 * `Authorization: Bearer`, while Messages takes Anthropic's `x-api-key` plus
 * `anthropic-version`, because the gateway rejects Bearer there.
 */
class OpenCodeGoLanguageModel(
    private val credentialStore: CredentialStore,
    private val transport: RemoteTransport,
    private val provider: ProviderCapabilities =
        requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENCODE_GO)) {
            "the OpenCode Go provider entry is missing from the registry"
        },
    private val destination: ServerDestination = defaultDestination(provider),
    private val sessionId: String = defaultSessionId(),
) : LanguageModel {
    init {
        require(sessionId.isNotBlank()) { "an OpenCode Go session id must not be blank" }
    }

    override val providerId: ProviderId
        get() = provider.providerId

    /**
     * The provider-level capability union: streaming, usage reporting, and every
     * reasoning level any measured Go model accepts. A specific model's own set is
     * narrower and is what [OpenCodeGoReasoning.supports] enforces per request
     * (the M22 UI intersects the two through `LlmCapabilityReconciler`).
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

            // Per-model reasoning support: the provider-level union above cannot
            // express that one Go model rejects the field and another accepts it.
            val reasoning = request.reasoning
            if (reasoning != null && !OpenCodeGoReasoning.supports(request.model.modelId, reasoning)) {
                emit(
                    LlmStreamEvent.Failed(
                        error =
                            VoiceAgentError(
                                ErrorCode.LLM_INVALID_REQUEST,
                                "the selected model has no verified reasoning control for that level",
                            ),
                        partialText = "",
                    ),
                )
                return@flow
            }

            val family = OpenCodeGoModels.familyFor(request.model.modelId)
            if (family == null) {
                emit(
                    LlmStreamEvent.Failed(
                        error =
                            VoiceAgentError(
                                ErrorCode.LLM_INVALID_REQUEST,
                                "the selected model's OpenCode Go protocol family is not verified",
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
                    headers = headers(family, credential),
                    body = encode(family, request),
                )

            var terminal = false
            var reportedModel: ModelId? = null
            var reportedUsage: LlmUsage? = null

            try {
                transport.streamSse(httpRequest).collect { frame ->
                    if (terminal) return@collect
                    when (val parsed = parse(family, frame)) {
                        is OpenCodeGoStreamFrame.Delta -> {
                            parsed.model?.let { reportedModel = it }
                            emit(LlmStreamEvent.Delta(parsed.text))
                        }

                        is OpenCodeGoStreamFrame.Reported -> {
                            parsed.model?.let { reportedModel = it }
                            reportedUsage = mergeUsage(reportedUsage, parsed.usage)
                        }

                        is OpenCodeGoStreamFrame.Completed -> {
                            terminal = true
                            emit(
                                LlmStreamEvent.Completed(
                                    usage = parsed.usage ?: reportedUsage,
                                    model = parsed.model ?: reportedModel,
                                ),
                            )
                        }

                        is OpenCodeGoStreamFrame.Failed -> {
                            terminal = true
                            emit(LlmStreamEvent.Failed(error = parsed.error, partialText = ""))
                        }

                        OpenCodeGoStreamFrame.Ignored -> {
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
        family: OpenCodeGoFamily,
        request: LlmRequest,
    ): String =
        when (family) {
            OpenCodeGoFamily.CHAT_COMPLETIONS -> OpenCodeGoChatCompletions.encodeRequest(request)
            OpenCodeGoFamily.RESPONSES -> OpenCodeGoResponses.encodeRequest(request)
            OpenCodeGoFamily.MESSAGES -> OpenCodeGoMessages.encodeRequest(request)
        }

    private fun parse(
        family: OpenCodeGoFamily,
        frame: SseFrame,
    ): OpenCodeGoStreamFrame =
        when (family) {
            OpenCodeGoFamily.CHAT_COMPLETIONS -> OpenCodeGoChatCompletions.parse(frame)
            OpenCodeGoFamily.RESPONSES -> OpenCodeGoResponses.parse(frame)
            OpenCodeGoFamily.MESSAGES -> OpenCodeGoMessages.parse(frame)
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
     * The request headers for one protocol [family].
     *
     * Go's three families do not share one auth scheme. Chat Completions and
     * Responses authenticate with `Authorization: Bearer`; the Messages family is
     * Anthropic-shaped and requires `x-api-key` (plus `anthropic-version`), and
     * answers HTTP 401 `"Go requires a credential"` when sent a Bearer token.
     * `RemoteHttpHeader` values are redacted by `RemoteHttpRequest.toString`, so
     * the key never reaches a log or a trace.
     */
    private fun headers(
        family: OpenCodeGoFamily,
        credential: Credential,
    ): List<RemoteHttpHeader> =
        buildList {
            if (family == OpenCodeGoFamily.MESSAGES) {
                add(RemoteHttpHeader(name = "x-api-key", value = credential.secret))
                add(RemoteHttpHeader(name = ANTHROPIC_VERSION_HEADER, value = ANTHROPIC_VERSION))
            } else {
                add(RemoteHttpHeader(name = "Authorization", value = "Bearer ${credential.secret}"))
            }
            add(RemoteHttpHeader(name = "Content-Type", value = "application/json"))
            add(RemoteHttpHeader(name = "Accept", value = "text/event-stream"))
            add(RemoteHttpHeader(name = "User-Agent", value = CLIENT_USER_AGENT))
            add(RemoteHttpHeader(name = SESSION_HEADER, value = sessionId))
        }

    companion object {
        /**
         * The documented client identifier. Go asks a client to name itself with
         * its own user agent rather than a generic SDK/HTTP-library name.
         */
        const val CLIENT_USER_AGENT: String = "voicechat-agent/0.1"

        /** The documented stable per-conversation session header. */
        const val SESSION_HEADER: String = "x-opencode-session"

        /** The Anthropic API-version header the Messages family requires. */
        const val ANTHROPIC_VERSION_HEADER: String = "anthropic-version"

        /** The Anthropic API version Go's Messages family was measured to accept. */
        const val ANTHROPIC_VERSION: String = "2023-06-01"

        /** A stable id for one adapter instance when the caller does not supply one. */
        fun defaultSessionId(): String = "voicechat-${UUID.randomUUID()}"

        /** Validates and returns the documented OpenCode Go destination. */
        fun defaultDestination(
            provider: ProviderCapabilities =
                requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENCODE_GO)),
        ): ServerDestination =
            when (val validation = ProviderEndpointPolicy.destinationFor(provider)) {
                is EndpointValidation.Valid -> validation.destination
                is EndpointValidation.Invalid -> error("the OpenCode Go destination is invalid: ${validation.error.code}")
            }
    }
}
