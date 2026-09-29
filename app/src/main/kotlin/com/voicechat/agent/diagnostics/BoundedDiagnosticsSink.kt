package com.voicechat.agent.diagnostics

import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Bounded, non-blocking [DiagnosticsSink].
 *
 * [record] only offers the event to an in-memory channel with
 * [Channel.trySend]; it never suspends, so calling it from the audio or UI path
 * cannot block. A consumer coroutine on [scope] drains the buffer to
 * [downstream] off the caller's thread.
 *
 * When the buffer is full the event is dropped, but the drop is counted in
 * [droppedEventCount] rather than lost silently. A consumer that is slow or
 * stalled therefore degrades into an observable drop count instead of stalling
 * capture or rendering.
 *
 * **Ownership and lifecycle.** The sink is app-scoped; [scope] must outlive it.
 * Cancelling [scope] stops draining (and further [record] calls are dropped and
 * counted). The buffer is not closed explicitly because the sink is expected to
 * live for the process lifetime.
 *
 * @param capacity number of events buffered before drops begin.
 */
class BoundedDiagnosticsSink(
    scope: CoroutineScope,
    capacity: Int = DEFAULT_CAPACITY,
    downstream: DiagnosticsSink = NoOpDiagnosticsSink,
) : DiagnosticsSink {
    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val channel =
        Channel<DiagnosticEvent>(
            capacity = capacity,
            onBufferOverflow = BufferOverflow.SUSPEND,
        )

    private val droppedState = MutableStateFlow(0L)

    /**
     * Running count of events dropped because the buffer was full.
     *
     * Observable on purpose: a nonzero, growing value tells a developer the
     * trace is incomplete instead of hiding events.
     */
    val droppedEventCount: StateFlow<Long> get() = droppedState.asStateFlow()

    init {
        scope.launch {
            for (event in channel) {
                downstream.record(event)
            }
        }
    }

    override fun record(event: DiagnosticEvent) {
        if (channel.trySend(event).isFailure) {
            droppedState.update { it + 1 }
        }
    }

    companion object {
        /** Default buffer size; enough for a burst of stage events without drops. */
        const val DEFAULT_CAPACITY = 256
    }
}
