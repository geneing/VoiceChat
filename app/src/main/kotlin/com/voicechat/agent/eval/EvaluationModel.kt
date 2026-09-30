package com.voicechat.agent.eval

import com.voicechat.agent.voice.BargeInTiming

/**
 * Configuration metadata every M25 measurement must carry.
 *
 * The voice-quality plan forbids a performance claim without recording the
 * device, OS, route, model/runtime, network, and fixture that produced it
 * (`docs/voice-quality-and-latency.md`, `AGENTS.md`). This value type is that
 * metadata in one place so a latency/quality report can never be read without it.
 *
 * Fields are strings, not enums, on purpose: an unmeasured dimension is written
 * as `"unmeasured"` rather than silently omitted, and the harness carries the
 * exact labels a run used.
 */
data class MeasurementConditions(
    /** Device and build, e.g. `Pixel 10 (Android 17, CP2A.…, debug)`. */
    val device: String,
    /** Input/output route, e.g. `built-in mic / built-in speaker`. */
    val route: String,
    /** Locale used for STT/TTS, e.g. `en-US`. */
    val locale: String,
    /** Replay fixture or live-corpus label. */
    val fixture: String,
    /** LLM provider id, when the measurement includes a turn. */
    val provider: String? = null,
    /** LLM model id, when known. */
    val model: String? = null,
    /** Reasoning/thinking level sent, when the provider supports one. */
    val reasoning: String? = null,
    /** Network conditions, e.g. `home wifi` or `unmeasured`. */
    val network: String? = null,
    /** Any other condition worth recording (thermal, battery, notes). */
    val notes: String = "",
) {
    /** One-line human summary; `null` fields read as `unmeasured`. */
    fun summarize(): String =
        buildString {
            append("device=$device")
            append(", route=$route")
            append(", locale=$locale")
            append(", fixture=$fixture")
            append(", provider=${provider ?: "unmeasured"}")
            append(", model=${model ?: "unmeasured"}")
            append(", reasoning=${reasoning ?: "unmeasured"}")
            append(", network=${network ?: "unmeasured"}")
            if (notes.isNotBlank()) append(", notes=$notes")
        }
}

/**
 * The stage/end-to-end latencies M25 measures.
 *
 * Each is a monotonic duration in nanoseconds. The set mirrors the milestone's
 * "speech end to first assistant text, first audible TTS, completion, and user
 * speech onset to playback stop/cancel acknowledgement" handoff.
 */
enum class LatencyMetric(
    /** Stable id for reports. */
    val id: String,
) {
    /** Final speech end → first assistant text token. */
    SPEECH_END_TO_FIRST_TEXT("speech_end_to_first_text"),

    /** Final speech end → first audible TTS. */
    SPEECH_END_TO_FIRST_AUDIO("speech_end_to_first_audio"),

    /** Final speech end → assistant completion. */
    SPEECH_END_TO_COMPLETION("speech_end_to_completion"),

    /** Barge-in speech onset → playback stop issued (what the user perceives). */
    ONSET_TO_PLAYBACK_STOP("onset_to_playback_stop"),

    /** Barge-in speech onset → next capture/recognition started. */
    ONSET_TO_CAPTURE_RESUMED("onset_to_capture_resumed"),

    /** Barge-in speech onset → interrupted work settled (cancel acknowledgement). */
    ONSET_TO_SETTLED("onset_to_settled"),

    /** True logical turn end → VAD/endpoint decision (endpoint delay). */
    ENDPOINT_DELAY("endpoint_delay"),
}

/** One measured duration for one metric. */
data class LatencySample(
    val metric: LatencyMetric,
    val durationNanos: Long,
) {
    init {
        require(durationNanos >= 0L) { "durationNanos must not be negative, was $durationNanos" }
    }

    /** Convenience for feeding millisecond-measured values. */
    companion object {
        fun ofMillis(
            metric: LatencyMetric,
            millis: Long,
        ): LatencySample = LatencySample(metric, millis * NANOS_PER_MILLI)

        const val NANOS_PER_MILLI: Long = 1_000_000L
    }
}

/**
 * p50/p90/p95/tail summary for one metric.
 *
 * Percentiles use the nearest-rank method on the ascending sample list, so the
 * reported value is always one of the observed durations (no interpolation
 * invents a number that was never seen). This is deliberate for a small,
 * hand-recorded evaluation corpus.
 */
data class LatencySummary(
    val count: Int,
    val minNanos: Long,
    val p50Nanos: Long,
    val p90Nanos: Long,
    val p95Nanos: Long,
    val maxNanos: Long,
) {
    val minMillis: Double get() = minNanos.toDouble() / NANOS_PER_MILLI
    val p50Millis: Double get() = p50Nanos.toDouble() / NANOS_PER_MILLI
    val p90Millis: Double get() = p90Nanos.toDouble() / NANOS_PER_MILLI
    val p95Millis: Double get() = p95Nanos.toDouble() / NANOS_PER_MILLI
    val maxMillis: Double get() = maxNanos.toDouble() / NANOS_PER_MILLI

    companion object {
        private const val NANOS_PER_MILLI = 1_000_000.0

        /** @return the summary, or `null` when [samples] is empty (no fabricated rows). */
        fun of(samples: List<Long>): LatencySummary? {
            if (samples.isEmpty()) return null
            val sorted = samples.sorted()
            return LatencySummary(
                count = sorted.size,
                minNanos = sorted.first(),
                p50Nanos = Percentiles.nearestRank(sorted, 50.0),
                p90Nanos = Percentiles.nearestRank(sorted, 90.0),
                p95Nanos = Percentiles.nearestRank(sorted, 95.0),
                maxNanos = sorted.last(),
            )
        }
    }
}

/** Nearest-rank percentile over an ascending list. */
internal object Percentiles {
    fun nearestRank(
        sortedAscending: List<Long>,
        percentile: Double,
    ): Long {
        require(sortedAscending.isNotEmpty()) { "percentile requires at least one sample" }
        require(percentile in 0.0..100.0) { "percentile must be within [0, 100], was $percentile" }
        val rank = kotlin.math.ceil(percentile / 100.0 * sortedAscending.size).toInt()
        val index = (rank - 1).coerceIn(0, sortedAscending.lastIndex)
        return sortedAscending[index]
    }
}

/**
 * Latency samples grouped by metric, with the conditions they were measured
 * under. [byMetric] keeps only the metrics that actually have samples, so an
 * absent measurement is absent rather than a zero.
 */
class LatencyReport(
    val samples: List<LatencySample>,
    val conditions: MeasurementConditions,
) {
    init {
        require(samples.isNotEmpty()) { "a latency report needs at least one sample" }
    }

    /** Per-metric summaries, keyed in [LatencyMetric] declaration order. */
    fun byMetric(): Map<LatencyMetric, LatencySummary> {
        val grouped = samples.groupBy { it.metric }
        return buildMap {
            for (metric in LatencyMetric.entries) {
                grouped[metric]?.let { rows ->
                    LatencySummary.of(rows.map { it.durationNanos })?.let { put(metric, it) }
                }
            }
        }
    }

    /** One markdown table row per measured metric. */
    fun toMarkdown(): String =
        buildString {
            append("Conditions: ").append(conditions.summarize()).append('\n').append('\n')
            append("| metric | n | p50 ms | p90 ms | p95 ms | max ms |\n")
            append("| --- | --- | --- | --- | --- | --- |\n")
            for ((metric, summary) in byMetric()) {
                append("| ").append(metric.id).append(" | ").append(summary.count)
                append(" | ").append(format(summary.p50Millis))
                append(" | ").append(format(summary.p90Millis))
                append(" | ").append(format(summary.p95Millis))
                append(" | ").append(format(summary.maxMillis))
                append(" |\n")
            }
        }

    /** Latency samples derived from the barge-in timings the M24 loop already records. */
    companion object {
        /** Builds the barge-in latency metrics from recorded [BargeInTiming]s. */
        fun fromBargeIns(
            timings: List<BargeInTiming>,
            conditions: MeasurementConditions,
        ): LatencyReport {
            val samples =
                buildList {
                    for (timing in timings) {
                        add(LatencySample(LatencyMetric.ONSET_TO_PLAYBACK_STOP, timing.onsetToStopNanos))
                        timing.onsetToCaptureResumedNanos?.let {
                            add(LatencySample(LatencyMetric.ONSET_TO_CAPTURE_RESUMED, it))
                        }
                        timing.onsetToSettledNanos?.let {
                            add(LatencySample(LatencyMetric.ONSET_TO_SETTLED, it))
                        }
                    }
                }
            require(samples.isNotEmpty()) { "no barge-in timings carried a measurable duration" }
            return LatencyReport(samples, conditions)
        }
    }
}

private fun format(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
