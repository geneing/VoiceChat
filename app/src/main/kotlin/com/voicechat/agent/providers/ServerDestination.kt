package com.voicechat.agent.providers

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import java.net.URI
import java.net.URISyntaxException

/**
 * How a destination value reached the app.
 *
 * [QR_PAYLOAD] is explicit so the validator can refuse it: a scanned QR is
 * untrusted input, not a configured endpoint (`docs/privacy-and-security.md`).
 */
enum class EndpointSource {
    /** The provider's own documented base URL, chosen by the app. */
    PROVIDER_DEFAULT,

    /** Typed/confirmed by the user (or the admin who deployed the server). */
    USER_ENTERED,

    /** Scanned from a QR code; never trusted as an endpoint. */
    QR_PAYLOAD,
}

/**
 * A validated server destination, safe to disclose before a request is sent.
 *
 * The host is normalized to lowercase. [isLoopback] is true only for a loopback
 * address (`localhost`, `127.0.0.0/8`, `::1`), where plain `http` may be allowed;
 * every other host requires TLS.
 */
data class ServerDestination(
    val scheme: String,
    val host: String,
    val port: Int,
    val basePath: String = "",
) {
    /** True for a loopback-only destination. */
    val isLoopback: Boolean
        get() =
            host == "localhost" ||
                host == "::1" ||
                (host.startsWith("127.") && host.removePrefix("127.").split('.').size == 3)

    /**
     * The user-visible destination, shown before text leaves the device. It
     * always names the scheme and host so the user can identify it; the default
     * port is elided.
     */
    fun disclosure(): String {
        val defaultPort = if (scheme == "https") 443 else 80
        val builder = StringBuilder().append(scheme).append("://").append(host)
        if (port != defaultPort) builder.append(':').append(port)
        if (basePath.isNotEmpty()) builder.append(basePath)
        return builder.toString()
    }

    /**
     * The absolute URL for [path] on this destination, used by a provider adapter
     * to reach one documented endpoint (for example `/responses`) without
     * re-deriving the base URL. The path is joined with a single `/`.
     */
    fun url(path: String = ""): String {
        val defaultPort = if (scheme == "https") 443 else 80
        val builder = StringBuilder().append(scheme).append("://").append(host)
        if (port != defaultPort) builder.append(':').append(port)
        builder.append(basePath.trimEnd('/'))
        val suffix = path.trimStart('/')
        if (suffix.isNotEmpty()) builder.append('/').append(suffix)
        return builder.toString()
    }
}

/** Result of validating a destination; invalid carries a typed, safe error. */
sealed interface EndpointValidation {
    data class Valid(
        val destination: ServerDestination,
    ) : EndpointValidation

    data class Invalid(
        val error: VoiceAgentError,
    ) : EndpointValidation
}

/**
 * Validates a configurable server destination (used for the Hermes server).
 *
 * Rules (`docs/decisions.md` §4, `docs/llm-providers.md`):
 * - only `http`/`https` are accepted;
 * - credentials in the URL (`user:pass@host`) are refused;
 * - **TLS is required for any non-local host**; `http` is accepted only for a
 *   loopback address;
 * - a QR-sourced value is never accepted as an endpoint.
 */
object ServerDestinationValidator {
    /** Validates [raw] and returns the parsed destination or a typed reason. */
    fun validate(
        raw: String,
        source: EndpointSource = EndpointSource.USER_ENTERED,
        requireTlsForRemote: Boolean = true,
    ): EndpointValidation {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return invalid("the destination is empty")
        if (source == EndpointSource.QR_PAYLOAD) {
            return invalid("a QR payload is not a trusted destination; enter or confirm the server address")
        }

        val uri =
            try {
                URI(trimmed)
            } catch (_: URISyntaxException) {
                return invalid("the destination is not a valid URL")
            }

        val scheme = uri.scheme?.lowercase() ?: return invalid("the destination needs an http(s) scheme")
        if (scheme != "http" && scheme != "https") return invalid("only http(s) destinations are supported")
        if (uri.userInfo != null) return invalid("credentials must not be embedded in the destination URL")

        val host = uri.host?.removeSurrounding("[", "]")?.lowercase() ?: return invalid("the destination needs a host")
        if (host.isBlank()) return invalid("the destination needs a host")

        val port =
            if (uri.port in 1..65535) {
                uri.port
            } else if (scheme == "https") {
                443
            } else {
                80
            }
        val destination = ServerDestination(scheme = scheme, host = host, port = port, basePath = uri.path.orEmpty())

        if (scheme == "http" && requireTlsForRemote && !destination.isLoopback) {
            return EndpointValidation.Invalid(
                VoiceAgentError(
                    code = ErrorCode.PROVIDER_ENDPOINT_INSECURE,
                    detail = "TLS (https) is required for a non-local destination",
                ),
            )
        }
        return EndpointValidation.Valid(destination)
    }

    private fun invalid(detail: String): EndpointValidation.Invalid =
        EndpointValidation.Invalid(VoiceAgentError(ErrorCode.PROVIDER_ENDPOINT_INVALID, detail = detail))
}

/**
 * Applies the destination rules in the context of one provider.
 *
 * A fixed provider (OpenAI, OpenRouter, the OpenCode surfaces, DeepSeek) must
 * point at its documented host — a substituted host is refused so a request can
 * never be redirected with a valid-looking URL. A configurable provider (Hermes)
 * is validated by [ServerDestinationValidator] with TLS required for non-local
 * hosts.
 */
object ProviderEndpointPolicy {
    /** The destination to disclose/use for [capabilities], given an optional user value. */
    fun destinationFor(
        capabilities: ProviderCapabilities,
        configured: String? = null,
    ): EndpointValidation {
        val transport = capabilities.transport
        return if (transport.configurable) {
            if (configured.isNullOrBlank()) {
                EndpointValidation.Invalid(
                    VoiceAgentError(ErrorCode.PROVIDER_ENDPOINT_INVALID, detail = "this provider needs a configured server address"),
                )
            } else {
                ServerDestinationValidator.validate(configured, EndpointSource.USER_ENTERED)
            }
        } else {
            ServerDestinationValidator.validate(
                raw = transport.baseUrl.orEmpty(),
                source = EndpointSource.PROVIDER_DEFAULT,
            )
        }
    }

    /** Validates a candidate endpoint for [capabilities]. */
    fun validateForProvider(
        capabilities: ProviderCapabilities,
        raw: String,
        source: EndpointSource = EndpointSource.USER_ENTERED,
    ): EndpointValidation {
        val transport = capabilities.transport
        if (transport.configurable) {
            return ServerDestinationValidator.validate(raw, source)
        }

        val expectedHost = transport.expectedHost ?: return invalid("this provider has no documented endpoint")
        val validated = ServerDestinationValidator.validate(raw, source, requireTlsForRemote = true)
        if (validated is EndpointValidation.Invalid) return validated
        val destination = (validated as EndpointValidation.Valid).destination
        if (!destination.host.equals(expectedHost, ignoreCase = true)) {
            return invalid("the destination host does not match the provider's documented endpoint")
        }
        return validated
    }

    private fun invalid(detail: String): EndpointValidation.Invalid =
        EndpointValidation.Invalid(VoiceAgentError(ErrorCode.PROVIDER_ENDPOINT_INVALID, detail = detail))
}
