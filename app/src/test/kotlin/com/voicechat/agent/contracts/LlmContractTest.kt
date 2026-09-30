package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCategory
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the M12 request, capability, failure, usage, and stream
 * rules. They assert the *contract's* behavior (what it refuses and what it
 * reports), independent of any fake or adapter.
 */
class LlmContractTest {
    private val selection = ProviderModelSelection(ProviderId("provider"), ModelId("model"))

    private fun request(
        reasoning: ReasoningLevel? = null,
        messages: List<LlmMessage> = listOf(LlmMessage(LlmRole.USER, "hello")),
    ) = LlmRequest(model = selection, messages = messages, reasoning = reasoning)

    // region failure reasons

    @Test
    fun everyFailureReasonCarriesADistinctStableLanguageModelCode() {
        val codes = LlmFailureReason.entries.map { it.defaultErrorCode }

        assertEquals(codes.size, codes.distinct().size)
        assertTrue(codes.all { it.category == ErrorCategory.LANGUAGE_MODEL })
        // The required distinctions from M12: delta/completion are not errors,
        // but timeout, rate limit, auth, network, malformed, cancellation, and an
        // unclassified failure each map to their own code.
        assertEquals(ErrorCode.LLM_TIMEOUT, LlmFailureReason.TIMEOUT.defaultErrorCode)
        assertEquals(ErrorCode.LLM_RATE_LIMITED, LlmFailureReason.RATE_LIMITED.defaultErrorCode)
        assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, LlmFailureReason.AUTHENTICATION.defaultErrorCode)
        assertEquals(ErrorCode.LLM_NETWORK_FAILED, LlmFailureReason.NETWORK.defaultErrorCode)
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, LlmFailureReason.MALFORMED_RESPONSE.defaultErrorCode)
        assertEquals(ErrorCode.LLM_CANCELLED, LlmFailureReason.CANCELLED.defaultErrorCode)
        assertEquals(ErrorCode.LLM_REQUEST_FAILED, LlmFailureReason.OTHER.defaultErrorCode)
    }

    @Test
    fun failureReasonRoundTripsThroughItsErrorCode() {
        LlmFailureReason.entries.forEach { reason ->
            assertEquals(reason, LlmFailureReason.fromErrorCode(reason.defaultErrorCode))
        }
    }

    @Test
    fun anUnrelatedErrorCodeFallsBackToOtherAndIsNeverFabricated() {
        assertEquals(LlmFailureReason.OTHER, LlmFailureReason.fromErrorCode(ErrorCode.PERSISTENCE_FAILED))
        assertEquals(LlmFailureReason.OTHER, LlmFailureReason.fromErrorCode(ErrorCode.UNKNOWN))
    }

    @Test
    fun aFailedEventDefaultsItsReasonFromItsError() {
        val failed = LlmStreamEvent.Failed(VoiceAgentError(ErrorCode.LLM_RATE_LIMITED), partialText = "x")

        assertEquals(LlmFailureReason.RATE_LIMITED, failed.reason)
    }

    // endregion

    // region ordering rules

    @Test
    fun aStreamIsDeltasThenExactlyOneTerminalEvent() {
        val delta = LlmStreamEvent.Delta("a")
        val completed = LlmStreamEvent.Completed()

        assertTrue(isLegalStreamTransition(null, delta))
        assertTrue(isLegalStreamTransition(delta, delta))
        assertTrue(isLegalStreamTransition(delta, completed))
        assertTrue(isLegalStreamTransition(null, completed))
        // A terminal event ends the stream; nothing may follow it.
        assertFalse(isLegalStreamTransition(completed, delta))
        assertFalse(isLegalStreamTransition(completed, LlmStreamEvent.Completed()))
        assertFalse(isLegalStreamTransition(LlmStreamEvent.Failed(VoiceAgentError(ErrorCode.LLM_TIMEOUT), ""), delta))
    }

    @Test
    fun terminalIsExactlyTheNonDeltaEvents() {
        assertFalse(LlmStreamEvent.Delta("a").isTerminal)
        assertTrue(LlmStreamEvent.Completed().isTerminal)
        assertTrue(LlmStreamEvent.Cancelled("x").isTerminal)
        assertTrue(LlmStreamEvent.Failed(VoiceAgentError(ErrorCode.LLM_TIMEOUT), "x").isTerminal)
    }

    @Test
    fun anEventAfterATerminalEventIsRejected() {
        val terminal = LlmStreamEvent.Completed()

        assertThrows(IllegalArgumentException::class.java) {
            LlmStreamEvent.Delta("late").requireLegalAfter(terminal)
        }
        assertEquals(LlmStreamEvent.Delta("ok").requireLegalAfter(null), LlmStreamEvent.Delta("ok"))
    }

    @Test
    fun streamValidatorFindsTheFirstViolationAndItsIndex() {
        val legal =
            listOf(
                LlmStreamEvent.Delta("a"),
                LlmStreamEvent.Delta("b"),
                LlmStreamEvent.Completed(),
            )
        assertNull(LlmStreamValidator.firstViolation(legal))
        assertNull(LlmStreamValidator.firstViolation(emptyList()))

        val illegal =
            listOf(
                LlmStreamEvent.Delta("a"),
                LlmStreamEvent.Completed(),
                LlmStreamEvent.Delta("after terminal"),
            )
        val violation = LlmStreamValidator.firstViolation(illegal)!!
        assertEquals(2, violation.index)
        assertTrue(violation.previous is LlmStreamEvent.Completed)
        assertTrue(violation.describe().contains("Delta"))
    }

    // endregion

    // region capabilities and request validation

    @Test
    fun theUnverifiedCapabilityClaimsNothingBeyondStreaming() {
        val unverified = LlmCapabilities.UNVERIFIED

        assertTrue(unverified.streaming)
        assertFalse(unverified.usageReporting)
        assertTrue(unverified.reasoningLevels.isEmpty())
    }

    @Test
    fun aReasoningRequestOutsideTheDeclaredCapabilityIsRefusedNotDowngraded() {
        val capabilities = LlmCapabilities(reasoningLevels = setOf(ReasoningLevel.LOW, ReasoningLevel.MEDIUM))

        assertTrue(LlmRequestValidator.validate(request(), capabilities) is LlmRequestValidation.Supported)
        assertTrue(
            LlmRequestValidator.validate(request(ReasoningLevel.LOW), capabilities) is LlmRequestValidation.Supported,
        )
        // NONE means "do not think", which every provider can express.
        assertTrue(
            LlmRequestValidator.validate(request(ReasoningLevel.NONE), capabilities) is LlmRequestValidation.Supported,
        )

        val refused = LlmRequestValidator.validate(request(ReasoningLevel.HIGH), capabilities)
        assertTrue(refused is LlmRequestValidation.Unsupported)
        assertEquals(ErrorCode.LLM_INVALID_REQUEST, (refused as LlmRequestValidation.Unsupported).error.code)
        // An unsupported request cannot succeed on retry.
        assertFalse(refused.error.retryable)
    }

    @Test
    fun anAdapterWhoseDocsAreUnverifiedRefusesEveryReasoningLevel() {
        val refused = LlmRequestValidator.validate(request(ReasoningLevel.LOW), LlmCapabilities.UNVERIFIED)

        assertTrue(refused is LlmRequestValidation.Unsupported)
    }

    // endregion

    // region usage

    @Test
    fun usageDistinguishesNotReportedFromReportedZero() {
        assertTrue(LlmUsage().isEmpty)
        assertNull(LlmUsage().promptTokens)
        assertFalse(LlmUsage(promptTokens = 0).isEmpty)
        assertEquals(0, LlmUsage(promptTokens = 0).promptTokens)
    }

    @Test
    fun usageRejectsNegativeCounts() {
        assertThrows(IllegalArgumentException::class.java) { LlmUsage(promptTokens = -1) }
        assertThrows(IllegalArgumentException::class.java) { LlmUsage(completionTokens = -1) }
        assertThrows(IllegalArgumentException::class.java) { LlmUsage(totalTokens = -1) }
    }

    // endregion

    // region request

    @Test
    fun requestCountsItsCharactersAndKeepsModelIdentityTogether() {
        val request =
            LlmRequest(
                model = selection,
                messages =
                    listOf(
                        LlmMessage(LlmRole.SYSTEM, "sys"),
                        LlmMessage(LlmRole.USER, "hello"),
                        LlmMessage(LlmRole.ASSISTANT, "hi"),
                    ),
            )

        assertEquals(3 + 5 + 2, request.characterCount)
        assertEquals(ProviderId("provider"), request.model.providerId)
        assertEquals(ModelId("model"), request.model.modelId)
        assertNull(request.reasoning)
    }

    // endregion

    // region terminal partial state

    @Test
    fun aCancelledOrFailedEventCarriesThePartialTextSoAPartialResponseIsExplicit() {
        val cancelled = LlmStreamEvent.Cancelled(partialText = "half a sen")
        val failed =
            LlmStreamEvent.Failed(
                error = VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED),
                partialText = "half a sen",
            )

        assertEquals("half a sen", cancelled.partialText)
        assertEquals(LlmFailureReason.CANCELLED, cancelled.reason)
        assertEquals("half a sen", failed.partialText)
        assertEquals(LlmFailureReason.NETWORK, failed.reason)
        // A completion is the only event that means the text is whole.
        assertFalse(LlmStreamEvent.Completed().isTerminal.not())
        assertEquals(null, LlmStreamEvent.Completed().usage)
    }

    // endregion

    // region envelope

    @Test
    fun theEnvelopeExposesStreamStateWithoutSubtypeInspection() {
        val delta = LlmStreamEventEnvelope(LlmStreamEvent.Delta("ab"))
        assertTrue(delta.isDelta)
        assertFalse(delta.completed)
        assertFalse(delta.isTerminal)
        assertEquals("ab", delta.partialText)

        val completed =
            LlmStreamEventEnvelope(
                LlmStreamEvent.Completed(usage = LlmUsage(totalTokens = 7)),
                model = ModelId("served"),
            )
        assertTrue(completed.completed)
        assertTrue(completed.isTerminal)
        assertEquals("", completed.partialText)
        assertEquals(ModelId("served"), completed.model)
        assertEquals(7, completed.usage?.totalTokens)
        assertNull(completed.error)

        val failed = LlmStreamEventEnvelope(LlmStreamEvent.Failed(VoiceAgentError(ErrorCode.LLM_RATE_LIMITED), "p"))
        assertTrue(failed.isTerminal)
        assertEquals("p", failed.partialText)
        assertEquals(ErrorCode.LLM_RATE_LIMITED, failed.error?.code)
        assertNull(failed.usage)
    }

    // endregion
}
