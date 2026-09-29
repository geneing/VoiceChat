package com.voicechat.agent.fake

import com.voicechat.agent.diagnostics.MonotonicClock

/**
 * Deterministic [MonotonicClock] for trace tests.
 *
 * The starting reading is configurable and [advanceMillis] / [advanceNanos]
 * move it forward explicitly, so a test can assert exact durations without
 * depending on real elapsed time. Time never moves backwards.
 */
class FakeMonotonicClock(
    private var nanos: Long = 0L,
) : MonotonicClock {
    override fun nanoTime(): Long = nanos

    /** Advances the clock by [millis] milliseconds. */
    fun advanceMillis(millis: Long) {
        require(millis >= 0L) { "millis must not be negative" }
        nanos += millis * NANOS_PER_MILLI
    }

    /** Advances the clock by [nanosToAdd] nanoseconds. */
    fun advanceNanos(nanosToAdd: Long) {
        require(nanosToAdd >= 0L) { "nanos must not be negative" }
        nanos += nanosToAdd
    }

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
