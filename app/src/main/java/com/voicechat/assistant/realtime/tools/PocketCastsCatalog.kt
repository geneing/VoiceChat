package com.voicechat.assistant.realtime.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal class PocketCastsCatalog(private val http: OkHttpClient) {
    suspend fun lookup(query: String, latest: Boolean): String {
        if (query.isBlank()) return toolError("Which show, episode, or topic?")
        // The public catalog negotiates the Streamable HTTP protocol and
        // returns its tool payload as JSON structuredContent.
        val init = rpc("initialize", 1, buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            putJsonObject("capabilities") {}
            putJsonObject("clientInfo") { put("name", "VoiceChat"); put("version", "1.0") }
        })
        if (init?.obj("result")?.string("protocolVersion") != PROTOCOL_VERSION) {
            return toolError("The Pocket Casts catalog is unavailable.")
        }
        rpc("notifications/initialized", null, buildJsonObject {})
        val episodes = if (latest) {
            val shows = call("search_podcasts", 2, buildJsonObject { put("query", query); put("limit", 5) })
                .array("podcasts")
            val show = shows.firstOrNull { it.string("title").equals(query, true) }
                ?: shows.singleOrNull() ?: return toolError("Please say the full Pocket Casts show name.")
            call("get_podcast_episodes", 3, buildJsonObject {
                put("uuid", show.string("uuid"))
                put("limit", 1)
            }).array("episodes")
        } else {
            call("search_episodes", 2, buildJsonObject { put("query", query); put("limit", 4) }).array("episodes")
        }
        return buildJsonObject {
            put("ok", true)
            put("source", "Pocket Casts")
            putJsonArray("matches") {
                episodes.forEach { episode ->
                    val url = episode.obj("pocketcasts_url")?.obj("episode")?.string("share").orEmpty()
                    if (episodeLink(url) != null) add(buildJsonObject {
                        put("title", episode.string("title"))
                        put("show", episode.string("podcast_title"))
                        put("posted_at", episode.string("published_date"))
                        put("url", url)
                    })
                }
            }
        }.toString()
    }

    private suspend fun call(name: String, id: Int, args: JsonObject): JsonObject =
        rpc("tools/call", id, buildJsonObject { put("name", name); put("arguments", args) })
            ?.obj("result")?.obj("structuredContent") ?: error("No Pocket Casts data returned.")

    private suspend fun rpc(method: String, id: Int?, params: JsonObject): JsonObject? {
        val payload = buildJsonObject {
            put("jsonrpc", "2.0")
            id?.let { put("id", it) }
            put("method", method)
            put("params", params)
        }
        val response = http.toolResponse(Request.Builder().url("https://mcp.pocketcasts.com")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build())
        if (id == null) return null
        return Json.parseToJsonElement(response) as JsonObject
    }

    companion object {
        private const val PROTOCOL_VERSION = "2025-03-26"

        fun episodeLink(value: String) = value.toHttpUrlOrNull()?.takeIf {
            it.isHttps && it.username.isEmpty() && it.password.isEmpty() &&
                it.host in setOf("pocketcasts.com", "www.pocketcasts.com", "pca.st") &&
                it.pathSegments.firstOrNull() in setOf("podcast", "podcasts", "episode")
        }
    }
}
