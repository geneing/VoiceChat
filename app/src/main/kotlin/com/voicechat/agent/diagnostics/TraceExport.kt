package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent

/**
 * Renders a trace as deterministic, newline-delimited text for a test run.
 *
 * The output is intentionally content-free: only the event's stage, outcome,
 * trace/turn identity, monotonic timing, and enum-keyed attributes are written.
 * Because the event model cannot carry free-form content, an exported trace
 * cannot leak a transcript, prompt, credential, audio, or provider response
 * body even when the store was configured with a larger retention window.
 */
object TraceJsonExporter {
    /** Renders [events] oldest first, one JSON object per line. */
    fun export(events: List<DiagnosticEvent>): String =
        events.joinToString(separator = "\n", postfix = if (events.isEmpty()) "" else "\n") { toJsonLine(it) }

    /** Renders one event as a single JSON object (no trailing newline). */
    fun toJsonLine(event: DiagnosticEvent): String =
        buildString {
            append('{')
            appendField("stage", event.stage.name)
            append(',')
            appendField("outcome", event.outcome.name)
            append(',')
            appendField("traceId", event.traceId?.value)
            append(',')
            appendField("turnId", event.turnId?.value)
            append(',')
            append("\"monotonicTimeNanos\":").append(event.monotonicTimeNanos)
            append(',')
            append("\"durationNanos\":").append(event.durationNanos ?: 0L)
            append(',')
            append("\"attributes\":{")
            appendAttributes(event.attributes)
            append('}')
            append('}')
        }

    private fun StringBuilder.appendAttributes(attributes: Map<DiagnosticAttribute, String>) {
        attributes.entries
            .sortedBy { it.key.name }
            .forEachIndexed { index, entry ->
                if (index > 0) append(',')
                appendEscaped(entry.key.name)
                append(':')
                appendEscaped(entry.value)
            }
    }

    private fun StringBuilder.appendField(
        name: String,
        value: String?,
    ) {
        appendEscaped(name)
        append(':')
        if (value == null) append("null") else appendEscaped(value)
    }

    private fun StringBuilder.appendEscaped(value: String) {
        append('"')
        value.forEach { character ->
            when (character) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(character)
            }
        }
        append('"')
    }
}

/** One human-readable line in the local trace viewer. */
data class TraceViewLine(
    /** Buffer-relative offset in milliseconds, for reading stage order at a glance. */
    val elapsedMillis: Long,
    val stage: String,
    val outcome: String,
    val traceId: String?,
    val durationMillis: Long?,
    val attributes: Map<DiagnosticAttribute, String>,
)

/** Formats retained trace events for a developer-facing log or debug screen. */
object TraceViewer {
    /** Renders one line per event, oldest first, relative to the earliest retained event. */
    fun lines(events: List<DiagnosticEvent>): List<TraceViewLine> {
        val origin = events.minOfOrNull { it.monotonicTimeNanos } ?: 0L
        return events.map { event ->
            TraceViewLine(
                elapsedMillis = (event.monotonicTimeNanos - origin) / NANOS_PER_MILLI,
                stage = event.stage.name,
                outcome = event.outcome.name,
                traceId = event.traceId?.value,
                durationMillis = event.durationNanos?.let { it / NANOS_PER_MILLI },
                attributes = event.attributes,
            )
        }
    }

    /** Renders [lines] as a stable, single-line-per-event string. */
    fun render(lines: List<TraceViewLine>): String =
        lines.joinToString(separator = "\n") { line ->
            buildString {
                append("+").append(line.elapsedMillis).append("ms")
                append(' ').append(line.stage)
                append(' ').append(line.outcome)
                line.durationMillis?.let { append(" (").append(it).append("ms)") }
                line.traceId?.let { append(" trace=").append(it) }
                if (line.attributes.isNotEmpty()) {
                    append(' ')
                    append(
                        line.attributes.entries
                            .sortedBy { it.key.name }
                            .joinToString(separator = ",", prefix = "{", postfix = "}") { "${it.key.name}=${it.value}" },
                    )
                }
            }
        }

    private const val NANOS_PER_MILLI = 1_000_000L
}
