package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import java.util.concurrent.atomic.AtomicLong

/**
 * Creates one [TurnTraceRecorder] per conversation turn with a unique [TraceId].
 *
 * A turn's trace ID is generated once, when the turn starts, and then reused by
 * every stage so a streamed response, a cancellation, and a failure stay
 * correlated. The ID generator is injectable so tests can use a deterministic
 * sequence (see `docs/turn-tracing.md`).
 */
class TurnTraceFactory(
    private val clock: MonotonicClock,
    private val sink: DiagnosticsSink,
    private val traceIdGenerator: TraceIdGenerator = SequentialTraceIdGenerator,
) {
    /** Starts a trace for [turnId] using the given provider/model metadata. */
    fun start(
        turnId: TurnId?,
        providerId: ProviderId? = null,
        modelId: ModelId? = null,
        runtime: String? = null,
        reasoningLevel: String? = null,
        engineId: String? = null,
    ): TurnTraceRecorder =
        TurnTraceRecorder(
            traceId = traceIdGenerator.next(),
            turnId = turnId,
            clock = clock,
            sink = sink,
            providerId = providerId?.value,
            modelId = modelId?.value,
            runtime = runtime,
            reasoningLevel = reasoningLevel,
            engineId = engineId,
        )

    /**
     * Starts a trace for [selection], recording the provider/model identities
     * that the request is about to use.
     */
    fun start(
        turnId: TurnId?,
        selection: ProviderModelSelection,
        runtime: String? = null,
        reasoningLevel: String? = null,
    ): TurnTraceRecorder = start(turnId, selection.providerId, selection.modelId, runtime, reasoningLevel)
}

/** Supplies the next [TraceId]. */
fun interface TraceIdGenerator {
    fun next(): TraceId
}

/** Deterministic, process-unique trace IDs: `trace-1`, `trace-2`, …; injectable and test-friendly. */
object SequentialTraceIdGenerator : TraceIdGenerator {
    private val counter = AtomicLong(0L)

    override fun next(): TraceId = TraceId("trace-${counter.incrementAndGet()}")
}
