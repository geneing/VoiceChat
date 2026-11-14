package com.voicechat.assistant.realtime.tools

import java.time.Instant
import java.time.ZonedDateTime

/**
 * Decides when a search must be restricted to fresh pages.
 *
 * A "top news today" query without a date window returned the top hit for
 * `apnews.com` — a cached homepage snippet with months-old sidebar text — and
 * the realtime model read it out as today's news. The fix is to ask the
 * provider for pages published recently, and to hand the model every result's
 * publication date so it can tell a fresh story from an undated snippet.
 */
object SearchRecency {

    /** How far back a news query may reach; covers today plus late coverage. */
    const val NEWS_WINDOW_DAYS = 3L

    private val NEWS = Regex(
        "\\b(news|headlines?|breaking|today|tonight|yesterday|latest|current events|" +
            "what happened|this (week|weekend|morning|afternoon|evening|day|\\w+day))\\b",
        RegexOption.IGNORE_CASE,
    )

    fun isNewsQuery(query: String): Boolean = NEWS.containsMatchIn(query)

    /** ISO 8601 cutoff for the provider, or null when the query is evergreen. */
    fun startPublishedDate(
        query: String,
        now: ZonedDateTime = ZonedDateTime.now(),
        windowDays: Long = NEWS_WINDOW_DAYS,
    ): String? = if (isNewsQuery(query)) {
        now.minusDays(windowDays).toInstant().toString()
    } else {
        null
    }

    /** The instant the window opens, for logging. */
    fun cutoff(query: String, now: ZonedDateTime = ZonedDateTime.now()): Instant? =
        startPublishedDate(query, now)?.let(Instant::parse)
}
