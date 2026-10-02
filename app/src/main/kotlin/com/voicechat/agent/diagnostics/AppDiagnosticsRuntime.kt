package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticsSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * App-owned diagnostics pipeline: bounded, non-blocking capture into a local,
 * bounded in-memory trace store. The store is never persisted or uploaded.
 */
class AppDiagnosticsRuntime(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    maxEvents: Int = InMemoryTraceStore.DEFAULT_MAX_EVENTS,
    bufferCapacity: Int = BoundedDiagnosticsSink.DEFAULT_CAPACITY,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Retained events available to in-process developer tooling and tests. */
    val traceStore = InMemoryTraceStore(maxEvents)

    private val boundedSink =
        BoundedDiagnosticsSink(
            scope = scope,
            capacity = bufferCapacity,
            downstream = traceStore,
        )

    /** Sink passed to voice capture, STT, TTS, and turn orchestration. */
    val sink: DiagnosticsSink get() = boundedSink

    /** Number of events rejected because the bounded queue could not accept them. */
    val droppedEventCount get() = boundedSink.droppedEventCount

    /** Drains accepted events before stopping the collector. Safe to call repeatedly. */
    suspend fun close() {
        boundedSink.close()
        scope.cancel()
    }
}
