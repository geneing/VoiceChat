package com.voicechat.agent.eval.tts

import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.eval.LatencyMetric
import com.voicechat.agent.eval.MeasurementConditions
import com.voicechat.agent.tts.kokoro.KokoroArtifact
import com.voicechat.agent.tts.kokoro.KokoroPhonemeChunk
import com.voicechat.agent.tts.kokoro.KokoroPhonemizer
import com.voicechat.agent.tts.kokoro.KokoroSynthesizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The M28 synthesis-stage harness on the JVM: it must produce a truthful,
 * attributable breakdown of time-to-first-audio without a device, a real graph,
 * or a real phonemizer.
 *
 * The point of these tests is not the fake's timings but the harness's honesty:
 * a stage that did not happen is reported as absent, an error is a recorded
 * outcome rather than a lost attempt, and the metrics it feeds M25 are the
 * stages it actually measured.
 */
class KokoroTtsStageHarnessTest {
    @Test
    fun `reports each stage as its own metric`() =
        runTest {
            val report =
                harness(
                    phonemizer = FakePhonemizer(chunksPerText = 3),
                    synthesizer = FakeSynthesizer(samplesPerChunk = SAMPLES_PER_CHUNK),
                ).run(listOf("Hello there."), repeats = 1)

            assertEquals(
                1,
                report.results
                    .single()
                    .runs.size,
            )
            assertNotNull(report.latency)
            val metrics = requireNotNull(report.latency).samples.map { it.metric }.toSet()
            assertEquals(
                setOf(LatencyMetric.TTS_PHONEMIZE, LatencyMetric.TTS_FIRST_PCM, LatencyMetric.TTS_SYNTHESIS_COMPLETE),
                metrics,
            )
        }

    @Test
    fun `first pcm is measured before the last chunk is synthesized`() =
        runTest {
            // The fake clock advances one millisecond per read, so the harness's
            // three stage reads must be strictly increasing and the first-PCM
            // read must come before the completion read: that ordering is the
            // whole claim ("first sound" is not "the whole utterance").
            val report =
                harness(
                    phonemizer = FakePhonemizer(chunksPerText = 4),
                    synthesizer = FakeSynthesizer(samplesPerChunk = SAMPLES_PER_CHUNK),
                ).run(listOf("A sentence long enough to need several chunks."), repeats = 1)

            val run =
                report.results
                    .single()
                    .runs
                    .single()
            assertEquals(4, run.phonemeChunks)
            assertEquals(1L, run.phonemizeMillis)
            assertEquals(2L, run.timeToFirstPcmMillis)
            assertEquals(3L, run.synthesisCompleteMillis)
            assertTrue(
                "first PCM (${run.timeToFirstPcmMillis}) must precede completion (${run.synthesisCompleteMillis})",
                requireNotNull(run.timeToFirstPcmMillis) < requireNotNull(run.synthesisCompleteMillis),
            )
        }

    @Test
    fun `audio duration and realtime factor come from the synthesized samples`() =
        runTest {
            val report =
                harness(
                    phonemizer = FakePhonemizer(chunksPerText = 2),
                    synthesizer = FakeSynthesizer(samplesPerChunk = SAMPLES_PER_CHUNK),
                ).run(listOf("Two chunks."), repeats = 1)

            val run =
                report.results
                    .single()
                    .runs
                    .single()
            // 2 chunks x 24000 samples is two seconds of speech at 24 kHz.
            assertEquals(2_000L, run.audioMillis)
            // 2000 ms of speech produced in 3 ms of measured synthesis.
            assertEquals(2000.0 / 3.0, requireNotNull(run.realtimeFactor), 1e-9)
            assertEquals(requireNotNull(run.realtimeFactor), requireNotNull(report.results.single().medianRealtimeFactor), 1e-9)
        }

    @Test
    fun `text with no phonemes reports no fabricated durations`() =
        runTest {
            val report =
                harness(
                    phonemizer = FakePhonemizer(chunksPerText = 0),
                    synthesizer = FakeSynthesizer(samplesPerChunk = SAMPLES_PER_CHUNK),
                ).run(listOf("…"), repeats = 1)

            val run =
                report.results
                    .single()
                    .runs
                    .single()
            assertEquals(0, run.phonemeChunks)
            assertNull(run.timeToFirstPcmMillis)
            assertNull(run.synthesisCompleteMillis)
            assertNull(run.audioMillis)
            assertEquals("no phonemes", run.failure)
            // Phonemization did happen, so only that stage is reported.
            assertEquals(
                setOf(LatencyMetric.TTS_PHONEMIZE),
                requireNotNull(report.latency).samples.map { it.metric }.toSet(),
            )
        }

    @Test
    fun `a throwing synthesizer is a recorded outcome, not a lost attempt`() =
        runTest {
            val report =
                harness(
                    phonemizer = FakePhonemizer(chunksPerText = 1),
                    synthesizer = FakeSynthesizer(samplesPerChunk = SAMPLES_PER_CHUNK, alwaysFail = true),
                ).run(listOf("Boom."), repeats = 2)

            val result = report.results.single()
            assertEquals(2, result.runs.size)
            assertEquals(listOf("threw IllegalStateException"), result.failures)
            assertTrue(result.runs.none { it.timeToFirstPcmMillis != null })
            assertEquals(0, requireNotNull(report.latency).samples.count { it.metric == LatencyMetric.TTS_FIRST_PCM })
        }

    @Test
    fun `the record carries the conditions and the per-text table`() =
        runTest {
            val conditions =
                MeasurementConditions(
                    device = "host JVM",
                    route = "none (synthesis only)",
                    locale = "en-US",
                    fixture = "kokoro-latency",
                    model = KokoroArtifact.AF_HEART.id,
                )
            val report =
                harness(
                    phonemizer = FakePhonemizer(chunksPerText = 1),
                    synthesizer = FakeSynthesizer(samplesPerChunk = SAMPLES_PER_CHUNK),
                    conditions = conditions,
                ).run(listOf("Read me."), repeats = 1)

            val markdown = report.toMarkdown()
            assertTrue(markdown.contains("tts_first_pcm"))
            assertTrue(markdown.contains("Read me."))
            assertTrue(markdown.contains(conditions.summarize()))
            assertTrue(markdown.contains("| Text | Attempts | Chunks |"))
        }

    private fun harness(
        phonemizer: KokoroPhonemizer,
        synthesizer: KokoroSynthesizer,
        conditions: MeasurementConditions = HOST_CONDITIONS,
    ): KokoroTtsStageHarness =
        KokoroTtsStageHarness(
            phonemizer = phonemizer,
            synthesizer = synthesizer,
            conditions = conditions,
            now = { "2026-10-17 12:00" },
            clock = SteppingClock(),
            // Keep the measurement deterministic and on the test thread: the
            // harness's own dispatcher choice is not what these tests assert.
            synthesisDispatcher = Dispatchers.Unconfined,
        )

    /** A clock that advances one millisecond per read, so stage durations are exact. */
    private class SteppingClock : MonotonicClock {
        private var reads: Long = 0

        override fun nanoTime(): Long = reads++ * NANOS_PER_MILLI

        private companion object {
            const val NANOS_PER_MILLI: Long = 1_000_000L
        }
    }

    /** A phonemizer that plans a fixed number of chunks and needs no lexicon. */
    private class FakePhonemizer(
        private val chunksPerText: Int,
    ) : KokoroPhonemizer {
        override fun phonemes(text: String): String = "fəˈniːmz"

        override fun chunks(
            text: String,
            maxPhonemes: Int,
        ): List<KokoroPhonemeChunk> =
            (0 until chunksPerText).map { index ->
                KokoroPhonemeChunk(phonemes = "tʃʌŋk", sourceText = if (index == 0) text else "")
            }

        override fun encode(phonemes: String): IntArray = intArrayOf(0, 1, 2, 0)
    }

    /** A synthesizer that returns fixed-length samples and can fail on every call. */
    private class FakeSynthesizer(
        private val samplesPerChunk: Int,
        private val alwaysFail: Boolean = false,
    ) : KokoroSynthesizer {
        override fun synthesize(
            ids: IntArray,
            speed: Float,
        ): FloatArray {
            if (alwaysFail) error("synthetic graph failure")
            return FloatArray(samplesPerChunk) { 0.25f }
        }

        override fun close() = Unit
    }

    private companion object {
        const val SAMPLES_PER_CHUNK: Int = KokoroArtifact.SAMPLE_RATE_HZ

        val HOST_CONDITIONS =
            MeasurementConditions(
                device = "host JVM",
                route = "none (synthesis only)",
                locale = "en-US",
                fixture = "kokoro-latency",
                model = KokoroArtifact.AF_HEART.id,
            )
    }
}
