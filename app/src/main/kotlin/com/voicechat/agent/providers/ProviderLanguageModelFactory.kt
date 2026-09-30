package com.voicechat.agent.providers

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.credentials.CredentialStore
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.providers.deepseek.DeepSeekLanguageModel
import com.voicechat.agent.providers.hermes.HermesLanguageModel
import com.voicechat.agent.providers.openai.OpenAiLanguageModel
import com.voicechat.agent.providers.opencodego.OpenCodeGoLanguageModel
import com.voicechat.agent.providers.opencodezen.OpenCodeZenLanguageModel
import com.voicechat.agent.providers.openrouter.OpenRouterLanguageModel
import com.voicechat.agent.remote.RemoteTransport

/**
 * Builds the active [LanguageModel] for a persisted M22 selection (M23).
 *
 * This is the single place the conversation turn path turns a
 * `ProviderModelSelection` + the M13 [CredentialStore] into a real adapter, so
 * the UI never hard-codes a provider (R-0103) and adding a provider is a registry
 * change, not a UI change. Returning `null` means "this app has no adapter for
 * that provider" and the caller keeps the honest `LLM_NOT_CONFIGURED` state
 * instead of fabricating a reply (R-0012).
 *
 * [sessionHint] is a conversation-scoped, non-secret routing hint (the
 * conversation id). A provider that documents a session header uses it so
 * prompt-caching/routing stays conversation-scoped (R-0132); a provider that does
 * not simply ignores it. [configuredServerUrl] is the validated user-entered
 * address for a configurable provider (Hermes), and is ignored by a fixed
 * provider.
 */
fun interface ProviderLanguageModelFactory {
    fun create(
        selection: ProviderModelSelection,
        sessionHint: String?,
        configuredServerUrl: String?,
    ): LanguageModel?
}

/**
 * The registry-driven [ProviderLanguageModelFactory] over the M13 registry, the
 * M13 credential store, and the M14 remote transport.
 *
 * It is deliberately uniform: every known provider's adapter has the same
 * `(credentials, transport, provider, destination)` shape, so the factory
 * resolves the registry row and the validated destination once and dispatches on
 * the provider identity. No network call happens here — an adapter loads its
 * credential and opens a stream only when a turn actually runs.
 */
class RegisteredProviderLanguageModelFactory(
    private val registry: ProviderCapabilityRegistry,
    private val credentials: CredentialStore,
    private val transport: RemoteTransport,
) : ProviderLanguageModelFactory {
    override fun create(
        selection: ProviderModelSelection,
        sessionHint: String?,
        configuredServerUrl: String?,
    ): LanguageModel? {
        val provider = registry.capabilities(selection.providerId) ?: return null
        val destination =
            when (val validation = ProviderEndpointPolicy.destinationFor(provider, configuredServerUrl)) {
                is EndpointValidation.Valid -> validation.destination

                // A configurable provider (Hermes) with no validated address has no
                // destination to send to; the caller keeps the honest not-configured
                // state instead of inventing an endpoint.
                is EndpointValidation.Invalid -> return null
            }
        return when (selection.providerId) {
            KnownProviders.OPENCODE_GO -> {
                OpenCodeGoLanguageModel(
                    credentialStore = credentials,
                    transport = transport,
                    provider = provider,
                    destination = destination,
                    sessionId = sessionHint?.let(::openCodeGoSessionId) ?: OpenCodeGoLanguageModel.defaultSessionId(),
                )
            }

            KnownProviders.OPENCODE_ZEN -> {
                OpenCodeZenLanguageModel(
                    credentialStore = credentials,
                    transport = transport,
                    provider = provider,
                    destination = destination,
                )
            }

            KnownProviders.OPENAI -> {
                OpenAiLanguageModel(
                    credentialStore = credentials,
                    transport = transport,
                    provider = provider,
                    destination = destination,
                )
            }

            KnownProviders.OPENROUTER -> {
                OpenRouterLanguageModel(
                    credentialStore = credentials,
                    transport = transport,
                    provider = provider,
                    destination = destination,
                )
            }

            KnownProviders.DEEPSEEK -> {
                DeepSeekLanguageModel(
                    credentialStore = credentials,
                    transport = transport,
                    provider = provider,
                    destination = destination,
                )
            }

            KnownProviders.HERMES -> {
                HermesLanguageModel(
                    credentialStore = credentials,
                    transport = transport,
                    destination = destination,
                    provider = provider,
                    // The conversation id is the natural stable session key; it is a
                    // random app-local id and carries no user content.
                    sessionId = sessionHint,
                )
            }

            else -> {
                null
            }
        }
    }

    private companion object {
        /**
         * A stable, non-secret session id for OpenCode Go derived from the
         * conversation id, so Go's `x-opencode-session` routing hint is
         * conversation-scoped (R-0132) rather than per-adapter-instance.
         */
        fun openCodeGoSessionId(conversationId: String): String = "voicechat-$conversationId"
    }
}
