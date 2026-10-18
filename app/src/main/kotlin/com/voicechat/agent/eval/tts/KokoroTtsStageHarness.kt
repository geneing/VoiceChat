package com.voicechat.agent.eval.tts

import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.eval.LatencyMetric
import com.voicechat.agent.eval.LatencyReport
import com.voicechat.agent.eval.LatencySample
import com.voicechat.agent.eval.MeasurementConditions
import com.voicechat.agent.tts.kokoro.KokoroArtifact
import com.voicechat.agent.tts.kokoro.KokoroPhonemizer
import com.voicechat.agent.tts.kokoro.KokoroSynthesisPipeline
import com.voicechat.agent.tts.kokoro.KokoroSynthesizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * What one measured Kokoro synthesis attempt produced.
 *
 * Every duration is monotonic and measured from the same instant — the start of
 * the text, just before phonemization — so the numbers are directly comparable
 * and can be read as a breakdown of one user-visible quantity:
 * [timeToFirstPcmMillis] is **text start to first sound**, before the audio
 * device is involved, and [timeToFirstAudioMillis] is the same instant measured
 * end to end through playback by [VoiceTtsHarness].
 *
 * [audioMillis] is how much speech was produced, so [realtimeFactor] answers
 * "does this voice synthesize faster than it speaks" on the machine that ran it.
 */
data class KokoroTtsStageRun(
    val text: String,
    val characters: Int,
    /** Phoneme chunks the utterance was planned as; 0 when the text has no phonemes. */
    val phonemeChunks: Int,
    /** Text start → phoneme chunks planned. */
    val phonemizeMillis: Long?,
    /** Text start → the first PCM piece is in memory (the synthesis side of time-to-first-audio). */
    val timeToFirstPcmMillis: Long?,
    /** Text start → every PCM piece is in memory. */
    val synthesisCompleteMillis: Long?,
    /** Duration of the synthesized speech. */
    val audioMillis: Long?,
    val failure: String?,
) {
    /** Synthesized audio per unit of synthesis time; `null` when either side is unmeasured. */
    val realtimeFactor: Double?
        get() {
            val audio = audioMillis ?: return null
            val synthesis = synthesisCompleteMillis ?: return null
            if (synthesis <= 0L) return null
            return audio.toDouble() / synthesis.toDouble()
        }
}

/** Every attempt for one [text], with the medians the voice decision is made on. */
class KokoroTtsStageCaseResult(
    val text: String,
    val runs: List<KokoroTtsStageRun>,
) {
    /** Median text start → first PCM over the attempts that produced it. */
    val medianTimeToFirstPcmMillis: Long? get() = runs.mapNotNull { it.timeToFirstPcmMillis }.median()

    /** Median text start → all PCM over the attempts that produced it. */
    val medianSynthesisCompleteMillis: Long? get() = runs.mapNotNull { it.synthesisCompleteMillis }.median()

    /** Median synthesized speech duration over the attempts that produced audio. */
    val medianAudioMillis: Long? get() = runs.mapNotNull { it.audioMillis }.median()

    /** Median synthesized audio per unit of synthesis time, over the attempts that measured both. */
    val medianRealtimeFactor: Double? get() = runs.mapNotNull { it.realtimeFactor }.medianDouble()

    /** True when every attempt planned at least one phoneme chunk and produced audio. */
    val allProducedAudio: Boolean get() = runs.all { it.phonemeChunks > 0 && it.audioMillis != null && it.failure == null }

    /** The distinct failure descriptions, for the record. */
    val failures: List<String> get() = runs.mapNotNull { it.failure }.distinct()

    private fun List<Long>.median(): Long? =
        if (isEmpty()) {
            null
        } else {
            val sorted = sorted()
            if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }
        }

    private fun List<Double>.medianDouble(): Double? =
        if (isEmpty()) {
            null
        } else {
            val sorted = sorted()
            if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
            }
        }
}

/**
 * The measured synthesis set, rendered as the markdown record the harness writes.
 *
 * [latency] carries the aggregated `tts_phonemize`/`tts_first_pcm`/
 * `tts_synthesis_complete` metrics, or `null` when no attempt produced a
 * measurable duration (an unmeasured stage is absent rather than a fabricated
 * zero).
 */
class KokoroTtsStageReport(
    val conditions: MeasurementConditions,
    val results: List<KokoroTtsStageCaseResult>,
    val latency: LatencyReport?,
    val recordedAt: String,
) {
    /** The summary table: medians per text, with the number of attempts. */
    fun summaryMarkdown(): String {
        val header =
            "| Text | Attempts | Chunks | First PCM median (ms) | Complete median (ms) | Audio median (ms) | Realtime x | Result |"
        val rule = "| --- | --- | --- | --- | --- | --- | --- | --- |"
        val rows =
            results.map { result ->
                val outcome =
                    if (result.allProducedAudio) {
                        "synthesized"
                    } else {
                        "FAILED (${result.failures.joinToString(separator = "; ")})"
                    }
                "| ${escape(result.text)} | ${result.runs.size} | ${result.runs.firstOrNull()?.phonemeChunks ?: 0} | " +
                    "${result.medianTimeToFirstPcmMillis.ms()} | ${result.medianSynthesisCompleteMillis.ms()} | " +
                    "${result.medianAudioMillis.ms()} | ${result.medianRealtimeFactor.x()} | $outcome |"
            }
        return (listOf(header, rule) + rows).joinToString(separator = "\n")
    }

    /** The whole record: method, conditions, the aggregated latency, and the per-text rows. */
    fun toMarkdown(): String =
        buildString {
            appendLine("# Kokoro TTS Latency Harness Run — $recordedAt")
            appendLine()
            appendLine("Generated by `com.voicechat.agent.eval.tts.KokoroTtsStageHarness`, which drives the")
            appendLine("M28 distilled-Kokoro phonemizer and ONNX graph directly and measures **text start to")
            appendLine("first sound**: `tts_first_pcm` is everything that must happen before the audio sink")
            appendLine("can be handed its first buffer (phonemize + encode + one graph call), and")
            appendLine("`tts_synthesis_complete` is the whole utterance. `tts_first_audible` from the M25")
            appendLine("TTS harness is the same instant measured through real playback, so the difference")
            appendLine("between the two is the audio device's own start cost.")
            appendLine()
            appendLine("Durations are monotonic and measured from one instant per attempt, so the rows are")
            appendLine("a breakdown rather than three unrelated numbers. A host run measures the port; only")
            appendLine("a device run is a device claim (`docs/evaluation.md`).")
            appendLine()
            appendLine("## Conditions")
            appendLine()
            appendLine(conditions.summarize())
            appendLine()
            appendLine("## Summary")
            appendLine()
            appendLine(summaryMarkdown())
            appendLine()
            appendLine("## Latency")
            appendLine()
            appendLine(latency?.toMarkdown() ?: "No attempt produced a measurable duration.")
            appendLine()
            appendLine("## Attempts")
            appendLine()
            results.forEach { result ->
                appendLine("### ${escape(result.text)}")
                appendLine()
                result.runs.forEachIndexed { index, run ->
                    appendLine(
                        "- Attempt ${index + 1}: ${run.phonemeChunks} chunks, phonemize ${run.phonemizeMillis.ms()} ms, " +
                            "first PCM ${run.timeToFirstPcmMillis.ms()} ms, complete ${run.synthesisCompleteMillis.ms()} ms, " +
                            "audio ${run.audioMillis.ms()} ms, realtime ${run.realtimeFactor.x()}, " +
                            "${run.characters} chars" + (run.failure?.let { ", failure=$it" } ?: ""),
                    )
                }
                appendLine()
            }
        }

    private fun Long?.ms(): String = this?.toString() ?: "-"

    private fun Double?.x(): String = this?.let { String.format(java.util.Locale.US, "%.2f", it) } ?: "-"

    private fun escape(text: String): String = text.replace("|", "\\|").replace("\n", " ")
}

/**
 * The M28 synthesis-stage harness: phonemizes and synthesizes the same fixed
 * texts [VoiceTtsHarness] speaks, [repeats] times each, and records the stage
 * timings that make up time-to-first-audio.
 *
 * It is deliberately independent of the audio device: it answers "how long until
 * there is something to play", which is the part a synthesis or phonemizer
 * change moves, and which can be measured on a host with a fake graph or on a
 * device with the real one. The audible half of the same question is
 * [VoiceTtsHarness]'s `tts_first_audible`, and the two are meant to be run
 * together (see `KokoroTtsHarnessInstrumentedTest`).
 *
 * It never asserts on the audio content; only timing, counts, and the typed
 * outcome. Synthesis is blocking CPU work, so it runs on [synthesisDispatcher].
 */
class KokoroTtsStageHarness(
    private val phonemizer: KokoroPhonemizer,
    private val synthesizer: KokoroSynthesizer,
    private val conditions: MeasurementConditions,
    private val now: () -> String,
    private val speed: Float = KokoroArtifact.DEFAULT_SPEED,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val synthesisDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val pipeline = KokoroSynthesisPipeline(phonemizer = phonemizer, synthesizer = synthesizer, speed = speed)

    /** Runs every [texts] entry [repeats] times and returns the record. */
    suspend fun run(
        texts: List<String> = VoiceTtsHarnessCases.TTS_TEXTS,
        repeats: Int = VoiceTtsHarness.DEFAULT_REPEATS,
    ): KokoroTtsStageReport {
        require(texts.isNotEmpty()) { "the Kokoro latency harness needs at least one text" }
        require(repeats >= 1) { "repeats must be at least 1" }
        val results =
            texts.map { text ->
                KokoroTtsStageCaseResult(text = text, runs = (1..repeats).map { runAttempt(text) })
            }
        val samples =
            buildList {
                results.forEach { result ->
                    result.runs.forEach { run ->
                        run.phonemizeMillis?.let { add(LatencySample.ofMillis(LatencyMetric.TTS_PHONEMIZE, it)) }
                        run.timeToFirstPcmMillis?.let { add(LatencySample.ofMillis(LatencyMetric.TTS_FIRST_PCM, it)) }
                        run.synthesisCompleteMillis?.let {
                            add(LatencySample.ofMillis(LatencyMetric.TTS_SYNTHESIS_COMPLETE, it))
                        }
                    }
                }
            }
        return KokoroTtsStageReport(
            conditions = conditions,
            results = results,
            latency = if (samples.isEmpty()) null else LatencyReport(samples, conditions),
            recordedAt = now(),
        )
    }

    private suspend fun runAttempt(text: String): KokoroTtsStageRun {
        val startedAt = clock.nanoTime()
        var chunks = 0
        var phonemize: Long? = null
        var firstPcm: Long? = null
        var complete: Long? = null
        var audio: Long? = null
        var failure: String? = null
        try {
            val planned = withContext(synthesisDispatcher) { phonemizer.chunks(text) }
            phonemize = elapsedMillis(startedAt)
            chunks = planned.size
            if (planned.isEmpty()) {
                failure = "no phonemes"
            } else {
                var samples = 0
                withContext(synthesisDispatcher) {
                    planned.forEachIndexed { index, chunk ->
                        samples += pipeline.synthesize(chunk).samples.size
                        if (index == 0) firstPcm = elapsedMillis(startedAt)
                    }
                }
                complete = elapsedMillis(startedAt)
                audio = samples.toLong() * MILLIS_PER_SECOND / KokoroArtifact.SAMPLE_RATE_HZ
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (thrown: Throwable) {
            // A thrown graph/adapter error is still a measured outcome, not a
            // reason to lose the other attempts.
            failure = "threw ${thrown.javaClass.simpleName}"
        }
        return KokoroTtsStageRun(
            text = text,
            characters = text.length,
            phonemeChunks = chunks,
            phonemizeMillis = phonemize,
            timeToFirstPcmMillis = firstPcm,
            synthesisCompleteMillis = complete,
            audioMillis = audio,
            failure = failure,
        )
    }

    private fun elapsedMillis(startedAtNanos: Long): Long = (clock.nanoTime() - startedAtNanos) / NANOS_PER_MILLI

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000L
        const val MILLIS_PER_SECOND: Long = 1_000L
    }
}
