package com.voicechat.agent.settings

import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.EndpointValidation
import com.voicechat.agent.providers.PairingQrPolicy
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.providers.ProviderEndpointPolicy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Why an authorization attempt was refused. */
enum class AuthorizationRejection {
    /** The provider does not document the method. */
    UNSUPPORTED_METHOD,

    /** The authorization URL is not the provider's documented host. */
    ARBITRARY_DESTINATION,

    /** The payload embeds a reusable credential; a pairing must never carry one. */
    CARRIES_CREDENTIAL,

    /** The short-lived challenge expired before it was completed. */
    EXPIRED,

    /** The single-use challenge was already completed. */
    REUSED,

    /** This build has no wired browser/device flow yet; do not fake a pairing. */
    NOT_IMPLEMENTED,
}

/**
 * One provider-authorized authorization attempt.
 *
 * The [state] is an opaque, single-use nonce; [expiresAtEpochMillis] bounds the
 * window. The session is **not** a credential and never contains one: completing
 * the flow (exchanging the provider's short-lived code) is M14+/M23 work and
 * produces a [Credential] stored in the M13 store.
 */
data class AuthorizationSession(
    val providerId: ProviderId,
    val method: AuthMethod,
    val state: String,
    val authorizationUri: String,
    val expiresAtEpochMillis: Long,
) {
    /** True once [now] is past the short-lived window. */
    fun isExpired(now: Long): Boolean = now > expiresAtEpochMillis
}

/** Outcome of beginning or completing a provider authorization. */
sealed interface AuthorizationOutcome {
    /** The provider's authorization page/session is ready; the user must authorize. */
    data class AwaitingAuthorization(
        val session: AuthorizationSession,
    ) : AuthorizationOutcome

    /** The provider authorized the single use; exchange the code for a credential (M14+). */
    data class Completed(
        val session: AuthorizationSession,
    ) : AuthorizationOutcome

    /** The attempt was refused; [reason] says why. */
    data class Rejected(
        val reason: AuthorizationRejection,
    ) : AuthorizationOutcome
}

/**
 * A browser/device authorization flow for a provider that documents one.
 *
 * M22 implements only [AuthorizationSessionCoordinator], which enforces the
 * security invariants (documented method, provider host, short-lived, single-use,
 * never carrying a reusable credential). The network/token exchange is a
 * provider adapter concern (M14+) behind this seam.
 */
interface ProviderAuthFlow {
    /** Begins an authorization for [method]; [authorizationUri] must be the provider's own. */
    suspend fun begin(
        provider: ProviderCapabilities,
        method: AuthMethod,
        authorizationUri: String = provider.transport.baseUrl.orEmpty(),
    ): AuthorizationOutcome

    /** Completes [session] once, inside its short-lived window. */
    suspend fun complete(session: AuthorizationSession): AuthorizationOutcome
}

/**
 * The honest default when no client flow is wired: it refuses rather than
 * pretending a pairing succeeded. Used in the shipped 0.1.0 build until M14/M23
 * add the OpenRouter PKCE exchange.
 */
object UnimplementedProviderAuthFlow : ProviderAuthFlow {
    override suspend fun begin(
        provider: ProviderCapabilities,
        method: AuthMethod,
        authorizationUri: String,
    ): AuthorizationOutcome = AuthorizationOutcome.Rejected(AuthorizationRejection.NOT_IMPLEMENTED)

    override suspend fun complete(session: AuthorizationSession): AuthorizationOutcome =
        AuthorizationOutcome.Rejected(AuthorizationRejection.NOT_IMPLEMENTED)
}

/**
 * The provider-authorized, short-lived, single-use pairing lifecycle (M22).
 *
 * It is deliberately transport-free and platform-free: it does not itself open a
 * browser, so it is fully unit-testable. The invariants it enforces are the ones
 * the acceptance requires:
 *
 * - only a **documented** auth method is accepted (the QR method has no enum
 *   value at all, so it can never be requested);
 * - the authorization URI must be on the provider's own host, so an arbitrary
 *   QR-scanned or user-entered destination is refused;
 * - a URL that embeds a reusable credential is refused ([PairingQrPolicy]);
 * - a challenge is **short-lived** (expires after [ttlMillis]) and **single-use**
 *   (completing it twice is refused).
 */
class AuthorizationSessionCoordinator(
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    private val stateFactory: () -> String = { UUID.randomUUID().toString() },
) : ProviderAuthFlow {
    private val completedStates: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        require(ttlMillis > 0) { "an authorization challenge must be short-lived, not open-ended" }
    }

    override suspend fun begin(
        provider: ProviderCapabilities,
        method: AuthMethod,
        authorizationUri: String,
    ): AuthorizationOutcome {
        if (method !in provider.availableAuthMethods) {
            return AuthorizationOutcome.Rejected(AuthorizationRejection.UNSUPPORTED_METHOD)
        }
        if (PairingQrPolicy.carriesReusableCredential(authorizationUri)) {
            return AuthorizationOutcome.Rejected(AuthorizationRejection.CARRIES_CREDENTIAL)
        }
        if (ProviderEndpointPolicy.validateForProvider(provider, authorizationUri) !is EndpointValidation.Valid) {
            return AuthorizationOutcome.Rejected(AuthorizationRejection.ARBITRARY_DESTINATION)
        }
        val session =
            AuthorizationSession(
                providerId = provider.providerId,
                method = method,
                state = stateFactory(),
                authorizationUri = authorizationUri,
                expiresAtEpochMillis = clock() + ttlMillis,
            )
        return AuthorizationOutcome.AwaitingAuthorization(session)
    }

    override suspend fun complete(session: AuthorizationSession): AuthorizationOutcome {
        if (session.isExpired(clock())) return AuthorizationOutcome.Rejected(AuthorizationRejection.EXPIRED)
        if (!completedStates.add(session.state)) return AuthorizationOutcome.Rejected(AuthorizationRejection.REUSED)
        return AuthorizationOutcome.Completed(session)
    }

    companion object {
        /** A five-minute authorization window; the provider's code is exchanged immediately. */
        const val DEFAULT_TTL_MILLIS: Long = 5 * 60 * 1000L
    }
}
