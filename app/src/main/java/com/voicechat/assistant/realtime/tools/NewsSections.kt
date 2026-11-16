package com.voicechat.assistant.realtime.tools

/**
 * Sections of the news reference and the query each one runs.
 *
 * A generic web search answers "top news today" with whatever page it ranks
 * first — usually a homepage whose cached sidebar mixes fresh and months-old
 * stories, with no dates to check. The news reference asks the providers for
 * *news* published in the last days in three separate sections, so the digest
 * model receives dated, grouped material.
 */
object NewsSections {

    const val WORLD = "world"
    const val MARKETS = "markets"
    const val AI = "ai"

    /** The order the digest should present its sections in. */
    val order = listOf(WORLD, MARKETS, AI)

    /** Any window is measured in days: enough for late coverage of today's news. */
    const val WINDOW_DAYS = 2L

    fun queryFor(section: String): String = when (section) {
        MARKETS ->
            "stock market today S&P 500 Nasdaq Dow movers what moved the market"
        AI ->
            "artificial intelligence news today model releases research papers " +
                "open-source models library changes"
        else -> "top world news today"
    }

    /**
     * Items fetched per section. The reference handed to the digest model is
     * kept small: four world and market stories and the five AI items the news
     * recipe asks for.
     */
    fun itemsFor(section: String): Int = when (section) {
        AI -> 5
        else -> 3
    }
}
