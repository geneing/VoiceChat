package com.voicechat.agent.audio

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Verifies capture diagnostics stay on the M04 seam and carry no sample content. */
class AudioCaptureDiagnosticsTest {
    @Test
    fun emitsTheFormatSourceRouteLevelsAndCounts() {
        val sink = RecordingDiagnosticsSink()
        val diagnostics =
            AudioCaptureDiagnostics(
                sink = sink,
                clock = FakeMonotonicClock(nanos = 1_000L),
                traceId = TraceId("trace-1"),
                turnId = TurnId("turn-1"),
            )
        val levels = CaptureLevelAccumulator().apply { add(shortArrayOf(Short.MAX_VALUE, 0)) }

        diagnostics.started(AudioFormat.MONO_16_KHZ, "VOICE_RECOGNITION", AudioRoute(AudioRouteType.BUILTIN_MIC))
        diagnostics.routeChanged(AudioRoute(AudioRouteType.BLUETOOTH_SCO))
        diagnostics.routeChanged(AudioRoute(AudioRouteType.BLUETOOTH_SCO))
        diagnostics.progress(frameCount = 10, droppedFrames = 2, levels = levels)
        diagnostics.stopped(frameCount = 10, droppedFrames = 2, levels = levels)
        diagnostics.failed(ErrorCode.AUDIO_CAPTURE_FAILED, "VOICE_RECOGNITION")

        val started = sink.events.first { it.outcome == DiagnosticOutcome.STARTED }
        assertEquals(DiagnosticStage.AUDIO_INPUT, started.stage)
        assertEquals("VOICE_RECOGNITION", started.attributes[DiagnosticAttribute.AUDIO_SOURCE])
        assertEquals("16000Hz/mono/16bit", started.attributes[DiagnosticAttribute.AUDIO_FORMAT])
        assertEquals("BUILTIN_MIC", started.attributes[DiagnosticAttribute.AUDIO_ROUTE])
        assertEquals("trace-1", started.traceId?.value)
        assertEquals("turn-1", started.turnId?.value)

        val routeChanges =
            sink.events
                .filter { it.outcome == DiagnosticOutcome.PROGRESS && it.attributes[DiagnosticAttribute.AUDIO_ROUTE] != null }
                .map { it.attributes[DiagnosticAttribute.AUDIO_ROUTE] }
        assertEquals(listOf("BLUETOOTH_SCO"), routeChanges)

        val progress =
            sink.events.first {
                it.outcome == DiagnosticOutcome.PROGRESS &&
                    it.attributes[DiagnosticAttribute.AUDIO_PEAK_LEVEL] != null
            }
        assertTrue(progress.attributes[DiagnosticAttribute.AUDIO_PEAK_LEVEL]!!.toFloat() > 0.99f)
        assertEquals("2", progress.attributes[DiagnosticAttribute.AUDIO_DROPPED_FRAMES])
        assertEquals("10", progress.attributes[DiagnosticAttribute.FRAME_COUNT])
        assertEquals(1L, progress.attributes[DiagnosticAttribute.AUDIO_CLIPPED_SAMPLES]?.toLong())

        val failed = sink.events.first { it.outcome == DiagnosticOutcome.FAILED }
        assertEquals(ErrorCode.AUDIO_CAPTURE_FAILED.name, failed.attributes[DiagnosticAttribute.ERROR_CODE])
    }
}
