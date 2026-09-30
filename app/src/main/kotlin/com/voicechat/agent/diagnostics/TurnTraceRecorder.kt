package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId

/**
 * A long-running span (a pipeline stage) that emits a `STARTED` event on
 * creation and exactly one terminal event when [succeed], [fail], or [cancel]
 * is called. Its [startedAtNanos] and the terminal event's `durationNanos` both
 * come from the recorder's monotonic clock.
 */
class TraceSpan internal constructor(
    private val recorder: TurnTraceRecorder,
    val stage: DiagnosticStage,
    private val baseAttributes: Map<DiagnosticAttribute, String>,
) {
    /** Monotonic time the span started. */
    val startedAtNanos: Long = recorder.clockNanos()

    private var finished = false

    /** Completes the span with [DiagnosticOutcome.COMPLETED]. */
    fun succeed(attributes: Map<DiagnosticAttribute, String> = emptyMap()) = finish(DiagnosticOutcome.COMPLETED, attributes)

    /** Completes the span with [DiagnosticOutcome.FAILED]. */
    fun fail(attributes: Map<DiagnosticAttribute, String> = emptyMap()) = finish(DiagnosticOutcome.FAILED, attributes)

    /** Completes the span with [DiagnosticOutcome.CANCELLED]. */
    fun cancel(attributes: Map<DiagnosticAttribute, String> = emptyMap()) = finish(DiagnosticOutcome.CANCELLED, attributes)

    private fun finish(
        outcome: DiagnosticOutcome,
        attributes: Map<DiagnosticAttribute, String>,
    ) {
        if (finished) return
        finished = true
        recorder.emitStage(
            stage = stage,
            outcome = outcome,
            durationNanos = recorder.clockNanos() - startedAtNanos,
            attributes = baseAttributes + attributes,
        )
    }
}

/** Plain per-turn counters that a trace viewer shows alongside the event stream. */
data class TurnTraceSummary(
    val monotonicStartNanos: Long,
    val monotonicEndNanos: Long?,
    val llmDeltaCount: Int,
    val firstTextMillis: Long?,
    val firstAudibleMillis: Long?,
    val bargeInStopMillis: Long?,
)

/**
 * Records the privacy-safe events for one conversation turn.
 *
 * Every event carries the same [traceId] (and optionally [turnId]), so a
 * streamed turn, its cancellation, and its failure remain correlated even
 * though they arrive on different stages. All events go to the injected
 * [DiagnosticsSink]; by default that is a [BoundedDiagnosticsSink] whose
 * `record` never blocks the caller (see `docs/turn-tracing.md`).
 *
 * The recorder holds the turn's start time, streamed-delta counters, and the
 * one-shot timing points needed by the latency plan. It is a single-consumer
 * object: the injected clock and sink are the only shared state.
 */
class TurnTraceRecorder(
    val traceId: TraceId,
    val turnId: TurnId?,
    private val clock: MonotonicClock,
    private val sink: DiagnosticsSink,
    private val providerId: String? = null,
    private val modelId: String? = null,
    private val runtime: String? = null,
    private val reasoningLevel: String? = null,
    private val engineId: String? = null,
) {
    private val startedAtNanos: Long = clock.nanoTime()
    private var llmDeltaCount: Int = 0
    private var lastDeltaAtNanos: Long? = null
    private var streamStartedAtNanos: Long? = null
    private var firstTextAtNanos: Long? = null
    private var firstAudibleAtNanos: Long? = null
    private var bargeInStopAtNanos: Long? = null
    private var bargeInOnsetAtNanos: Long? = null
    private var endedAtNanos: Long? = null

    /** The provider recorded on every event, if known at turn start. */
    val provider: String? get() = providerId

    /** The model recorded on every event, if known at turn start. */
    val model: String? get() = modelId

    /**
     * Begins a stage span and emits its `STARTED` event. Repeated calls for the
     * same stage are allowed; the viewer keeps them in order.
     */
    fun start(
        stage: DiagnosticStage,
        attributes: Map<DiagnosticAttribute, String> = emptyMap(),
    ): TraceSpan {
        val span = TraceSpan(this, stage, attributes)
        emitStage(stage, DiagnosticOutcome.STARTED, null, attributes)
        return span
    }

    /** Emits a `PROGRESS` event for [stage]. */
    fun progress(
        stage: DiagnosticStage,
        attributes: Map<DiagnosticAttribute, String> = emptyMap(),
    ) = emitStage(stage, DiagnosticOutcome.PROGRESS, null, attributes)

    /** Records which provider/model one request actually used.
     *
     * This is emitted from the resolved request, not just the selection, so the
     * trace shows the true origin even if orchestration passed a different
     * model than the recorder was constructed with.
     */
    fun requestSelected(
        providerId: String,
        modelId: String,
        reasoningLevel: String? = null,
    ) = progress(
        stage = DiagnosticStage.LLM_REQUEST,
        attributes =
            buildMap {
                put(DiagnosticAttribute.PROVIDER_ID, providerId)
                put(DiagnosticAttribute.MODEL_ID, modelId)
                put(DiagnosticAttribute.REQUEST_STATE, "selected")
                reasoningLevel?.let { put(DiagnosticAttribute.REASONING_LEVEL, it) }
            },
    )

    /**
     * Marks the moment the request actually started streaming.
     *
     * The first [llmDelta] then reports `durationNanos` as time-to-first-text
     * measured from this point (not from turn start), which is the latency the
     * product cares about. Call once per request, after [requestSelected].
     */
    fun markStreamStarted(atNanos: Long = clock.nanoTime()) {
        streamStartedAtNanos = atNanos
        progress(
            stage = DiagnosticStage.LLM_REQUEST,
            attributes = mapOf(DiagnosticAttribute.REQUEST_STATE to "streaming"),
        )
    }

    /** Records an explicit request-state transition (for example `streaming`, `cancelled`). */
    fun requestState(state: String) =
        progress(
            stage = DiagnosticStage.LLM_REQUEST,
            attributes = mapOf(DiagnosticAttribute.REQUEST_STATE to state),
        )

    /**
     * Records the typed reason one request/stream ended without completing
     * normally (a stable `LlmFailureReason` name, or `completed`).
     *
     * Only the reason name is recorded, never the adapter's detail string, so
     * provider text cannot reach the trace through this path.
     */
    fun requestEndReason(reason: String) =
        progress(
            stage = DiagnosticStage.LLM_REQUEST,
            attributes = mapOf(DiagnosticAttribute.REQUEST_END_REASON to reason),
        )

    /**
     * Records token usage the provider actually reported.
     *
     * A `null` field is omitted rather than written as `0`, so the trace never
     * claims a count the provider did not send. Only counts are recorded.
     */
    fun requestUsage(
        promptTokens: Int?,
        completionTokens: Int?,
        totalTokens: Int?,
    ) {
        val usage = usageSummary(promptTokens, completionTokens, totalTokens) ?: return
        progress(
            stage = DiagnosticStage.LLM_REQUEST,
            attributes = mapOf(DiagnosticAttribute.USAGE to usage),
        )
    }

    /**
     * Records one streamed assistant delta.
     *
     * The first delta reports `durationNanos` as time-to-first-text measured
     * from [markStreamStarted] (or from turn start if that was never called),
     * and is tagged `first-text`. Later deltas report the inter-delta gap. Only
     * counts and the delta index are recorded, never the text itself.
     */
    fun llmDelta(
        receivedAtNanos: Long,
        characterCount: Int,
    ) {
        val isFirst = llmDeltaCount == 0
        val referenceNanos = if (isFirst) (streamStartedAtNanos ?: startedAtNanos) else lastDeltaAtNanos!!
        if (isFirst) firstTextAtNanos = receivedAtNanos
        lastDeltaAtNanos = receivedAtNanos

        llmDeltaCount++
        emitStage(
            stage = DiagnosticStage.LLM_REQUEST,
            outcome = DiagnosticOutcome.PROGRESS,
            durationNanos = receivedAtNanos - referenceNanos,
            attributes =
                buildMap {
                    put(DiagnosticAttribute.STREAM_STATE, if (isFirst) "first-text" else "delta")
                    put(DiagnosticAttribute.DELTA_INDEX, (llmDeltaCount - 1).toString())
                    put(DiagnosticAttribute.CHARACTER_COUNT, characterCount.toString())
                },
            eventTimeNanos = receivedAtNanos,
        )
    }

    /** Records the audible playback start of an utterance; the first call fixes first-audible timing. */
    fun playbackStarted(receivedAtNanos: Long = clock.nanoTime()) {
        if (firstAudibleAtNanos == null) firstAudibleAtNanos = receivedAtNanos
        progress(
            stage = DiagnosticStage.TTS_PLAYBACK,
            attributes = mapOf(DiagnosticAttribute.STREAM_STATE to "first-audible"),
        )
    }

    /**
     * Records the audible playback stop that follows a detected barge-in.
     *
     * [onsetAtNanos] is when the interrupting speech was first observed;
     * [stoppedAtNanos] is when playback actually stopped. The event's
     * `durationNanos` is that stop/cancel latency; use [bargeIn] to record the
     * onset-to-acknowledgement transition on the turn stage as well.
     */
    fun playbackStopped(
        onsetAtNanos: Long,
        stoppedAtNanos: Long = clock.nanoTime(),
    ) {
        bargeInOnsetAtNanos = onsetAtNanos
        bargeInStopAtNanos = stoppedAtNanos
        emitStage(
            stage = DiagnosticStage.TTS_PLAYBACK,
            outcome = DiagnosticOutcome.CANCELLED,
            durationNanos = stoppedAtNanos - onsetAtNanos,
            attributes =
                mapOf(
                    DiagnosticAttribute.BARGE_IN to "true",
                    DiagnosticAttribute.STREAM_STATE to "stopped",
                ),
            eventTimeNanos = stoppedAtNanos,
        )
    }

    /** Records how much assistant text was actually delivered versus generated. */
    fun playbackDelivered(
        deliveredCharacterCount: Int,
        totalCharacterCount: Int,
        interrupted: Boolean,
    ) = progress(
        stage = DiagnosticStage.TTS_PLAYBACK,
        attributes =
            mapOf(
                DiagnosticAttribute.DELIVERED_CHARACTER_COUNT to deliveredCharacterCount.toString(),
                DiagnosticAttribute.TOTAL_CHARACTER_COUNT to totalCharacterCount.toString(),
                DiagnosticAttribute.BARGE_IN to interrupted.toString(),
                DiagnosticAttribute.STREAM_STATE to if (interrupted) "interrupted" else "completed",
            ),
    )

    /** Records a barge-in transition on the turn stage (onset + cancel acknowledgement). */
    fun bargeIn(
        onsetAtNanos: Long,
        acknowledgedAtNanos: Long,
        stageName: DiagnosticStage = DiagnosticStage.TURN,
    ) {
        bargeInOnsetAtNanos = onsetAtNanos
        emitStage(
            stage = stageName,
            outcome = DiagnosticOutcome.CANCELLED,
            durationNanos = acknowledgedAtNanos - onsetAtNanos,
            attributes = mapOf(DiagnosticAttribute.BARGE_IN to "true"),
            eventTimeNanos = acknowledgedAtNanos,
        )
    }

    /** Records a typed failure. Only the stable error code and category are kept; never its detail. */
    fun error(
        stage: DiagnosticStage,
        errorCode: String,
        attributes: Map<DiagnosticAttribute, String> = emptyMap(),
    ) = emitStage(
        stage = stage,
        outcome = DiagnosticOutcome.FAILED,
        durationNanos = null,
        attributes = attributes + mapOf(DiagnosticAttribute.ERROR_CODE to errorCode),
    )

    /** Records a cancellation that is not a barge-in (for example abandoning a turn). */
    fun cancelled(
        stage: DiagnosticStage,
        attributes: Map<DiagnosticAttribute, String> = emptyMap(),
    ) = emitStage(stage, DiagnosticOutcome.CANCELLED, null, attributes)

    /** Records that the turn itself completed. */
    fun turnCompleted() {
        endedAtNanos = clock.nanoTime()
        emitStage(DiagnosticStage.TURN, DiagnosticOutcome.COMPLETED, null, emptyMap())
    }

    /** Records that the turn ended without completing (cancelled or failed). */
    fun turnEnded(outcome: DiagnosticOutcome) {
        endedAtNanos = clock.nanoTime()
        emitStage(DiagnosticStage.TURN, outcome, null, emptyMap())
    }

    /** A snapshot of the turn's counters, for a viewer or a test assertion. */
    fun summary(): TurnTraceSummary =
        TurnTraceSummary(
            monotonicStartNanos = startedAtNanos,
            monotonicEndNanos = endedAtNanos,
            llmDeltaCount = llmDeltaCount,
            firstTextMillis = firstTextAtNanos?.let { (it - startedAtNanos) / NANOS_PER_MILLI },
            firstAudibleMillis = firstAudibleAtNanos?.let { (it - startedAtNanos) / NANOS_PER_MILLI },
            bargeInStopMillis =
                if (bargeInOnsetAtNanos != null && bargeInStopAtNanos != null) {
                    (bargeInStopAtNanos!! - bargeInOnsetAtNanos!!) / NANOS_PER_MILLI
                } else {
                    null
                },
        )

    internal fun clockNanos(): Long = clock.nanoTime()

    internal fun emitStage(
        stage: DiagnosticStage,
        outcome: DiagnosticOutcome,
        durationNanos: Long?,
        attributes: Map<DiagnosticAttribute, String>,
        eventTimeNanos: Long = clock.nanoTime(),
    ) {
        sink.record(
            DiagnosticEvent(
                stage = stage,
                outcome = outcome,
                monotonicTimeNanos = eventTimeNanos,
                turnId = turnId,
                durationNanos = durationNanos,
                attributes = identifyingAttributes() + attributes,
                traceId = traceId,
            ),
        )
    }

    private fun identifyingAttributes(): Map<DiagnosticAttribute, String> =
        buildMap {
            providerId?.let { put(DiagnosticAttribute.PROVIDER_ID, it) }
            modelId?.let { put(DiagnosticAttribute.MODEL_ID, it) }
            runtime?.let { put(DiagnosticAttribute.RUNTIME, it) }
            reasoningLevel?.let { put(DiagnosticAttribute.REASONING_LEVEL, it) }
            engineId?.let { put(DiagnosticAttribute.ENGINE_ID, it) }
        }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L

        /**
         * `prompt=…,completion=…,total=…` with only the reported parts, or `null`
         * when the provider reported nothing. Stable order so a trace diff is
         * readable.
         */
        fun usageSummary(
            promptTokens: Int?,
            completionTokens: Int?,
            totalTokens: Int?,
        ): String? {
            val parts =
                buildList {
                    promptTokens?.let { add("prompt=$it") }
                    completionTokens?.let { add("completion=$it") }
                    totalTokens?.let { add("total=$it") }
                }
            return parts.takeIf { it.isNotEmpty() }?.joinToString(separator = ",")
        }
    }
}
