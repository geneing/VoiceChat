package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the M04 acceptance criteria for timing and for the non-blocking,
 * observable trace path:
 *
 * - durations come from an injected monotonic clock and are exact;
 * - `record` never suspends even when the buffer is full;
 * - a full buffer drops events but exposes the drop count;
 * - the store evicts oldest events and reports the eviction count.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TraceTimingAndBufferTest {
    private fun event(
        atNanos: Long,
        stage: DiagnosticStage = DiagnosticStage.LLM_REQUEST,
        outcome: DiagnosticOutcome = DiagnosticOutcome.PROGRESS,
    ) = DiagnosticEvent(stage = stage, outcome = outcome, monotonicTimeNanos = atNanos, traceId = TraceId("trace-1"))

    @Test
    fun spanDurationComesFromTheInjectedMonotonicClock() {
        val clock = FakeMonotonicClock(nanos = 1_000_000L)
        val sink = RecordingDiagnosticsSink()
        val recorder = newRecorder(clock, sink)

        val span = recorder.start(DiagnosticStage.SPEECH_TO_TEXT)
        clock.advanceMillis(250L)
        span.succeed()

        val started = sink.events.first { it.outcome == DiagnosticOutcome.STARTED }
        val completed = sink.events.first { it.outcome == DiagnosticOutcome.COMPLETED }
        assertEquals(250L * NANOS_PER_MILLI, completed.durationNanos)
        assertEquals(0L, started.durationNanos ?: 0L)
        assertTrue(completed.monotonicTimeNanos > started.monotonicTimeNanos)
    }

    @Test
    fun spanFinishesOnlyOnceEvenIfTerminalOutcomesRace() {
        val clock = FakeMonotonicClock()
        val sink = RecordingDiagnosticsSink()
        val recorder = newRecorder(clock, sink)

        val span = recorder.start(DiagnosticStage.TTS_PLAYBACK)
        clock.advanceMillis(10L)
        span.succeed()
        clock.advanceMillis(999L)
        span.cancel()
        span.fail()

        val terminal = sink.events.filter { it.stage == DiagnosticStage.TTS_PLAYBACK && it.outcome != DiagnosticOutcome.STARTED }
        assertEquals(1, terminal.size)
        assertEquals(DiagnosticOutcome.COMPLETED, terminal.single().outcome)
        assertEquals(10L * NANOS_PER_MILLI, terminal.single().durationNanos)
    }

    @Test
    fun bargeInStopLatencyIsOnsetToStop() {
        val clock = FakeMonotonicClock(nanos = 5_000_000L)
        val sink = RecordingDiagnosticsSink()
        val recorder = newRecorder(clock, sink)

        val onset = clock.nanoTime()
        clock.advanceMillis(40L)
        recorder.playbackStopped(onsetAtNanos = onset, stoppedAtNanos = clock.nanoTime())

        val stopped = sink.events.single { it.outcome == DiagnosticOutcome.CANCELLED }
        assertEquals(40L * NANOS_PER_MILLI, stopped.durationNanos)
        assertEquals("true", stopped.attributes[DiagnosticAttribute.BARGE_IN])
        assertEquals(40L, recorder.summary().bargeInStopMillis)
    }

    @Test
    fun fullBufferDropsWithoutBlockingAndCountsTheDrops() =
        runTest {
            // A downstream that never yields: the drain coroutine cannot run.
            val stalled =
                object : DiagnosticsSink {
                    override fun record(event: DiagnosticEvent) = Unit
                }
            val sink = BoundedDiagnosticsSink(scope = backgroundScope, capacity = 4, downstream = stalled)

            // Fill the buffer and overflow it. None of these calls may suspend,
            // and the drop becomes visible as a count instead of being silent.
            repeat(100) { index ->
                sink.record(event(atNanos = index.toLong()))
            }

            assertEquals(96L, sink.droppedEventCount.value)
        }

    @Test
    fun bufferedEventsReachTheDownstreamWhenItIsGivenATurn() =
        runTest {
            val collecting = RecordingDiagnosticsSink()
            val sink = BoundedDiagnosticsSink(scope = backgroundScope, capacity = 8, downstream = collecting)

            repeat(3) { index -> sink.record(event(atNanos = index.toLong())) }
            // Let the drain coroutine start and consume the buffered events.
            testScheduler.advanceUntilIdle()
            testScheduler.runCurrent()

            assertEquals(3, collecting.events.size)
            assertEquals(listOf(0L, 1L, 2L), collecting.events.map { it.monotonicTimeNanos })
            assertEquals(0L, sink.droppedEventCount.value)
        }

    @Test
    fun inMemoryStoreEvictsOldestAndReportsEvictionCount() {
        val store = InMemoryTraceStore(maxEvents = 3)

        repeat(5) { index -> store.record(event(atNanos = index.toLong())) }

        val snapshot = store.snapshot()
        assertEquals(3, snapshot.size)
        assertEquals(listOf(2L, 3L, 4L), snapshot.map { it.monotonicTimeNanos })
        assertEquals(2L, store.evictedEventCount)

        store.clear()
        assertTrue(store.snapshot().isEmpty())
    }

    @Test
    fun storeFiltersByTraceAndRevisionAdvances() {
        val store = InMemoryTraceStore()
        val traceA = TraceId("a")
        val traceB = TraceId("b")

        store.record(event(1L).copy(traceId = traceA))
        store.record(event(2L).copy(traceId = traceB))
        store.record(event(3L).copy(traceId = traceA))

        assertEquals(listOf(1L, 3L), store.byTrace(traceA).map { it.monotonicTimeNanos })
        assertEquals(listOf(2L), store.byTrace(traceB).map { it.monotonicTimeNanos })
        assertEquals(3L, store.revision.value)
    }

    private fun newRecorder(
        clock: FakeMonotonicClock,
        sink: DiagnosticsSink,
    ): TurnTraceRecorder =
        TurnTraceRecorder(
            traceId = TraceId("trace-1"),
            turnId = TurnId("turn-1"),
            clock = clock,
            sink = sink,
        )

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
