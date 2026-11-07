package com.voicechat.assistant.realtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class SearchResult(val title: String, val url: String, val snippet: String)

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
            if (title.isBlank() && url.isBlank() && snippet.isBlank()) null
            else SearchResult(title, url, snippet)
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
            if (title.isBlank() && url.isBlank() && snippet.isBlank()) null
            else SearchResult(title, url, snippet)
        }
    }
}
