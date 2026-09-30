package com.voicechat.agent.eval

import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.vad.BoundedTurnEndpointPolicy
import com.voicechat.agent.vad.TurnDetectionEvent
import com.voicechat.agent.vad.VadConfig
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull

/**
 * One labeled endpoint case for the M25 threshold sweep.
 *
 * [frames] is the replayed or synthesized capture timeline; [trueEndOffsetMillis]
 * is the labeled true end of the logical turn on that same timeline. The policy
 * under test never sees the label — it only sees [frames] — so the comparison is
 * a real false-commit/false-hold measurement rather than a mirror of the policy.
 */
data class LabeledEndpointCase(
    val name: String,
    val frames: List<AudioFrame>,
    val trueEndOffsetMillis: Long,
)

/**
 * Outcome of running one [VadConfig] over an endpoint corpus.
 *
 * - **false commit** — the policy ended the turn more than
 *   [EndpointSweep.falseCommitToleranceMillis] *before* the labeled true end, so
 *   the user was cut off mid-thought. With a semantic detector this is a wrong
 *   `COMPLETE`; with the bounded policy it is a too-short silence cap.
 * - **false hold** — the policy stayed open more than the hold tolerance *after*
 *   the true end, so the reply felt late. With a semantic detector this is a
 *   wrong `INCOMPLETE`; with the bounded policy it is a long silence cap.
 * - **miss** — no endpoint was produced at all (should not happen: the bounded
 *   policy always emits exactly one endpoint).
 *
 * Delays are signed (negative = early). Averages are over all cases that produced
 * an endpoint; a miss contributes no delay.
 */
data class EndpointAccuracy(
    val cases: Int,
    val accurate: Int,
    val falseCommits: Int,
    val falseHolds: Int,
    val misses: Int,
    val meanSignedDelayMillis: Double,
    val meanAbsoluteDelayMillis: Double,
    val p95AbsoluteDelayMillis: Double,
) {
    val falseCommitRate: Double get() = rate(falseCommits)
    val falseHoldRate: Double get() = rate(falseHolds)
    val missRate: Double get() = rate(misses)

    private fun rate(count: Int): Double = if (cases == 0) 0.0 else count.toDouble() / cases
}

/** One swept configuration and its measured accuracy. */
data class EndpointSweepRow(
    val config: VadConfig,
    val accuracy: EndpointAccuracy,
)

/**
 * The M25 endpoint threshold sweep.
 *
 * It runs the real production [BoundedTurnEndpointPolicy] over each labeled case
 * for each candidate [VadConfig], which is exactly the "sweep VAD thresholds and
 * silence cap; compare false commits, false holds, and endpoint latency" handoff.
 * It is pure Kotlin and JVM-reproducible from the frozen fixtures; a Pixel 10 run
 * supplies the labeled audio and records the same rows.
 *
 * The tolerances are the acceptance window, not tuned defaults: they encode "an
 * endpoint around the true end is correct". They are configurable so a report can
 * state the exact window a run used.
 */
class EndpointSweep(
    private val falseCommitToleranceMillis: Long = DEFAULT_FALSE_COMMIT_TOLERANCE_MILLIS,
    private val falseHoldToleranceMillis: Long = DEFAULT_FALSE_HOLD_TOLERANCE_MILLIS,
) {
    init {
        require(falseCommitToleranceMillis >= 0) { "falseCommitToleranceMillis must not be negative" }
        require(falseHoldToleranceMillis >= 0) { "falseHoldToleranceMillis must not be negative" }
    }

    /** @return the endpoint offset the policy chose for one case, or `null` if it never ended. */
    suspend fun endpointOffsetMillis(
        case: LabeledEndpointCase,
        config: VadConfig,
    ): Long? {
        val policy = BoundedTurnEndpointPolicy(config = config)
        return policy
            .observe(case.frames.asFlow())
            .filterIsInstance<TurnDetectionEvent.Endpointed>()
            .firstOrNull()
            ?.atOffsetMillis
    }

    /** Runs one configuration over every case. */
    suspend fun evaluate(
        cases: List<LabeledEndpointCase>,
        config: VadConfig,
    ): EndpointAccuracy {
        require(cases.isNotEmpty()) { "the endpoint sweep needs at least one case" }
        var accurate = 0
        var falseCommits = 0
        var falseHolds = 0
        var misses = 0
        val signedDelays = mutableListOf<Double>()
        val absoluteDelays = mutableListOf<Double>()
        for (case in cases) {
            val endpoint = endpointOffsetMillis(case, config)
            if (endpoint == null) {
                misses++
                continue
            }
            val delay = endpoint - case.trueEndOffsetMillis
            signedDelays += delay.toDouble()
            absoluteDelays += kotlin.math.abs(delay).toDouble()
            when {
                delay < -falseCommitToleranceMillis -> falseCommits++
                delay > falseHoldToleranceMillis -> falseHolds++
                else -> accurate++
            }
        }
        val absolute = LatencySummary.of(absoluteDelays.map { (it * NANOS_PER_MILLI).toLong() })
        return EndpointAccuracy(
            cases = cases.size,
            accurate = accurate,
            falseCommits = falseCommits,
            falseHolds = falseHolds,
            misses = misses,
            meanSignedDelayMillis = signedDelays.averageOrZero(),
            meanAbsoluteDelayMillis = absoluteDelays.averageOrZero(),
            p95AbsoluteDelayMillis = absolute?.p95Millis ?: 0.0,
        )
    }

    /** Runs every configuration over the same cases, in the order given. */
    suspend fun sweep(
        cases: List<LabeledEndpointCase>,
        configs: List<VadConfig>,
    ): List<EndpointSweepRow> = configs.map { EndpointSweepRow(it, evaluate(cases, it)) }

    companion object {
        const val DEFAULT_FALSE_COMMIT_TOLERANCE_MILLIS: Long = 200L
        const val DEFAULT_FALSE_HOLD_TOLERANCE_MILLIS: Long = 800L
        private const val NANOS_PER_MILLI = 1_000_000.0
    }
}

/** Sweep rows as a markdown table, for pasting into the evaluation record. */
fun List<EndpointSweepRow>.toMarkdown(): String =
    buildString {
        append(
            "| onsetRms | pauseFrames | maxSilence ms | accurate | false commit | false hold | miss | mean delay ms | p95 |delay| ms |\n",
        )
        append("| --- | --- | --- | --- | --- | --- | --- | --- | --- |\n")
        for (row in this@toMarkdown) {
            append("| ").append(row.config.onsetRmsThreshold)
            append(" | ").append(row.config.pauseFrames)
            append(" | ").append(row.config.maxSilenceMillis)
            append(" | ").append(row.accuracy.accurate)
            append(" | ").append(row.accuracy.falseCommits)
            append(" | ").append(row.accuracy.falseHolds)
            append(" | ").append(row.accuracy.misses)
            append(" | ").append(formatMillis(row.accuracy.meanSignedDelayMillis))
            append(" | ").append(formatMillis(row.accuracy.p95AbsoluteDelayMillis))
            append(" |\n")
        }
    }

private fun List<Double>.averageOrZero(): Double = if (isEmpty()) 0.0 else average()

private fun formatMillis(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)
