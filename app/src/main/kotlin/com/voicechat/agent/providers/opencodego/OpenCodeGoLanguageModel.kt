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
 * access date, in `docs/opencode-go-adapter.md`. Reasoning controls are not
 * documented for Go, so the adapter declares **no** reasoning level: a request
 * that asks for one is refused by [LlmRequestValidator] before a request is built
 * (only `ReasoningLevel.NONE` is accepted, as everywhere). Usage is echoed only
 * when a family's protocol actually reports it; no usage is promised.
 *
 * **Credential handling.** The adapter loads the M13 credential at request time
 * and places it only in the `Authorization` header, which [RemoteHttpRequest]
 * redacts on `toString`. No credential means `LLM_NOT_CONFIGURED`, never an
 * empty-key send. Go documents a client-identifying user agent and a stable
 * `x-opencode-session` conversation id; both are sent and neither carries a
 * credential.
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
     * The verified provider-level capability. Go's documented reasoning control
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

    private fun headers(credential: Credential): List<RemoteHttpHeader> =
        listOf(
            RemoteHttpHeader(name = "Authorization", value = "Bearer ${credential.secret}"),
            RemoteHttpHeader(name = "Content-Type", value = "application/json"),
            RemoteHttpHeader(name = "Accept", value = "text/event-stream"),
            RemoteHttpHeader(name = "User-Agent", value = CLIENT_USER_AGENT),
            RemoteHttpHeader(name = SESSION_HEADER, value = sessionId),
        )

    companion object {
        /**
         * The documented client identifier. Go asks a client to name itself with
         * its own user agent rather than a generic SDK/HTTP-library name.
         */
        const val CLIENT_USER_AGENT: String = "voicechat-agent/0.1"

        /** The documented stable per-conversation session header. */
        const val SESSION_HEADER: String = "x-opencode-session"

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
