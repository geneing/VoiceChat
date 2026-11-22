package com.voicechat.assistant.realtime.tools

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException

internal class BriefingTools(
    context: Context,
    private val http: OkHttpClient,
    private val exaKey: String,
) {
    private val saved = context.getSharedPreferences("saved_for_later", Context.MODE_PRIVATE)

    suspend fun reddit(args: JsonObject): String = coroutineScope {
        if (exaKey.isBlank()) return@coroutineScope toolError("Reddit briefings need the Exa search key.")
        val topic = args.string("topic").take(120).ifBlank { "machine learning" }
        val explicit = args.string("communities").split(',').map { it.trim().removePrefix("r/") }
            .filter { it.isNotBlank() }.distinctBy { it.lowercase() }
        if (explicit.any { !it.matches(Regex("[A-Za-z0-9_]{2,24}")) }) {
            return@coroutineScope toolError("Provide valid subreddit names such as MachineLearning.")
        }
        val communities = explicit.take(5).ifEmpty {
            if (topic.lowercase().removePrefix("r/") in setOf("machine learning", "machinelearning", "ml", "ai", "artificial intelligence")) {
                listOf("MachineLearning", "LocalLLaMA", "StableDiffusion")
            } else emptyList()
        }
        val days = args.string("days").ifBlank { "7" }.toIntOrNull()?.takeIf { it in 1..30 }
            ?: return@coroutineScope toolError("Choose a lookback between 1 and 30 days.")
        val since = Instant.now().minus(days.toLong(), ChronoUnit.DAYS)
        val searches = communities.map<String, String?> { it }.ifEmpty { listOf(null) }
        val results = searches.map { community ->
            async {
                try {
                    redditSearch(topic, community, since)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    null
                }
            }
        }.awaitAll()
        if (results.all { it == null }) return@coroutineScope toolError("Reddit search couldn't reach the search provider.")
        // Interleave each community's search rankings so a busy community
        // doesn't take over the briefing. Search rank is not Reddit vote rank.
        val lists = results.filterNotNull()
        val threads = (0 until 5).flatMap { rank -> lists.mapNotNull { it.getOrNull(rank) } }
            .distinctBy { it.string("url") }.take(7)
        buildJsonObject {
            put("ok", true)
            put("source", "Exa search index, including public RSSAmp listings. Results are search ranked.")
            put("topic", topic)
            put("since", since.toString())
            if (results.any { it == null }) put("coverage", "Some community searches failed.")
            val missing = searches.zip(results).mapNotNull { (community, rows) ->
                community?.takeIf { rows.isNullOrEmpty() }
            }
            if (missing.isNotEmpty()) put("no_indexed_results", missing.joinToString { "r/$it" })
            putJsonArray("threads") { threads.forEach { add(it) } }
        }.toString()
    }

    private suspend fun redditSearch(topic: String, community: String?, since: Instant): List<JsonObject> {
        val body = buildJsonObject {
            put("query", "Active recent Reddit discussions and substantive posts about $topic" +
                (community?.let { " in r/$it" } ?: ""))
            put("type", "auto")
            put("numResults", 12)
            put("startPublishedDate", since.toString())
            putJsonArray("includeDomains") {
                val suffix = community?.let { "/r/$it" }.orEmpty()
                add("reddit.com$suffix")
                add("rssamp.com$suffix")
            }
            put("contents", buildJsonObject { put("highlights", true) })
        }
        val root = http.toolJson(Request.Builder().url("https://api.exa.ai/search")
            .header("x-api-key", exaKey)
            .post(body.toString().toRequestBody("application/json".toMediaType())).build())
        return root.array("results").mapNotNull { result ->
            val url = result.string("url").toHttpUrlOrNull() ?: return@mapNotNull null
            val segments = url.pathSegments
            if (url.host !in setOf("reddit.com", "www.reddit.com", "old.reddit.com", "rssamp.com") ||
                segments.size < 4 || segments[0] != "r" || segments[2] != "comments" ||
                !segments[1].matches(Regex("[A-Za-z0-9_]{2,24}")) ||
                !segments[3].matches(Regex("[a-z0-9]+")) ||
                (community != null && !segments[1].equals(community, true))) return@mapNotNull null
            val published = result.string("publishedDate")
            val date = runCatching { Instant.parse(published) }.getOrNull() ?: return@mapNotNull null
            if (date.isBefore(since) || date.isAfter(Instant.now())) return@mapNotNull null
            val highlights = (result["highlights"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.joinToString(" ").take(450)
            val title = result.string("title").substringBefore(" · RSSAmp").take(220)
            if (title.isBlank()) return@mapNotNull null
            buildJsonObject {
                put("title", title)
                put("community", "r/${segments[1]}")
                put("posted_at", published)
                put("url", "https://www.reddit.com/r/${segments[1]}/comments/${segments[3]}/")
                if (url.host == "rssamp.com") put("indexed_via", "RSSAmp")
                put("excerpt", highlights)
            }
        }.take(5)
    }

    @Synchronized
    fun save(args: JsonObject): String {
        val rows = (Json.parseToJsonElement(saved.getString("items", "[]") ?: "[]") as JsonArray)
            .filterIsInstance<JsonObject>()
        return when (args.string("action")) {
            "list" -> buildJsonObject {
                put("ok", true)
                putJsonArray("saved") { rows.take(5).forEach { add(it) } }
            }.toString()
            "save" -> {
                val title = args.string("title").take(160)
                val note = args.string("note").take(1200)
                val url = args.string("url").take(2048).toHttpUrlOrNull()
                    ?.takeIf { it.isHttps && it.username.isEmpty() && it.password.isEmpty() }
                if (title.isBlank() && note.isBlank() && url == null) {
                    return toolError("Provide a note, title, or HTTPS link to save.")
                }
                val item = buildJsonObject {
                    put("title", title.ifBlank { note.take(80).ifBlank { url?.host.orEmpty() } })
                    if (note.isNotBlank()) put("note", note)
                    url?.let { put("url", it.toString()) }
                    put("saved_at", Instant.now().toString())
                }
                val updated = listOf(item) + rows.filterNot {
                    it["title"] == item["title"] && it["note"] == item["note"] && it["url"] == item["url"]
                }
                if (!saved.edit().putString("items", JsonArray(updated.take(100)).toString()).commit()) {
                    return toolError("The note couldn't be saved.")
                }
                buildJsonObject { put("ok", true); put("saved", item) }.toString()
            }
            else -> toolError("Choose save or list.")
        }
    }
}
