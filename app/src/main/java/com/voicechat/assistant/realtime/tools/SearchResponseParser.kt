package com.voicechat.assistant.realtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * One search result. [published] is the provider's publication date (ISO 8601
 * for Exa, `page_age` for Brave) and stays null when the result has none, which
 * is how the model tells a fresh story from an undated homepage snippet.
 */
data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val published: String? = null,
)

object SearchResponseParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun exa(response: String): List<SearchResult> {
        val results = json.parseToJsonElement(response).jsonObject["results"] as? JsonArray
            ?: return emptyList()
        return results.mapNotNull { element ->
            val result = element.jsonObject
            val title = result["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val snippet = (result["highlights"] as? JsonArray)
                ?.mapNotNull { it.jsonPrimitive.contentOrNull }
                ?.joinToString(" ")
                .orEmpty()
            val published = result["publishedDate"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
            if (title.isBlank() && url.isBlank() && snippet.isBlank()) {
                null
            } else {
                SearchResult(title, url, snippet, published)
            }
        }
    }

    fun brave(response: String): List<SearchResult> {
        val results = json.parseToJsonElement(response)
            .jsonObject["web"]?.jsonObject?.get("results") as? JsonArray
            ?: return emptyList()
        return results.mapNotNull { element ->
            val result = element.jsonObject
            val title = result["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val snippet = result["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val published = result["page_age"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: result["age"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (title.isBlank() && url.isBlank() && snippet.isBlank()) {
                null
            } else {
                SearchResult(title, url, snippet, published)
            }
        }
    }

    /** Brave News answers under a top-level `results` array, not `web.results`. */
    fun braveNews(response: String): List<SearchResult> {
        val results = json.parseToJsonElement(response).jsonObject["results"] as? JsonArray
            ?: return emptyList()
        return results.mapNotNull { element ->
            val result = element.jsonObject
            val title = result["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val url = result["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val snippet = result["description"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val published = result["page_age"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: result["age"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            if (title.isBlank() && url.isBlank() && snippet.isBlank()) {
                null
            } else {
                SearchResult(title, url, snippet, published)
            }
        }
    }
}
