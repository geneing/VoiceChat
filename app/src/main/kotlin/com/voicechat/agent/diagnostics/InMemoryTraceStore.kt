package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.domain.TraceId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bounded, in-memory store of [DiagnosticEvent]s backing the local trace viewer.
 *
 * It is the downstream of a [BoundedDiagnosticsSink]. Writes, snapshots, and
 * clears synchronize on one lock, so developer tooling can inspect it safely
 * while the collector is recording events. When it reaches [maxEvents] the
 * oldest event is evicted and [evictedEventCount] grows, so a developer sees
 * that the retained window is incomplete. Nothing here is persisted or
 * uploaded; it exists for development and test runs only.
 */
class InMemoryTraceStore(
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) : DiagnosticsSink {
    init {
        require(maxEvents > 0) { "maxEvents must be positive" }
    }

    private val lock = Any()
    private val events = ArrayDeque<DiagnosticEvent>()
    private val revisionState = MutableStateFlow(0L)
    private var evictedCount: Long = 0L

    /** Number of events evicted because the retained window reached [maxEvents]. */
    val evictedEventCount: Long get() = synchronized(lock) { evictedCount }

    /** Bumped whenever the retained events change; a viewer can recompute from this. */
    val revision: StateFlow<Long> get() = revisionState.asStateFlow()

    override fun record(event: DiagnosticEvent) {
        synchronized(lock) {
            events.addLast(event)
            while (events.size > maxEvents) {
                events.removeFirst()
                evictedCount++
            }
            revisionState.update { it + 1 }
        }
    }

    /** Snapshot of retained events, oldest first. */
    fun snapshot(): List<DiagnosticEvent> = synchronized(lock) { events.toList() }

    /** Retained events for one [traceId], oldest first. */
    fun byTrace(traceId: TraceId): List<DiagnosticEvent> = synchronized(lock) { events.filter { it.traceId == traceId } }

    /** Drops all retained events. */
    fun clear() {
        synchronized(lock) {
            events.clear()
            revisionState.update { it + 1 }
        }
    }

    private fun MutableStateFlow<Long>.update(transform: (Long) -> Long) {
        value = transform(value)
    }

    companion object {
        /** Default number of retained events; large enough for many turns. */
        const val DEFAULT_MAX_EVENTS = 2048
    }
}
