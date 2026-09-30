package com.voicechat.agent.providers.hermes

import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.providers.EndpointSource
import com.voicechat.agent.providers.EndpointValidation
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderEndpointPolicy
import com.voicechat.agent.providers.ServerDestination

/**
 * A validated, user/admin-configured Hermes Agent API Server destination.
 *
 * This is the M19 config model the settings UI (M22) surfaces: it keeps the raw
 * address the user entered and the validated [destination], and exposes the
 * [disclosure] the user sees **before** any text is sent. Hermes is an agent
 * runtime, so [toolsRunOnServerHost] is true and [notice] records that the
 * configured server executes tools (`pwd`, file, browser, MCP) on its own host
 * (risk R-0019).
 *
 * There is deliberately **no default address**: Hermes is user/admin
 * configuration, not a hard-coded public endpoint (`docs/llm-providers.md`).
 */
data class HermesServerConfig(
    val address: String,
    val destination: ServerDestination,
) {
    /** The user-visible destination, shown before text leaves the device. */
    val disclosure: String get() = destination.disclosure()

    /** True because Hermes executes tools on the server host, not as a pure proxy. */
    val toolsRunOnServerHost: Boolean get() = true

    /** The disclosure the settings UI shows alongside [disclosure] for Hermes. */
    val notice: String get() = TOOLS_ON_SERVER_HOST_NOTICE

    companion object {
        /**
         * The user-facing disclosure that the configured server executes tools on
         * its own host.
         */
        const val TOOLS_ON_SERVER_HOST_NOTICE: String =
            "This Hermes server runs agent tools (terminal, files, browser, MCP) on the server host."
    }
}

/** Result of validating a Hermes server address. */
sealed interface HermesServerConfigResult {
    /** The address is safe to use; [config] carries the destination and disclosure. */
    data class Valid(
        val config: HermesServerConfig,
    ) : HermesServerConfigResult

    /** The address was refused; [error] is a typed, safe reason. */
    data class Invalid(
        val error: VoiceAgentError,
    ) : HermesServerConfigResult
}

/**
 * Validates a configurable Hermes server address by reusing the M13
 * [`ServerDestinationValidator`][com.voicechat.agent.providers.ServerDestinationValidator]
 * rules through [ProviderEndpointPolicy]:
 *
 * - only `http`/`https`;
 * - no credentials embedded in the URL;
 * - **TLS is required for any non-local host** (plain `http` is accepted only for
 *   a loopback address);
 * - a QR-sourced value is never accepted as an endpoint.
 *
 * No public endpoint is hard-coded; the server address is always user/admin
 * configuration.
 */
object HermesServerAddress {
    /** The registry entry for Hermes (configurable transport, API-key auth). */
    fun provider(): ProviderCapabilities =
        requireNotNull(ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.HERMES)) {
            "the Hermes provider entry is missing from the registry"
        }

    /** Validates [raw] and returns the parsed destination or a typed reason. */
    fun validate(
        raw: String,
        source: EndpointSource = EndpointSource.USER_ENTERED,
    ): EndpointValidation = ProviderEndpointPolicy.validateForProvider(provider(), raw, source)

    /**
     * Validates [raw] and returns the config model (with the disclosure), or a
     * typed reason. This is the single call the settings UI/connection flow uses;
     * it never returns a destination the user cannot identify first.
     */
    fun config(
        raw: String,
        source: EndpointSource = EndpointSource.USER_ENTERED,
    ): HermesServerConfigResult =
        when (val validation = validate(raw, source)) {
            is EndpointValidation.Valid -> {
                HermesServerConfigResult.Valid(
                    HermesServerConfig(address = raw.trim(), destination = validation.destination),
                )
            }

            is EndpointValidation.Invalid -> {
                HermesServerConfigResult.Invalid(validation.error)
            }
        }

    /**
     * The disclosure for [raw], or `null` when the address is invalid. The UI
     * shows this before sending text.
     */
    fun disclosure(
        raw: String,
        source: EndpointSource = EndpointSource.USER_ENTERED,
    ): String? = (config(raw, source) as? HermesServerConfigResult.Valid)?.config?.disclosure
}
