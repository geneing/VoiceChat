package com.voicechat.assistant.realtime

/**
 * Retry schedule for opening or restoring the realtime connection.
 *
 * The first attempt is immediate and the follow-ups stay short (a few hundred
 * milliseconds) so a flaky mobile link recovers before the user notices the
 * drop. The schedule is pure so it can be unit tested.
 */
object ReconnectPolicy {

    /** Attempts made when a turn or voice mode opens a session. */
    const val INITIAL_ATTEMPTS = 3

    /** Attempts made when an established session drops mid-conversation. */
    const val RECOVERY_ATTEMPTS = 10

    /**
     * Backoff between recovery attempts. The tail is deliberately long: a phone
     * that lost Wi-Fi or DNS needs tens of seconds, and giving up in ten (as the
     * first version did) ended a live conversation on a transient outage.
     */
    private val DELAYS_MS = longArrayOf(
        250, 600, 1_200, 2_000, 3_000, 3_000, 4_000, 5_000, 5_000, 5_000,
    )

    /** Backoff before retry number [attempt] (1-based). */
    fun delayMs(attempt: Int): Long = DELAYS_MS[(attempt - 1).coerceIn(0, DELAYS_MS.lastIndex)]

    /**
     * How long one connect attempt may run before the loop gives up on it and
     * retries. A retry that waits out a dead network's full TCP timeout would
     * make the whole recovery slower than starting over.
     */
    fun connectTimeoutMs(attempt: Int): Long = if (attempt <= 1) 20_000L else 8_000L

    /**
     * Errors no retry can fix: a missing key, credentials the server rejects or
     * a native library missing for this CPU. Retrying those only delays the
     * message the user needs to see.
     */
    fun isPermanent(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val text = message.lowercase()
        return PERMANENT_MARKERS.any { text.contains(it) }
    }

    private val PERMANENT_MARKERS = listOf(
        "api key is not configured",
        "requires an api key",
        "invalid api key",
        "unauthorized",
        "forbidden",
        "not available for this cpu",
        "native libraries are not available",
        // Only the status phrasing, never a bare number: a timeout message can
        // legitimately contain values like "401 ms".
        "http 401",
        "http 403",
    )
}
