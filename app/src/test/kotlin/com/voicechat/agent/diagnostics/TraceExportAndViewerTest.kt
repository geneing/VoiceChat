package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Covers the local developer trace viewer and the deterministic text export. */
class TraceExportAndViewerTest {
    private val traceId = TraceId("trace-1")
    private val turnId = TurnId("turn-1")

    private val events =
        listOf(
            DiagnosticEvent(
                stage = DiagnosticStage.TURN,
                outcome = DiagnosticOutcome.STARTED,
                monotonicTimeNanos = 1_000_000L,
                turnId = turnId,
                traceId = traceId,
            ),
            DiagnosticEvent(
                stage = DiagnosticStage.LLM_REQUEST,
                outcome = DiagnosticOutcome.PROGRESS,
                monotonicTimeNanos = 2_000_000L,
                turnId = turnId,
                traceId = traceId,
                durationNanos = 1_000_000L,
                attributes =
                    mapOf(
                        DiagnosticAttribute.STREAM_STATE to "first-text",
                        DiagnosticAttribute.PROVIDER_ID to "openai",
                    ),
            ),
            DiagnosticEvent(
                stage = DiagnosticStage.TURN,
                outcome = DiagnosticOutcome.COMPLETED,
                monotonicTimeNanos = 5_000_000L,
                turnId = turnId,
                traceId = traceId,
            ),
        )

    @Test
    fun exportIsOneJsonObjectPerLineWithStableIdentityAndTiming() {
        val exported = TraceJsonExporter.export(events)
        val lines = exported.trimEnd('\n').lines()

        assertEquals(3, lines.size)
        lines.forEach { line ->
            assertTrue(line.startsWith("{"))
            assertTrue(line.endsWith("}"))
            assertTrue(line.contains("\"traceId\":\"trace-1\""))
            assertTrue(line.contains("\"turnId\":\"turn-1\""))
        }
        assertTrue(lines[0].contains("\"stage\":\"TURN\""))
        assertTrue(lines[0].contains("\"monotonicTimeNanos\":1000000"))
        assertTrue(lines[1].contains("\"STREAM_STATE\":\"first-text\""))
        assertTrue(lines[1].contains("\"durationNanos\":1000000"))
    }

    @Test
    fun exportIsDeterministicAcrossCalls() {
        assertEquals(TraceJsonExporter.export(events), TraceJsonExporter.export(events))
    }

    @Test
    fun exportOfNothingIsEmpty() {
        assertEquals("", TraceJsonExporter.export(emptyList()))
    }

    @Test
    fun viewerLinesAreRelativeToTheEarliestRetainedEvent() {
        val lines = TraceViewer.lines(events)

        assertEquals(listOf(0L, 1L, 4L), lines.map { it.elapsedMillis })
        assertEquals("LLM_REQUEST", lines[1].stage)
        assertEquals(1L, lines[1].durationMillis)
        assertEquals("trace-1", lines[1].traceId)
    }

    @Test
    fun renderedViewerKeepsStageOrderAndAttributes() {
        val rendered = TraceViewer.render(TraceViewer.lines(events))
        val lines = rendered.lines()

        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("+0ms TURN STARTED"))
        assertTrue(lines[1].contains("LLM_REQUEST PROGRESS (1ms)"))
        assertTrue(lines[1].contains("PROVIDER_ID=openai"))
        assertTrue(lines[1].contains("STREAM_STATE=first-text"))
        assertTrue(lines[2].contains("TURN COMPLETED"))
    }

    @Test
    fun viewerRendersNothingForAnEmptyTrace() {
        assertEquals("", TraceViewer.render(TraceViewer.lines(emptyList())))
    }

    @Test
    fun exportedTextNeverCarriesFreeFormValues() {
        // Event attribute values are enum keys with identifier/count values, so
        // even a deliberately odd value stays inside the closed attribute set.
        val suspicious =
            listOf(
                DiagnosticEvent(
                    stage = DiagnosticStage.LLM_REQUEST,
                    outcome = DiagnosticOutcome.FAILED,
                    monotonicTimeNanos = 0L,
                    traceId = traceId,
                    attributes = mapOf(DiagnosticAttribute.ERROR_CODE to "LLM_AUTHENTICATION_FAILED"),
                ),
            )

        val exported = TraceJsonExporter.export(suspicious)
        assertTrue(exported.contains("\"ERROR_CODE\":\"LLM_AUTHENTICATION_FAILED\""))
        assertFalse(exported.contains("Bearer "))
        assertFalse(exported.contains("sk-"))
    }
}
