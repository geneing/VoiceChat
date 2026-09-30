package com.voicechat.agent.providers

import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel

/**
 * How a provider authenticates, kept **separate** from transport and model
 * access so the app never couples "who you are" to "where the request goes".
 */
data class ProviderAuth(
    val methods: Set<AuthMethod>,
    val credentialKind: CredentialKind,
    val validation: CredentialValidationSupport,
) {
    init {
        require(methods.isNotEmpty()) { "a provider must document at least one auth method" }
    }
}

/**
 * Where and how a request travels, independent of authentication.
 *
 * A provider with a documented public base URL is not configurable and pins its
 * [expectedHost]. A provider whose server is user/admin-configured (Hermes) has
 * `baseUrl == null`, [configurable] true, and a `null` [expectedHost]; its
 * destination is validated by [ServerDestinationValidator] with TLS required for
 * non-local hosts.
 */
data class ProviderTransport(
    val baseUrl: String?,
    val configurable: Boolean,
    val expectedHost: String?,
    val streaming: Boolean,
    val unverified: Set<UnverifiedCapability> = emptySet(),
) {
    init {
        if (configurable) {
            require(baseUrl == null && expectedHost == null) {
                "a configurable transport has no default base URL or pinned host"
            }
        } else {
            require(baseUrl != null && expectedHost != null) {
                "a fixed transport needs a documented base URL and host"
            }
        }
    }
}

/** Model discovery and reasoning/usage surfaces, independent of auth and host. */
data class ProviderModelAccess(
    val discovery: ModelDiscovery,
    val reasoningLevels: Set<ReasoningLevel>,
    val usageReporting: Boolean,
    val unverified: Set<UnverifiedCapability> = emptySet(),
)

/**
 * The verified, app-facing capabilities of one provider.
 *
 * This is the M13 provider capability registry entry. It is deliberately split
 * into [auth], [transport], and [models] so the app can show connection options
 * (auth) without conflating them with the destination or the model catalog. It
 * reconciles with the M12 seam through [toLlmCapabilities]; the per-*model*
 * refinement (which reasoning levels a specific model exposes) comes from the
 * `/models` catalog via [LlmCapabilityReconciler], not from this entry.
 */
data class ProviderCapabilities(
    val providerId: ProviderId,
    val displayName: String,
    val auth: ProviderAuth,
    val transport: ProviderTransport,
    val models: ProviderModelAccess,
    /** True for an agent runtime that executes tools on the server host (Hermes). */
    val toolExecutionOnServer: Boolean = false,
) {
    init {
        require(displayName.isNotBlank()) { "a provider needs a display name" }
    }

    /** Auth methods the app may show for this provider; never a QR method. */
    val availableAuthMethods: Set<AuthMethod> get() = auth.methods

    /** True when a credential must be stored before a request can be attempted. */
    val requiresCredential: Boolean get() = auth.methods.any { it == AuthMethod.API_KEY || it == AuthMethod.OAUTH_PKCE }

    /**
     * The provider-level view of the M12 [LlmCapabilities] seam. Per-model
     * refinement is applied by [LlmCapabilityReconciler] before a request is
     * validated.
     */
    fun toLlmCapabilities(): LlmCapabilities =
        LlmCapabilities(
            streaming = transport.streaming,
            usageReporting = models.usageReporting,
            reasoningLevels = models.reasoningLevels,
        )
}

/** Stable provider identities used by the registry and tests. */
object KnownProviders {
    val OPENAI = ProviderId("openai")
    val OPENROUTER = ProviderId("openrouter")
    val OPENCODE_GO = ProviderId("opencode-go")
    val OPENCODE_ZEN = ProviderId("opencode-zen")
    val DEEPSEEK = ProviderId("deepseek")
    val HERMES = ProviderId("hermes")
}
