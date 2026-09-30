package com.voicechat.agent.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M13 acceptance for QR handling: a QR payload must never carry a reusable
 * credential, and (with [EndpointValidationTest]) a QR URL is never trusted as a
 * destination.
 */
class PairingQrPolicyTest {
    @Test
    fun aPayloadCarryingAnApiKeyIsFlagged() {
        assertTrue(PairingQrPolicy.carriesReusableCredential("voicechat://pair?api_key=sk-live-0123456789abcdef"))
        assertTrue(PairingQrPolicy.carriesReusableCredential("sk-live-0123456789abcdef"))
    }

    @Test
    fun aPayloadCarryingOtherCredentialsIsFlagged() {
        assertTrue(PairingQrPolicy.carriesReusableCredential("""{"access_token":"abc123def456"}"""))
        assertTrue(PairingQrPolicy.carriesReusableCredential("""{"refresh_token":"zzz999yyy888"}"""))
        assertTrue(PairingQrPolicy.carriesReusableCredential("password=correct-horse-battery"))
        assertTrue(PairingQrPolicy.carriesReusableCredential("Authorization: Bearer abcdefgh12345678"))
    }

    @Test
    fun aPlainChallengePayloadIsNotFlaggedAsCarryingACredential() {
        assertFalse(PairingQrPolicy.carriesReusableCredential("voicechat://pair?id=abc123&challenge=xyz789"))
        assertFalse(PairingQrPolicy.carriesReusableCredential("https://example.com/pair/challenge"))
    }

    @Test
    fun inspectRejectsACredentialBearingPayloadWithASpecificReason() {
        val inspection = PairingQrPolicy.inspect("voicechat://pair?api_key=sk-live-0123456789abcdef")

        assertEquals(QrInspection.Rejected(QrRejection.CARRIES_CREDENTIAL), inspection)
    }

    @Test
    fun inspectRejectsAnEmptyOrArbitraryPayload() {
        assertEquals(QrInspection.Rejected(QrRejection.EMPTY), PairingQrPolicy.inspect("  "))
        assertEquals(QrInspection.Rejected(QrRejection.NOT_A_PAIRING_CHALLENGE), PairingQrPolicy.inspect("https://hermes.example.com/v1"))
    }
}
