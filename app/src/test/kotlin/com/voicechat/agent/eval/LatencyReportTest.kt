package com.voicechat.agent.eval

import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.voice.BargeInTiming
import com.voicechat.agent.voice.VoiceInterruptionRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M25 latency aggregation: nearest-rank percentiles on observed samples, grouped
 * by metric, with the measurement conditions attached so a number is never read
 * without its configuration.
 */
class LatencyReportTest {
    @Test
    fun nearestRankReturnsAnObservedValue() {
        val sorted = (1L..100L).toList()

        assertEquals(1L, Percentiles.nearestRank(sorted, 0.0))
        assertEquals(50L, Percentiles.nearestRank(sorted, 50.0))
        assertEquals(90L, Percentiles.nearestRank(sorted, 90.0))
        assertEquals(95L, Percentiles.nearestRank(sorted, 95.0))
        assertEquals(100L, Percentiles.nearestRank(sorted, 100.0))
    }

    @Test
    fun anEmptySampleSetHasNoSummary() {
        assertNull(LatencySummary.of(emptyList()))
    }

    @Test
    fun summariesGroupByMetricAndSkipAbsentMetrics() {
        val report =
            LatencyReport(
                samples =
                    listOf(
                        LatencySample.ofMillis(LatencyMetric.SPEECH_END_TO_FIRST_TEXT, 100),
                        LatencySample.ofMillis(LatencyMetric.SPEECH_END_TO_FIRST_TEXT, 300),
                        LatencySample.ofMillis(LatencyMetric.ONSET_TO_PLAYBACK_STOP, 25),
                    ),
                conditions = conditions(),
            )

        val byMetric = report.byMetric()
        assertEquals(setOf(LatencyMetric.SPEECH_END_TO_FIRST_TEXT, LatencyMetric.ONSET_TO_PLAYBACK_STOP), byMetric.keys)
        assertEquals(2, byMetric.getValue(LatencyMetric.SPEECH_END_TO_FIRST_TEXT).count)
        assertEquals(100.0, byMetric.getValue(LatencyMetric.SPEECH_END_TO_FIRST_TEXT).minMillis, 1e-9)
        assertTrue(LatencyMetric.SPEECH_END_TO_COMPLETION !in byMetric)
    }

    @Test
    fun bargeInTimingsProduceTheThreeBargeInMetrics() {
        val report =
            LatencyReport.fromBargeIns(
                timings =
                    listOf(
                        BargeInTiming(
                            interruptedTurnId = TurnId("t1"),
                            onsetAtNanos = 0,
                            stopIssuedAtNanos = 30_000_000,
                            captureResumedAtNanos = 60_000_000,
                            settledAtNanos = 120_000_000,
                            recovery = VoiceInterruptionRecovery.COMMITTED,
                        ),
                    ),
                conditions = conditions(),
            )

        val byMetric = report.byMetric()
        assertEquals(30.0, byMetric.getValue(LatencyMetric.ONSET_TO_PLAYBACK_STOP).p50Millis, 1e-9)
        assertEquals(60.0, byMetric.getValue(LatencyMetric.ONSET_TO_CAPTURE_RESUMED).p50Millis, 1e-9)
        assertEquals(120.0, byMetric.getValue(LatencyMetric.ONSET_TO_SETTLED).p50Millis, 1e-9)
    }

    @Test
    fun markdownCarriesTheConditions() {
        val report =
            LatencyReport(
                samples = listOf(LatencySample.ofMillis(LatencyMetric.ENDPOINT_DELAY, 280)),
                conditions = conditions(),
            )

        val markdown = report.toMarkdown()
        assertTrue(markdown.contains("device=Pixel 10"))
        assertTrue(markdown.contains("endpoint_delay"))
    }

    private fun conditions() =
        MeasurementConditions(
            device = "Pixel 10",
            route = "built-in mic / built-in speaker",
            locale = "en-US",
            fixture = "replay:fixture-A",
        )
}
