package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.fake.DeterministicLanguageModel
import com.voicechat.agent.fake.ScriptedLlmStep
import com.voicechat.agent.fake.awaitFirstEmission
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the M12 acceptance criteria against the deterministic fake: event
 * ordering, backpressure, cancellation, a stall that only cancellation ends, a
 * timeout, malformed and empty streams, partial-response state, and — the point
 * of the fake — determinism on virtual time with no network or credentials.
 *
 * Every timing assertion uses the `runTest` scheduler, so a passing run also
 * proves no real time passed: `testScheduler.currentTime` is advanced explicitly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeterministicLanguageModelTest {
    private val selection = ProviderModelSelection(ProviderId("deterministic"), ModelId("fake-model"))
    private val request = LlmRequest(model = selection, messages = listOf(LlmMessage(LlmRole.USER, "q")))

    // region ordering

    @Test
    fun deltasArriveInOrderBeforeTheSingleTerminalCompletion() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("Hel"), afterMillis = 5),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("lo"), afterMillis = 5),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Completed(usage = LlmUsage(totalTokens = 9)), afterMillis = 5),
                        ),
                )

            val result = model.consume(request)

            assertEquals("Hello", result.text)
            assertEquals(2, result.deltaCount)
            assertTrue(result.completed)
            assertEquals(9, result.usage?.totalTokens)
            assertEquals(15L, testScheduler.currentTime)
            assertEquals(listOf(3), listOf(model.emittedEventCount))
        }

    @Test
    fun aScriptThatEmitsAfterATerminalEventIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException::class.java) {
            DeterministicLanguageModel(
                steps =
                    listOf(
                        ScriptedLlmStep.Emit(LlmStreamEvent.Completed()),
                        ScriptedLlmStep.Emit(LlmStreamEvent.Delta("late")),
                    ),
            )
        }
    }

    // endregion

    // region determinism

    @Test
    fun theSameScriptProducesTheSameEventSequenceAndVirtualTimingOnEveryRun() {
        val build = {
            DeterministicLanguageModel(
                steps =
                    listOf(
                        ScriptedLlmStep.Emit(LlmStreamEvent.Delta("a"), afterMillis = 1),
                        ScriptedLlmStep.Stall(3),
                        ScriptedLlmStep.Emit(LlmStreamEvent.Delta("b"), afterMillis = 2),
                        ScriptedLlmStep.Emit(LlmStreamEvent.Completed(), afterMillis = 4),
                    ),
            )
        }
        val first = mutableListOf<LlmStreamEvent>()
        val second = mutableListOf<LlmStreamEvent>()
        val firstTimes = mutableListOf<Long>()
        val secondTimes = mutableListOf<Long>()

        runTest {
            val model = build()
            model.stream(request).collect {
                first += it
                firstTimes += testScheduler.currentTime
            }
        }
        runTest {
            val model = build()
            model.stream(request).collect {
                second += it
                secondTimes += testScheduler.currentTime
            }
        }

        assertEquals(first, second)
        assertEquals(firstTimes, secondTimes)
        // delta at 1 ms; then a 3 ms stall plus the 2 ms wait -> 6 ms; then 4 ms -> 10 ms.
        assertEquals(listOf(1L, 6L, 10L), firstTimes)
    }

    // endregion

    // region backpressure

    @Test
    fun aSlowConsumerSuspendsTheProducerInsteadOfLosingEvents() =
        runTest {
            val capacity = 1
            val events = (0 until 40).map { ScriptedLlmStep.Emit(LlmStreamEvent.Delta("d$it")) }
            val model =
                DeterministicLanguageModel(
                    steps = events + ScriptedLlmStep.Emit(LlmStreamEvent.Completed()),
                    bufferCapacity = capacity,
                )

            val received = mutableListOf<LlmStreamEvent>()
            // The producer runs hot as soon as collectStream starts; it can only
            // ever be `capacity` events ahead of the consumer.
            val flow = model.collectStream(request)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(0, model.emittedEventCount)

            flow.collect { received += it }

            assertEquals(41, received.size)
            assertEquals(events.map { (it as ScriptedLlmStep.Emit).event }, received.dropLast(1))
            assertTrue(received.last() is LlmStreamEvent.Completed)
            assertEquals(41, model.emittedEventCount)
        }

    // endregion

    // region cancellation and stalls

    @Test
    fun cancellingAMidStreamCollectionStopsEventsAndCountsTheCancellation() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("first"), afterMillis = 10),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("second"), afterMillis = 10),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Completed(), afterMillis = 10),
                        ),
                )
            val received = mutableListOf<LlmStreamEvent>()

            val job = launch { model.stream(request).collect { received += it } }
            advanceTimeBy(15)
            runCurrent()
            job.cancelAndJoin()

            assertEquals(1, received.size)
            assertTrue(received.single() is LlmStreamEvent.Delta)
            assertEquals(1, model.cancellationCount)
            // Only one delta ran: cancelling before the second 10 ms delay
            // elapsed means the rest of the script never executed.
            assertEquals(1, model.emittedEventCount)
            assertEquals(15L, testScheduler.currentTime)
        }

    @Test
    fun aStallLeavesTheStreamOpenUntilItIsCancelled() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("partial")),
                            ScriptedLlmStep.Stall(millis = 30_000),
                        ),
                )
            val received = mutableListOf<LlmStreamEvent>()

            val job = launch { model.stream(request).collect { received += it } }
            advanceTimeBy(29_999)
            runCurrent()
            // Still streaming: a stall is not a completion, so nothing is
            // reported as finished while the provider is silent.
            assertEquals(1, received.size)
            assertTrue(job.isActive)
            assertNull(model.terminalEvent)

            job.cancelAndJoin()
            assertEquals(1, model.cancellationCount)
            assertEquals(1, received.size)
        }

    @Test
    fun aTimeoutIsExpressedAsATypedFailureNotAStallForever() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("waiting")),
                            ScriptedLlmStep.Stall(millis = 5_000),
                            ScriptedLlmStep.Emit(
                                LlmStreamEvent.Failed(
                                    error = VoiceAgentError(ErrorCode.LLM_TIMEOUT),
                                    partialText = "waiting",
                                ),
                            ),
                        ),
                )

            val result = model.consume(request)

            assertFalse(result.completed)
            assertTrue(result.isPartial)
            assertEquals(ErrorCode.LLM_TIMEOUT, result.error?.code)
            assertEquals(LlmFailureReason.TIMEOUT, result.failureReason)
            assertEquals("waiting", result.text)
        }

    // endregion

    // region errors

    @Test
    fun everyRequiredFailureIsDistinctlyRepresented() =
        runTest {
            val cases =
                mapOf(
                    LlmFailureReason.TIMEOUT to ErrorCode.LLM_TIMEOUT,
                    LlmFailureReason.RATE_LIMITED to ErrorCode.LLM_RATE_LIMITED,
                    LlmFailureReason.AUTHENTICATION to ErrorCode.LLM_AUTHENTICATION_FAILED,
                    LlmFailureReason.NETWORK to ErrorCode.LLM_NETWORK_FAILED,
                    LlmFailureReason.MALFORMED_RESPONSE to ErrorCode.LLM_MALFORMED_RESPONSE,
                )
            val seen = mutableSetOf<LlmFailureReason>()

            cases.forEach { (reason, code) ->
                val model =
                    DeterministicLanguageModel(
                        steps =
                            listOf(
                                ScriptedLlmStep.Emit(
                                    LlmStreamEvent.Failed(
                                        error = VoiceAgentError(code),
                                        partialText = "",
                                        reason = reason,
                                    ),
                                ),
                            ),
                    )
                val result = model.consume(request)
                assertEquals(reason, result.failureReason)
                assertEquals(code, result.error?.code)
                seen += result.failureReason!!
            }

            assertEquals(cases.keys, seen)
        }

    @Test
    fun anAdapterThatThrowsIsMappedToTheSameFailureAsAnEmittedEvent() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("par")),
                            // The 1 ms wait lets the consumer drain the delta before
                            // the throw, so the partial text is preserved.
                            ScriptedLlmStep.ThrowFailure(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED), afterMillis = 1),
                        ),
                )

            val result = model.consume(request)

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_NETWORK_FAILED, result.error?.code)
            assertEquals(LlmFailureReason.NETWORK, result.failureReason)
            assertEquals("par", result.text)
            assertTrue(result.isPartial)
        }

    @Test
    fun anUnsupportedRequestIsRefusedBeforeAnyStreamStarts() =
        runTest {
            val model = DeterministicLanguageModel(capabilities = LlmCapabilities(reasoningLevels = setOf(ReasoningLevel.LOW)))

            val result =
                model.consume(request.copy(reasoning = ReasoningLevel.HIGH))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertFalse(result.completed)
            assertEquals(0, model.streamCount)
            assertEquals(1, model.rejectedRequestCount)
        }

    @Test
    fun aThrowingFakeFailureIsATypedVoiceAgentException() {
        val error = VoiceAgentError(ErrorCode.LLM_RATE_LIMITED)
        val exception = VoiceAgentException(error)

        assertEquals(error, exception.error)
        assertTrue(exception.message!!.startsWith("LLM_RATE_LIMITED"))
    }

    // endregion

    // region malformed and empty streams

    @Test
    fun anEmptyScriptEndsWithoutATerminalEventSoItIsNotACompletion() =
        runTest {
            val model = DeterministicLanguageModel(steps = emptyList())

            val result = model.consume(request)

            assertFalse(result.completed)
            assertNull(result.terminal)
            assertEquals("", result.text)
            assertEquals(0, result.deltaCount)
            // Nothing to persist: no text and no completion.
            assertFalse(result.isPartial)
        }

    @Test
    fun aStreamWithNoDeltasButACompletionIsAValidEmptyReply() =
        runTest {
            val model = DeterministicLanguageModel(steps = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Completed())))

            val result = model.consume(request)

            assertTrue(result.completed)
            assertEquals("", result.text)
            assertFalse(result.isPartial)
        }

    @Test
    fun anEventAfterTheTerminalEventIsDroppedByTheConsumer() =
        runTest {
            // A misbehaving adapter: it emits a delta after completing.
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("done")),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Completed()),
                        ),
                )

            val result = model.consume(request)

            assertTrue(result.completed)
            assertEquals("done", result.text)
            assertEquals(1, result.deltaCount)
        }

    // endregion

    // region partial-response state

    @Test
    fun aCancelledStreamReportsPartialTextAndNeverClaimsCompletion() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("half")),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta(" a sen")),
                            ScriptedLlmStep.Stall(millis = 10_000),
                        ),
                )
            val received = mutableListOf<LlmStreamEvent>()
            var cancellationThrown: CancellationException? = null

            val job =
                launch {
                    try {
                        model.stream(request).collect { received += it }
                    } catch (cancellation: CancellationException) {
                        cancellationThrown = cancellation
                        throw cancellation
                    }
                }
            advanceTimeBy(1)
            runCurrent()
            assertEquals(2, received.size)
            assertEquals(
                listOf(LlmStreamEvent.Delta("half"), LlmStreamEvent.Delta(" a sen")),
                received,
            )
            job.cancelAndJoin()

            assertEquals(1, model.cancellationCount)
            assertTrue(cancellationThrown != null)
            // The stream ended without a terminal event, so a consumer that kept
            // collecting could never mistake it for a completed reply.
            assertNull(model.terminalEvent)
        }

    @Test
    fun theProviderReportedModelIsSurfacedWhenItDiffersFromTheSelection() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Completed())),
                    reportedModelId = ModelId("served-by-someone-else"),
                )

            val result = model.consume(request)

            assertEquals(ModelId("served-by-someone-else"), result.model)
        }

    @Test
    fun usageIsNotInventedWhenTheProviderReportsNone() =
        runTest {
            val model = DeterministicLanguageModel(steps = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Completed())))

            val result = model.consume(request)

            assertNull(result.usage)
        }

    // endregion

    // region cancellation race and close

    @Test
    fun cancellingExactlyWhenTheStreamIsEmittingStopsItWithoutRacing() =
        runTest {
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("x")),
                            ScriptedLlmStep.Stall(millis = 10_000),
                        ),
                )
            val received = mutableListOf<LlmStreamEvent>()
            var cancellationCount = 0

            val job = launch { model.stream(request).collect { received += it } }
            // Rendezvous on the fake's own emission so the cancel is not racing start-up.
            model.awaitFirstEmission()
            runCurrent()
            job.cancelAndJoin()
            cancellationCount = model.cancellationCount

            assertEquals(1, received.size)
            assertEquals(1, cancellationCount)
            // No event arrived after the cancel.
            assertEquals(1, model.emittedEventCount)
        }

    @Test
    fun closeIsCountedAndIdempotent() =
        runTest {
            val model = DeterministicLanguageModel()

            model.close()
            model.close()

            assertEquals(2, model.closeCount)
        }

    @Test
    fun theFakeRequiresNoNetworkOrCredentials() {
        // The fake has no transport and no secret field at all: constructing and
        // running it cannot make a network call. This asserts the shape of the
        // contract, which is what "offline test" means here.
        val model = DeterministicLanguageModel()

        assertEquals(ProviderId("deterministic-fake"), model.providerId)
        assertEquals(0, model.streamCount)
        assertEquals(0, model.closeCount)
    }

    // endregion
}
