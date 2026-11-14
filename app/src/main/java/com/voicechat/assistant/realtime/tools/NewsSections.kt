package com.voicechat.assistant.realtime.tools

import java.util.Locale

/**
 * Sections of the `get_news` tool and the query each one runs.
 *
 * A generic web search answers "top news today" with whatever page it ranks
 * first — usually a homepage whose cached sidebar mixes fresh and months-old
 * stories, with no dates for the model to check. A news tool asks the providers
 * for *news* published in the last days in three separate sections, so the
 * answer arrives already dated and grouped.
 */
object NewsSections {

    const val ALL = "all"
    const val WORLD = "world"
    const val MARKETS = "markets"
    const val AI = "ai"

    /** Sections returned by `all`, in the order the answer should present them. */
    val order = listOf(WORLD, MARKETS, AI)

    /** Any window is measured in days: enough for late coverage of today's news. */
    const val WINDOW_DAYS = 2L

    /** Sections requested by the model. Unknown or missing values mean all. */
    fun parse(requested: String?): List<String> {
        val value = requested?.trim()?.lowercase(Locale.US).orEmpty()
        if (value.isEmpty() || value == ALL) return order
        return order.filter { it == value }.ifEmpty { order }
    }

    fun queryFor(section: String): String = when (section) {
        MARKETS ->
            "stock market today S&P 500 Nasdaq Dow movers what moved the market"
        AI ->
            "artificial intelligence news today model releases research papers " +
                "open-source models library changes"
        else -> "top world news today"
    }

    /**
     * Items fetched per section. The world section is the one spoken first, so
     * it stays small to keep the first answer fast; AI carries the five items
     * the news recipe asks for.
     */
    fun itemsFor(section: String): Int = when (section) {
        AI -> 5
        else -> 4
    }
}
