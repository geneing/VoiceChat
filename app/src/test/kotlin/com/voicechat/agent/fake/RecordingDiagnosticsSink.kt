package com.voicechat.agent.fake

import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticsSink

/** [DiagnosticsSink] that keeps every recorded event for assertions. */
class RecordingDiagnosticsSink : DiagnosticsSink {
    private val recorded = mutableListOf<DiagnosticEvent>()

    /** Snapshot of recorded events in call order. */
    val events: List<DiagnosticEvent> get() = recorded.toList()

    override fun record(event: DiagnosticEvent) {
        recorded += event
    }
}
