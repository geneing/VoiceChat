package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the M04 acceptance criterion that one trace stays correlated across a
 * fake streamed turn, a cancellation, and an error.
 *
 * The test drives the [com.voicechat.agent.contracts.LanguageModel] contract and
 * records each stream event through [TurnTraceRecorder], mirroring how M21
 * orchestration will call it. Trace timing comes from [FakeMonotonicClock], so
 * every duration asserted here is exact and independent of real elapsed time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnTraceCorrelationTest {
    private val selection = ProviderModelSelection(ProviderId("opencode-zen"), ModelId("zen-chat-1"))
    private val turnId = TurnId("turn-1")
    private val fixedTraceId = TraceId("trace-fixed")

    private fun request() =
        LlmRequest(
            model = selection,
            messages = listOf(LlmMessage(role = LlmRole.USER, content = "hello")),
        )

    private fun newRecorder(
        clock: FakeMonotonicClock,
        sink: RecordingDiagnosticsSink,
    ): TurnTraceRecorder =
        TurnTraceRecorder(
            traceId = fixedTraceId,
            turnId = turnId,
            clock = clock,
            sink = sink,
            providerId = selection.providerId.value,
            modelId = selection.modelId.value,
            runtime = "REMOTE_API",
        )

    /**
     * Records one streamed response exactly as orchestration would: the trace
     * clock advances between deltas so the inter-delta gap is observable.
     */
    private fun recordStreamEvent(
        recorder: TurnTraceRecorder,
        span: TraceSpan,
        clock: FakeMonotonicClock,
        event: LlmStreamEvent,
    ) {
        when (event) {
            is LlmStreamEvent.Delta -> {
                clock.advanceMillis(10L)
                recorder.llmDelta(receivedAtNanos = clock.nanoTime(), characterCount = event.text.length)
            }

            is LlmStreamEvent.Completed -> {
                recorder.requestState("completed")
                span.succeed()
                recorder.turnCompleted()
            }

            is LlmStreamEvent.Cancelled -> {
                recorder.requestState("cancelled")
                span.cancel()
                recorder.turnEnded(DiagnosticOutcome.CANCELLED)
            }

            is LlmStreamEvent.Failed -> {
                recorder.error(DiagnosticStage.LLM_REQUEST, event.error.code.name)
                span.fail()
                recorder.turnEnded(DiagnosticOutcome.FAILED)
            }
        }
    }

    @Test
    fun streamedTurnKeepsEveryEventOnOneTrace() =
        runTest {
            val clock = FakeMonotonicClock()
            val sink = RecordingDiagnosticsSink()
            val trace = newRecorder(clock, sink)
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script =
                        listOf(
                            LlmStreamEvent.Delta("Hel"),
                            LlmStreamEvent.Delta("lo"),
                            LlmStreamEvent.Completed(),
                        ),
                )

            val span = trace.start(DiagnosticStage.LLM_REQUEST)
            trace.requestSelected(selection.providerId.value, selection.modelId.value)
            trace.markStreamStarted()
            model.stream(request()).collect { recordStreamEvent(trace, span, clock, it) }

            assertTrue(sink.isSingleTrace)
            assertEquals(fixedTraceId, sink.traceIds.single())
            assertTrue(sink.events.all { it.turnId == turnId })
            assertTrue(sink.events.all { it.attributes[DiagnosticAttribute.PROVIDER_ID] == "opencode-zen" })
            assertTrue(sink.events.all { it.attributes[DiagnosticAttribute.MODEL_ID] == "zen-chat-1" })
            assertTrue(sink.events.any { it.attributes[DiagnosticAttribute.REQUEST_STATE] == "selected" })
            assertTrue(sink.events.any { it.attributes[DiagnosticAttribute.REQUEST_STATE] == "streaming" })
            assertTrue(sink.events.any { it.outcome == DiagnosticOutcome.COMPLETED })
            assertEquals(2, trace.summary().llmDeltaCount)
        }

    @Test
    fun firstDeltaRecordsTimeToFirstTextAndInterDeltaGap() =
        runTest {
            val clock = FakeMonotonicClock()
            val sink = RecordingDiagnosticsSink()
            val trace = newRecorder(clock, sink)
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("a"), LlmStreamEvent.Delta("bb"), LlmStreamEvent.Completed()),
                )

            val span = trace.start(DiagnosticStage.LLM_REQUEST)
            // A request that took 5 ms to produce its first delta.
            clock.advanceMillis(5L)
            trace.markStreamStarted()
            model.stream(request()).collect { recordStreamEvent(trace, span, clock, it) }

            val deltas =
                sink.events.filter {
                    it.stage == DiagnosticStage.LLM_REQUEST &&
                        it.attributes[DiagnosticAttribute.DELTA_INDEX] != null
                }
            assertEquals(2, deltas.size)
            assertEquals("0", deltas[0].attributes[DiagnosticAttribute.DELTA_INDEX])
            assertEquals("first-text", deltas[0].attributes[DiagnosticAttribute.STREAM_STATE])
            assertEquals("1", deltas[1].attributes[DiagnosticAttribute.DELTA_INDEX])
            // First delta: duration is time-to-first-text from the request start.
            assertEquals(10L * NANOS_PER_MILLI, deltas[0].durationNanos)
            // Later deltas: duration is the gap to the previous delta.
            assertEquals(10L * NANOS_PER_MILLI, deltas[1].durationNanos)
            assertEquals("2", deltas[1].attributes[DiagnosticAttribute.CHARACTER_COUNT])
            assertEquals(15L, trace.summary().firstTextMillis)
        }

    @Test
    fun cancellingATurnRecordsACorrelatedCancellation() =
        runTest {
            val clock = FakeMonotonicClock()
            val sink = RecordingDiagnosticsSink()
            val trace = newRecorder(clock, sink)
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("partial"), LlmStreamEvent.Completed()),
                    eventDelayMillis = 1_000L,
                )

            val span = trace.start(DiagnosticStage.LLM_REQUEST)
            trace.markStreamStarted()
            val job =
                launch {
                    model.stream(request()).collect { recordStreamEvent(trace, span, clock, it) }
                }
            advanceTimeBy(1_500L)
            job.cancelAndJoin()
            // runTest's virtual clock does not advance the trace clock, so cancel
            // and end the turn explicitly the way orchestration does on barge-in.
            trace.cancelled(DiagnosticStage.LLM_REQUEST, mapOf(DiagnosticAttribute.REQUEST_STATE to "cancelled"))
            trace.turnEnded(DiagnosticOutcome.CANCELLED)

            assertTrue(sink.isSingleTrace)
            assertEquals(1, model.cancellationCount)
            assertTrue(sink.events.any { it.outcome == DiagnosticOutcome.CANCELLED })
            assertTrue(
                "cancellation must carry the request state",
                sink.events.any {
                    it.outcome == DiagnosticOutcome.CANCELLED &&
                        it.attributes[DiagnosticAttribute.REQUEST_STATE] == "cancelled"
                },
            )
            assertTrue(sink.events.last().outcome == DiagnosticOutcome.CANCELLED)
        }

    @Test
    fun aFailedStreamRecordsATypedErrorOnTheSameTrace() =
        runTest {
            val clock = FakeMonotonicClock()
            val sink = RecordingDiagnosticsSink()
            val trace = newRecorder(clock, sink)
            val error = VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED, detail = "socket closed")
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("par"), LlmStreamEvent.Failed(error, partialText = "par")),
                )

            val span = trace.start(DiagnosticStage.LLM_REQUEST)
            trace.markStreamStarted()
            model.stream(request()).collect { recordStreamEvent(trace, span, clock, it) }

            assertTrue(sink.isSingleTrace)
            val failure = sink.events.single { it.attributes[DiagnosticAttribute.ERROR_CODE] != null }
            assertEquals(ErrorCode.LLM_NETWORK_FAILED.name, failure.attributes[DiagnosticAttribute.ERROR_CODE])
            assertEquals(DiagnosticStage.LLM_REQUEST, failure.stage)
            // The provider detail must never be copied into the trace.
            assertTrue(sink.events.none { it.attributes.containsValue("socket closed") })
            assertTrue(sink.events.none { it.attributes.containsValue("par") })
            assertTrue(sink.events.last().outcome == DiagnosticOutcome.FAILED)
        }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
