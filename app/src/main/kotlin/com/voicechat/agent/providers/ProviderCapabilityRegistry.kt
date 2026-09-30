package com.voicechat.agent.providers

import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel

/**
 * The static, verified provider capability catalog.
 *
 * Every entry summarizes the M00 provider capability matrix
 * (`docs/decisions.md` §4) and is the single source the settings UI (M22) and
 * the request validator use to show or hide connection options. It is
 * deliberately conservative: where the docs were silent at the M00 access date,
 * the field is at its "claimed nothing" value and the capability is listed in
 * [ProviderTransport.unverified] / [ProviderModelAccess.unverified] so it is not
 * presented as fact.
 *
 * No endpoint here is a secret: all fixed base URLs are the providers' public,
 * documented URLs, and the Hermes server is user-configured (no public default).
 */
class ProviderCapabilityRegistry(
    private val providers: List<ProviderCapabilities>,
) {
    private val byId: Map<ProviderId, ProviderCapabilities> = providers.associateBy { it.providerId }

    init {
        require(byId.size == providers.size) { "duplicate provider id in the registry" }
    }

    /** Every known provider, in registry order. */
    fun all(): List<ProviderCapabilities> = providers

    /** The capabilities for [providerId], or `null` when the provider is unknown. */
    fun capabilities(providerId: ProviderId): ProviderCapabilities? = byId[providerId]

    /** True when [providerId] is a provider this app knows and can configure. */
    fun isKnown(providerId: ProviderId): Boolean = byId.containsKey(providerId)

    companion object {
        /**
         * The default registry built from the M00 matrix. Re-verify a row against
         * current official docs before implementing the adapter that consumes it
         * (M14–M19).
         */
        fun verifiedDefaults(): ProviderCapabilityRegistry =
            ProviderCapabilityRegistry(
                listOf(
                    openAi(),
                    openRouter(),
                    openCodeGo(),
                    openCodeZen(),
                    deepSeek(),
                    hermes(),
                ),
            )

        private fun openAi(): ProviderCapabilities =
            ProviderCapabilities(
                providerId = KnownProviders.OPENAI,
                displayName = "OpenAI",
                auth =
                    ProviderAuth(
                        methods = setOf(AuthMethod.API_KEY),
                        credentialKind = CredentialKind.API_KEY,
                        validation = CredentialValidationSupport.LIST_MODELS,
                    ),
                transport =
                    ProviderTransport(
                        baseUrl = "https://api.openai.com/v1",
                        configurable = false,
                        expectedHost = "api.openai.com",
                        streaming = true,
                    ),
                models =
                    ProviderModelAccess(
                        discovery = ModelDiscovery.ENDPOINT,
                        reasoningLevels =
                            setOf(
                                ReasoningLevel.NONE,
                                ReasoningLevel.MINIMAL,
                                ReasoningLevel.LOW,
                                ReasoningLevel.MEDIUM,
                                ReasoningLevel.HIGH,
                                ReasoningLevel.XHIGH,
                                ReasoningLevel.MAX,
                            ),
                        usageReporting = true,
                    ),
            )

        private fun openRouter(): ProviderCapabilities =
            ProviderCapabilities(
                providerId = KnownProviders.OPENROUTER,
                displayName = "OpenRouter",
                auth =
                    ProviderAuth(
                        methods = setOf(AuthMethod.API_KEY, AuthMethod.OAUTH_PKCE),
                        credentialKind = CredentialKind.API_KEY,
                        validation = CredentialValidationSupport.LIST_MODELS,
                    ),
                transport =
                    ProviderTransport(
                        baseUrl = "https://openrouter.ai/api/v1",
                        configurable = false,
                        expectedHost = "openrouter.ai",
                        streaming = true,
                    ),
                models =
                    ProviderModelAccess(
                        discovery = ModelDiscovery.ENDPOINT,
                        reasoningLevels =
                            setOf(
                                ReasoningLevel.NONE,
                                ReasoningLevel.MINIMAL,
                                ReasoningLevel.LOW,
                                ReasoningLevel.MEDIUM,
                                ReasoningLevel.HIGH,
                                ReasoningLevel.XHIGH,
                                ReasoningLevel.MAX,
                            ),
                        usageReporting = true,
                    ),
            )

        private fun openCodeGo(): ProviderCapabilities =
            ProviderCapabilities(
                providerId = KnownProviders.OPENCODE_GO,
                displayName = "OpenCode Go",
                auth =
                    ProviderAuth(
                        methods = setOf(AuthMethod.API_KEY),
                        credentialKind = CredentialKind.API_KEY,
                        // Re-verified at M17 (2026-09-29): Go documents API-key auth,
                        // but `GET /models` answers without a key, so it cannot
                        // validate one. No minimal check is claimed.
                        validation = CredentialValidationSupport.NONE,
                    ),
                transport =
                    ProviderTransport(
                        baseUrl = "https://opencode.ai/zen/go/v1",
                        configurable = false,
                        expectedHost = "opencode.ai",
                        streaming = true,
                        // Go names three protocol families but does not spell out
                        // the SSE framing, so streaming stays marked (R-0014).
                        unverified = setOf(UnverifiedCapability.STREAMING),
                    ),
                models =
                    ProviderModelAccess(
                        discovery = ModelDiscovery.ENDPOINT,
                        // Go documents no reasoning control, and does not promise
                        // usage reporting; claim neither (R-0014, R-0072).
                        reasoningLevels = emptySet(),
                        usageReporting = false,
                        unverified = setOf(UnverifiedCapability.REASONING, UnverifiedCapability.USAGE),
                    ),
            )

        private fun openCodeZen(): ProviderCapabilities =
            ProviderCapabilities(
                providerId = KnownProviders.OPENCODE_ZEN,
                displayName = "OpenCode Zen",
                auth =
                    ProviderAuth(
                        methods = setOf(AuthMethod.API_KEY),
                        credentialKind = CredentialKind.API_KEY,
                        // Zen's streaming/reasoning semantics are unverified (R-0015);
                        // `/systemone` is a decision model, never a chat model, and is
                        // intentionally absent.
                        validation = CredentialValidationSupport.NONE,
                    ),
                transport =
                    ProviderTransport(
                        baseUrl = "https://opencode.ai/zen/v1",
                        configurable = false,
                        expectedHost = "opencode.ai",
                        streaming = true,
                        unverified = setOf(UnverifiedCapability.STREAMING),
                    ),
                models =
                    ProviderModelAccess(
                        discovery = ModelDiscovery.ENDPOINT,
                        reasoningLevels = emptySet(),
                        usageReporting = false,
                        unverified = setOf(UnverifiedCapability.REASONING, UnverifiedCapability.AUTH),
                    ),
            )

        private fun deepSeek(): ProviderCapabilities =
            ProviderCapabilities(
                providerId = KnownProviders.DEEPSEEK,
                displayName = "DeepSeek",
                auth =
                    ProviderAuth(
                        methods = setOf(AuthMethod.API_KEY),
                        credentialKind = CredentialKind.API_KEY,
                        validation = CredentialValidationSupport.LIST_MODELS,
                    ),
                transport =
                    ProviderTransport(
                        baseUrl = "https://api.deepseek.com",
                        configurable = false,
                        expectedHost = "api.deepseek.com",
                        streaming = true,
                    ),
                models =
                    ProviderModelAccess(
                        // Re-verified at M16 (2026-09-29): the Chat Completions
                        // `reasoning_effort` reference lists `none`/`low`/`high`/`max`
                        // and documents `minimal` (-> low) and `medium`/`xhigh` (-> high)
                        // as accepted; `none` disables thinking. Every level this app
                        // exposes is accepted, so the provider union claims them. The
                        // per-model `effort.supported_levels` refinement from `/models`
                        // stays an M13/M22 concern (see docs/deepseek-adapter.md, R-0123).
                        discovery = ModelDiscovery.ENDPOINT,
                        reasoningLevels =
                            setOf(
                                ReasoningLevel.NONE,
                                ReasoningLevel.MINIMAL,
                                ReasoningLevel.LOW,
                                ReasoningLevel.MEDIUM,
                                ReasoningLevel.HIGH,
                                ReasoningLevel.XHIGH,
                                ReasoningLevel.MAX,
                            ),
                        // Chat Completions reports usage in the last streamed chunk and
                        // on the non-streaming response.
                        usageReporting = true,
                    ),
            )

        private fun hermes(): ProviderCapabilities =
            ProviderCapabilities(
                providerId = KnownProviders.HERMES,
                displayName = "Hermes Agent API Server",
                auth =
                    ProviderAuth(
                        methods = setOf(AuthMethod.API_KEY),
                        // Bearer `API_SERVER_KEY`; the server is user/admin-configurable.
                        credentialKind = CredentialKind.API_KEY,
                        validation = CredentialValidationSupport.LIST_MODELS,
                    ),
                transport =
                    ProviderTransport(
                        baseUrl = null,
                        configurable = true,
                        expectedHost = null,
                        // Re-verified at M19 (2026-09-29): the API server documents
                        // SSE streaming for POST /v1/chat/completions, so streaming is
                        // no longer unverified (see docs/hermes-adapter.md). Reasoning
                        // stays unverified: the effort vocabulary is not enumerated.
                        streaming = true,
                        unverified = setOf(UnverifiedCapability.REASONING),
                    ),
                models =
                    ProviderModelAccess(
                        // `GET /v1/models` advertises the stable agent alias; richer
                        // picker metadata lives on the Hermes-native `/api/model/options`.
                        discovery = ModelDiscovery.ENDPOINT,
                        // The model_options.reasoning effort vocabulary is not
                        // documented, so no level is claimed and USAGE is unverified
                        // for the streamed path (only the non-streaming example shows
                        // a usage object).
                        reasoningLevels = emptySet(),
                        usageReporting = false,
                        unverified = setOf(UnverifiedCapability.REASONING, UnverifiedCapability.USAGE),
                    ),
                // Hermes is an agent runtime: tools run on the server host.
                toolExecutionOnServer = true,
            )
    }
}
