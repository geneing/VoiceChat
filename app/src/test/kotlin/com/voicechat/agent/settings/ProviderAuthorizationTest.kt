package com.voicechat.agent.settings

import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.PairingQrPolicy
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.QrInspection
import com.voicechat.agent.providers.QrRejection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M22 acceptance for provider-authorized pairing and QR handling.
 *
 * The short-lived, single-use authorization lifecycle is proven for the one
 * provider that documents a browser flow (OpenRouter PKCE). QR is proven
 * unsupported and unsafe: no provider offers it, a credential-bearing payload is
 * rejected, and an arbitrary QR/destination is never trusted.
 */
class ProviderAuthorizationTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val openRouter = registry.capabilities(KnownProviders.OPENROUTER)!!
    private val openAi = registry.capabilities(KnownProviders.OPENAI)!!
    private val openRouterAuth = "https://openrouter.ai/auth"

    @Test
    fun aDocumentedMethodOnTheProviderHostBeginsAShortLivedSession() =
        runBlocking {
            val coordinator = AuthorizationSessionCoordinator(clock = { 1_000L }, ttlMillis = 60_000L)

            val outcome = coordinator.begin(openRouter, AuthMethod.OAUTH_PKCE, openRouterAuth)

            val session = (outcome as AuthorizationOutcome.AwaitingAuthorization).session
            assertEquals(KnownProviders.OPENROUTER, session.providerId)
            assertEquals(AuthMethod.OAUTH_PKCE, session.method)
            assertEquals(61_000L, session.expiresAtEpochMillis)
            assertTrue(!session.isExpired(60_999L))
        }

    @Test
    fun aPairingChallengeIsAcceptedOnceThenRejectedAsReused() =
        runBlocking {
            val coordinator = AuthorizationSessionCoordinator(clock = { 1_000L }, ttlMillis = 60_000L)
            val session =
                (coordinator.begin(openRouter, AuthMethod.OAUTH_PKCE, openRouterAuth) as AuthorizationOutcome.AwaitingAuthorization).session

            assertEquals(AuthorizationOutcome.Completed(session), coordinator.complete(session))
            assertEquals(
                AuthorizationOutcome.Rejected(AuthorizationRejection.REUSED),
                coordinator.complete(session),
            )
        }

    @Test
    fun anExpiredPairingChallengeIsRejected() =
        runBlocking {
            var now = 1_000L
            val coordinator = AuthorizationSessionCoordinator(clock = { now }, ttlMillis = 60_000L)
            val session =
                (coordinator.begin(openRouter, AuthMethod.OAUTH_PKCE, openRouterAuth) as AuthorizationOutcome.AwaitingAuthorization).session

            now = 61_001L
            assertEquals(
                AuthorizationOutcome.Rejected(AuthorizationRejection.EXPIRED),
                coordinator.complete(session),
            )
        }

    @Test
    fun anArbitraryDestinationIsRejected() =
        runBlocking {
            val coordinator = AuthorizationSessionCoordinator(clock = { 1_000L })

            assertEquals(
                AuthorizationOutcome.Rejected(AuthorizationRejection.ARBITRARY_DESTINATION),
                coordinator.begin(openRouter, AuthMethod.OAUTH_PKCE, "https://evil.example.com/auth"),
            )
        }

    @Test
    fun aUriCarryingAReusableCredentialIsRejected() =
        runBlocking {
            val coordinator = AuthorizationSessionCoordinator(clock = { 1_000L })

            assertEquals(
                AuthorizationOutcome.Rejected(AuthorizationRejection.CARRIES_CREDENTIAL),
                coordinator.begin(
                    openRouter,
                    AuthMethod.OAUTH_PKCE,
                    "https://openrouter.ai/auth?api_key=sk-live-0123456789abcdef",
                ),
            )
        }

    @Test
    fun anUndocumentedMethodIsRejected() =
        runBlocking {
            val coordinator = AuthorizationSessionCoordinator(clock = { 1_000L })

            assertEquals(
                AuthorizationOutcome.Rejected(AuthorizationRejection.UNSUPPORTED_METHOD),
                coordinator.begin(openAi, AuthMethod.OAUTH_PKCE, "https://api.openai.com/v1"),
            )
        }

    @Test
    fun theDefaultFlowNeverFakesASuccessfulPairing() =
        runBlocking {
            assertEquals(
                AuthorizationOutcome.Rejected(AuthorizationRejection.NOT_IMPLEMENTED),
                UnimplementedProviderAuthFlow.begin(openRouter, AuthMethod.OAUTH_PKCE),
            )
        }

    @Test
    fun qrIsUnsupportedAndCredentialOrArbitraryPayloadsAreRejected() {
        // No provider documents QR, so no provider can offer it.
        registry.all().forEach { provider ->
            assertTrue(provider.availableAuthMethods.none { it.name.contains("QR") })
        }
        // A QR carrying a credential is refused with the specific reason.
        assertEquals(
            QrInspection.Rejected(QrRejection.CARRIES_CREDENTIAL),
            PairingQrPolicy.inspect("voicechat://pair?api_key=sk-live-0123456789abcdef"),
        )
        // Any other QR is not a provider pairing challenge this app can complete.
        assertEquals(
            QrInspection.Rejected(QrRejection.NOT_A_PAIRING_CHALLENGE),
            PairingQrPolicy.inspect("https://evil.example.com/pair"),
        )
    }
}
