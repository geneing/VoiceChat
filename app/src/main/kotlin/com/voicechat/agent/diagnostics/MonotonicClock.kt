package com.voicechat.agent.diagnostics

/**
 * Monotonic time source for latency measurement.
 *
 * Trace timing must never use wall-clock time: a user or NTP clock change would
 * corrupt durations, and wall time can move backwards. Only differences between
 * two readings are meaningful. The interface is a `fun interface` so tests can
 * supply a deterministic clock as a lambda (see `docs/turn-tracing.md`).
 */
fun interface MonotonicClock {
    /** Current monotonic time in nanoseconds. */
    fun nanoTime(): Long
}

/** [MonotonicClock] backed by [System.nanoTime]; monotonic and immune to clock changes. */
object SystemMonotonicClock : MonotonicClock {
    override fun nanoTime(): Long = System.nanoTime()
}
