package com.voicechat.agent.eval.tts

import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.eval.LatencyMetric
import com.voicechat.agent.eval.MeasurementConditions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host half of the TTS-stage harness (M25).
 *
 * It drives the same [VoiceTtsHarness] the device test runs, but with a
 * deterministic, clock-advancing `TextToSpeech` so the aggregation, metric names,
 * and failure handling are proven on the JVM with no device and no audio. A real
 * engine's timings must still come from the device harness, never from here.
 */
class VoiceTtsHarnessTest {
    private val conditions =
        MeasurementConditions(
            device = "jvm",
            route = "none",
            locale = "en-US",
            fixture = "tts-harness",
        )

    @Test
    fun theHarnessRecordsFirstAudibleAndDeliveryPerAttempt() =
        runTest {
            val clock = AdvancingClock()
            val tts = AdvancingTextToSpeech(clock, firstAudibleMillis = 120, deliveredMillis = 900)
            val harness = VoiceTtsHarness(tts, conditions, now = { "test" }, clock = clock)

            val report = harness.run(listOf("Bring me the news."), repeats = 2)

            assertNotNull("a completing run must produce latency samples", report.latency)
            val byMetric = report.latency!!.byMetric()
            assertEquals(120.0, byMetric.getValue(LatencyMetric.TTS_FIRST_AUDIBLE).p50Millis, 1e-9)
            assertEquals(900.0, byMetric.getValue(LatencyMetric.TTS_COMPLETION).p50Millis, 1e-9)
            assertEquals(2, byMetric.getValue(LatencyMetric.TTS_FIRST_AUDIBLE).count)
            assertTrue(report.results.single().allCompleted)
            assertEquals(2, tts.spokenTexts.size)
        }

    @Test
    fun aFailingEngineIsARecordedOutcomeWithoutFabricatedLatency() =
        runTest {
            val clock = AdvancingClock()
            val tts = AdvancingTextToSpeech(clock, firstAudibleMillis = 10, deliveredMillis = 20, fail = true)
            val harness = VoiceTtsHarness(tts, conditions, now = { "test" }, clock = clock)

            val report = harness.run(listOf("This will fail."), repeats = 1)

            assertNull("no attempt produced a duration, so no latency report", report.latency)
            val result = report.results.single()
            assertTrue("a failed attempt must not read as delivered", !result.allCompleted)
            assertEquals(listOf(ErrorCode.TTS_SYNTHESIS_FAILED.name), result.failures)
            assertTrue(report.summaryMarkdown().contains("FAILED"))
        }

    @Test
    fun theRecordCarriesItsConditionsAndPerAttemptRows() =
        runTest {
            val clock = AdvancingClock()
            val tts = AdvancingTextToSpeech(clock, firstAudibleMillis = 5, deliveredMillis = 500)
            val harness = VoiceTtsHarness(tts, conditions, now = { "2026-10-17" }, clock = clock)

            val record = harness.run(listOf("One.", "Two."), repeats = 1).toMarkdown()

            assertTrue(record.contains("2026-10-17"))
            assertTrue(record.contains("device=jvm"))
            assertTrue(record.contains("tts_first_audible"))
            assertTrue(record.contains("Attempt 1"))
        }

    @Test(expected = IllegalArgumentException::class)
    fun anEmptyTextListIsRefused() =
        runTest {
            val clock = AdvancingClock()
            VoiceTtsHarness(AdvancingTextToSpeech(clock, 1, 2), conditions, now = { "test" }, clock = clock).run(emptyList())
        }
}

/** A monotonic clock the fake engine advances explicitly, so durations are exact. */
private class AdvancingClock(
    private var nanos: Long = 0L,
) : MonotonicClock {
    override fun nanoTime(): Long = nanos

    fun advance(millis: Long) {
        require(millis >= 0L) { "millis must not be negative" }
        nanos += millis * NANOS_PER_MILLI
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/**
 * A deterministic [TextToSpeech] that advances the shared clock before it emits
 * each event, so the harness reads the exact fake "first audible" and "delivered"
 * durations. It never plays audio.
 */
private class AdvancingTextToSpeech(
    private val clock: AdvancingClock,
    private val firstAudibleMillis: Long,
    private val deliveredMillis: Long,
    private val fail: Boolean = false,
) : TextToSpeech {
    val spokenTexts: MutableList<String> = mutableListOf()

    override fun speak(
        text: String,
        utteranceId: UtteranceId,
    ): Flow<TtsEvent> =
        flow {
            spokenTexts += text
            emit(TtsEvent.Queued(utteranceId, text))
            if (fail) {
                emit(TtsEvent.Failed(VoiceAgentError(ErrorCode.TTS_SYNTHESIS_FAILED)))
                return@flow
            }
            clock.advance(firstAudibleMillis)
            emit(TtsEvent.Started(utteranceId, text))
            clock.advance(deliveredMillis - firstAudibleMillis)
            emit(TtsEvent.Delivered(utteranceId, text))
        }

    override suspend fun stop() = Unit

    override suspend fun close() = Unit
}
