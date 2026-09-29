package com.voicechat.agent.fake

import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.domain.TraceId

/** [DiagnosticsSink] that keeps every recorded event for assertions. */
class RecordingDiagnosticsSink : DiagnosticsSink {
    private val recorded = mutableListOf<DiagnosticEvent>()

    /** Snapshot of recorded events in call order. */
    val events: List<DiagnosticEvent> get() = recorded.toList()

    /** True when every recorded event carrying a trace ID shares a single trace. */
    val isSingleTrace: Boolean
        get() = recorded.mapNotNull { it.traceId }.distinct().size <= 1

    /** Distinct trace IDs seen, in first-seen order. */
    val traceIds: List<TraceId> get() = recorded.mapNotNull { it.traceId }.distinct()

    /** Recorded events for one [traceId], in call order. */
    fun eventsFor(traceId: TraceId): List<DiagnosticEvent> = recorded.filter { it.traceId == traceId }

    override fun record(event: DiagnosticEvent) {
        recorded += event
    }
}
