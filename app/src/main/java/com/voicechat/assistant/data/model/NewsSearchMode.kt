package com.voicechat.assistant.data.model

/** Where the news digest gets its facts from. */
enum class NewsSearchMode(val label: String, val detail: String) {
    /**
     * The app searches (Exa news category, Brave News, DuckDuckGo) and hands the
     * dated items to the digest model as reference material.
     */
    APP(
        "App search (Exa/Brave)",
        "The app fetches dated world, market and AI items and the news model writes the digest from them.",
    ),

    /**
     * The digest model searches on its own: the request carries the vendor's
     * native search flag (`enable_search`), so the server does the search.
     */
    MODEL(
        "Model web search",
        "The news model runs the web search itself; the app sends no items. Needs a model that supports search.",
    ),

    /** No search at all: the model must say it cannot verify today's news. */
    OFF(
        "No search",
        "The news model answers without web access and says when it cannot verify a story.",
    ),
}
