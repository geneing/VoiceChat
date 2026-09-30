package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.DeterministicLanguageModel
import com.voicechat.agent.fake.ScriptedLlmStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the M12 requirement that the contract is usable by real adapters
 * without either side knowing the other's vendor types: these reference
 * adapters are written the way M14–M20 will be, and their provider-specific
 * exceptions map into the typed contract.
 *
 * They are deliberately throwaway test doubles — the point is the *mapping*, not
 * a real provider.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmAdapterMappingTest {
    private val selection = ProviderModelSelection(ProviderId("openrouter"), ModelId("some/model"))
    private val request = LlmRequest(model = selection, messages = listOf(LlmMessage(LlmRole.USER, "hi")))

    /** Stands in for a vendor SDK exception so the mapping is exercised, not assumed. */
    private class VendorHttpException(
        val status: Int,
        val body: String,
    ) : Exception("$status: $body")

    /**
     * A reference adapter that maps vendor HTTP statuses to typed contract
     * failures, exactly the way M14–M19 must. It returns typed events only; the
     * vendor exception never crosses the contract boundary.
     */
    private class MappingAdapter(
        private val failure: VendorHttpException?,
    ) : LanguageModel {
        override val providerId: ProviderId = ProviderId("openrouter")

        override val capabilities: LlmCapabilities =
            LlmCapabilities(streaming = true, usageReporting = true, reasoningLevels = setOf(ReasoningLevel.LOW))

        override fun stream(request: LlmRequest): Flow<LlmStreamEvent> {
            val error = failure?.let { it.toContractError(request) }
            return if (error != null) {
                flowOf(LlmStreamEvent.Failed(error = error, partialText = ""))
            } else {
                flowOf(LlmStreamEvent.Delta("ok"), LlmStreamEvent.Completed())
            }
        }

        private fun VendorHttpException.toContractError(request: LlmRequest): VoiceAgentError =
            when (status) {
                401, 403 -> VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED)
                429 -> VoiceAgentError(ErrorCode.LLM_RATE_LIMITED)
                408, 504 -> VoiceAgentError(ErrorCode.LLM_TIMEOUT)
                in 500..599 -> VoiceAgentError(ErrorCode.LLM_UNAVAILABLE)
                else -> VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE)
            }

        override suspend fun close() = Unit
    }

    @Test
    fun eachVendorStatusMapsToItsDistinctTypedContractFailure() =
        runTest {
            val cases =
                mapOf(
                    401 to ErrorCode.LLM_AUTHENTICATION_FAILED,
                    403 to ErrorCode.LLM_AUTHENTICATION_FAILED,
                    429 to ErrorCode.LLM_RATE_LIMITED,
                    408 to ErrorCode.LLM_TIMEOUT,
                    503 to ErrorCode.LLM_UNAVAILABLE,
                    400 to ErrorCode.LLM_MALFORMED_RESPONSE,
                )

            cases.forEach { (status, expectedCode) ->
                val adapter = MappingAdapter(VendorHttpException(status, "vendor secret body"))
                val result = adapter.consume(request)

                assertEquals(expectedCode, result.error?.code)
                // The vendor body must never reach the typed error's detail.
                assertFalse(result.error?.detail?.contains("vendor secret body") == true)
            }
        }

    @Test
    fun aSuccessfulVendorStreamConsumesThroughTheSameReferenceConsumer() =
        runTest {
            val adapter = MappingAdapter(failure = null)

            val result = adapter.consume(request)

            assertTrue(result.completed)
            assertEquals("ok", result.text)
            assertEquals(1, result.deltaCount)
        }

    @Test
    fun anAdapterDeclaresItsOwnCapabilitiesRatherThanInheritingThem() {
        val adapter = MappingAdapter(failure = null)

        assertTrue(adapter.capabilities.usageReporting)
        assertEquals(setOf(ReasoningLevel.LOW), adapter.capabilities.reasoningLevels)
        // The conservative default is what an adapter gets if it says nothing.
        assertFalse(LlmCapabilities.UNVERIFIED.usageReporting)
    }

    @Test
    fun anUnsupportedReasoningLevelIsRejectedBeforeTheVendorCall() =
        runTest {
            val adapter = MappingAdapter(failure = null)
            // The adapter declares only LOW; asking for HIGH must be refused by
            // the shared validator, not sent and hoped for.
            val validation = LlmRequestValidator.validate(request.copy(reasoning = ReasoningLevel.HIGH), adapter.capabilities)

            assertTrue(validation is LlmRequestValidation.Unsupported)
        }

    @Test
    fun theDeterministicFakeAndAReferenceAdapterAreInterchangeableBehindTheContract() =
        runTest {
            val models: List<LanguageModel> =
                listOf(
                    MappingAdapter(failure = null),
                    DeterministicLanguageModel(
                        steps =
                            listOf(
                                ScriptedLlmStep.Emit(LlmStreamEvent.Delta("ok")),
                                ScriptedLlmStep.Emit(LlmStreamEvent.Completed()),
                            ),
                    ),
                )

            models.forEach { model ->
                val result = model.consume(request)
                assertEquals("ok", result.text)
                assertTrue(result.completed)
            }
        }
}
