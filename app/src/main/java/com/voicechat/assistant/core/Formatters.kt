package com.voicechat.assistant.core

import java.util.Locale
import java.util.concurrent.TimeUnit

object Formatters {

    fun durationMs(ms: Long): String = when {
        ms < 0 -> "—"
        ms < 1_000 -> "$ms ms"
        ms < 60_000 -> String.format(Locale.US, "%.2f s", ms / 1000.0)
        else -> String.format(
            Locale.US,
            "%d:%02d",
            TimeUnit.MILLISECONDS.toMinutes(ms),
            TimeUnit.MILLISECONDS.toSeconds(ms) % 60,
        )
    }

    fun tokens(count: Int): String = when {
        count < 1_000 -> count.toString()
        count < 1_000_000 -> String.format(Locale.US, "%.1fK", count / 1000.0)
        else -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
    }

    fun cost(usd: Double): String = when {
        usd <= 0.0 -> "—"
        usd < 0.01 -> String.format(Locale.US, "$%.4f", usd)
        else -> String.format(Locale.US, "$%.2f", usd)
    }

    fun relativeTime(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val diff = (now - timestamp).coerceAtLeast(0)
        return when {
            diff < 60_000 -> "just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> "${diff / 3_600_000} h ago"
            diff < 2 * 86_400_000 -> "yesterday"
            diff < 7 * 86_400_000 -> "${diff / 86_400_000} days ago"
            else -> String.format(Locale.US, "%tD", timestamp)
        }
    }

    fun dayGroup(timestamp: Long, now: Long = System.currentTimeMillis()): String {
        val diff = now - timestamp
        return when {
            diff < 86_400_000L && isSameDay(timestamp, now) -> "Today"
            isSameDay(timestamp, now - 86_400_000L) -> "Yesterday"
            diff < 7 * 86_400_000L -> "Previous 7 days"
            diff < 30 * 86_400_000L -> "Previous 30 days"
            else -> "Older"
        }
    }

    private fun isSameDay(a: Long, b: Long): Boolean {
        val ca = java.util.Calendar.getInstance().apply { timeInMillis = a }
        val cb = java.util.Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(java.util.Calendar.YEAR) == cb.get(java.util.Calendar.YEAR) &&
            ca.get(java.util.Calendar.DAY_OF_YEAR) == cb.get(java.util.Calendar.DAY_OF_YEAR)
    }
}
