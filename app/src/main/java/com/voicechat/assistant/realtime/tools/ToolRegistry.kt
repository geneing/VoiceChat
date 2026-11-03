package com.voicechat.assistant.realtime.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Client-side tools exposed to the model. Definitions are sent in
 * `session.tools`; results are written back as `function_call_output` items.
 */
class ToolRegistry(
    private val context: Context,
    private val httpClient: OkHttpClient,
) {

    private val json = Json { prettyPrint = false }

    val definitionsJson: String = buildJsonArray {
        add(
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "get_current_datetime")
                    put(
                        "description",
                        "Get the current date and time. Use it for questions about today, " +
                            "the current date, weekday, time or time zone.",
                    )
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("timezone") {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional IANA time zone, for example Europe/Berlin. " +
                                        "Defaults to the device time zone.",
                                )
                            }
                        }
                        // Qwen Audio's function-call parser rejects a schema
                        // without `required` (internal service error), even when
                        // no argument is mandatory.
                        putJsonArray("required") {}
                    }
                }
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "get_location")
                    put(
                        "description",
                        "Get the device's last known approximate location. Use it for " +
                            "location-specific answers such as weather or nearby places.",
                    )
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {}
                        putJsonArray("required") {}
                    }
                }
            },
        )
        add(
            buildJsonObject {
                put("type", "function")
                putJsonObject("function") {
                    put("name", "web_search")
                    put(
                        "description",
                        "Search the public web for current information. Returns the top " +
                            "results with title, URL and snippet.",
                    )
                    putJsonObject("parameters") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("query") {
                                put("type", "string")
                                put("description", "Search query")
                            }
                        }
                        putJsonArray("required") { add("query") }
                    }
                }
            },
        )
    }.toString()

    suspend fun execute(name: String, argumentsJson: String): String = withContext(Dispatchers.IO) {
        AppLog.info(LogCategory.TOOLS, "Executing $name", argumentsJson.take(400))
        val result = runCatching {
            when (name) {
                "get_current_datetime" -> currentDateTime(argumentsJson)
                "get_location" -> location()
                "web_search" -> webSearch(argumentsJson)
                else -> errorResult("Unknown tool: $name")
            }
        }.getOrElse { error ->
            errorResult(error.message ?: error::class.java.simpleName)
        }
        AppLog.info(LogCategory.TOOLS, "Tool result: $name", result.take(600))
        result
    }

    private fun currentDateTime(argumentsJson: String): String {
        val requested = runCatching {
            json.parseToJsonElement(argumentsJson).jsonObject["timezone"]?.jsonPrimitive?.content
        }.getOrNull()
        val zone = runCatching {
            requested?.takeIf { it.isNotBlank() }?.let { ZoneId.of(it) } ?: ZoneId.systemDefault()
        }.getOrElse { ZoneId.systemDefault() }
        val now = ZonedDateTime.now(zone)
        return buildJsonObject {
            put("iso", now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            put("date", now.format(DateTimeFormatter.ISO_LOCAL_DATE))
            put("time", now.format(DateTimeFormatter.ofPattern("HH:mm:ss")))
            put("weekday", now.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US))
            put("timezone", zone.id)
            put("utc_offset", now.offset.id)
        }.toString()
    }

    @SuppressLint("MissingPermission")
    private fun location(): String {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return errorResult("Location permission was not granted.")
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return errorResult("Location service unavailable.")
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        val best = providers
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
            ?: return errorResult("No last known location yet. Enable location and try again.")
        return buildJsonObject {
            put("latitude", String.format(Locale.US, "%.5f", best.latitude))
            put("longitude", String.format(Locale.US, "%.5f", best.longitude))
            put("accuracy_m", best.accuracy.toInt())
            put("age_seconds", ((System.currentTimeMillis() - best.time) / 1000).coerceAtLeast(0))
            put("provider", best.provider)
        }.toString()
    }

    private fun webSearch(argumentsJson: String): String {
        val query = runCatching {
            json.parseToJsonElement(argumentsJson).jsonObject["query"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return errorResult("Missing query.")
        val results = searchDuckDuckGo(query)
        if (results.isEmpty()) {
            return buildJsonObject {
                put("query", query)
                put("results", JsonArray(emptyList()))
                put("note", "No results parsed; the search endpoint may have changed.")
            }.toString()
        }
        return buildJsonObject {
            put("query", query)
            put(
                "results",
                buildJsonArray {
                    results.forEach { item ->
                        add(
                            buildJsonObject {
                                put("title", item.title)
                                put("url", item.url)
                                put("snippet", item.snippet)
                            },
                        )
                    }
                },
            )
        }.toString()
    }

    private fun searchDuckDuckGo(query: String): List<SearchResult> {
        val url = "https://html.duckduckgo.com/html/?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
            )
            .build()
        val html = httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            response.body?.string().orEmpty()
        }
        val titleRegex = Regex(
            "<a[^>]+class=\"result__a\"[^>]+href=\"([^\"]+)\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val snippetRegex = Regex(
            "<a[^>]+class=\"result__snippet\"[^>]*>(.*?)</a>",
            RegexOption.DOT_MATCHES_ALL,
        )
        val titles = titleRegex.findAll(html).toList()
        val snippets = snippetRegex.findAll(html).map { it.groupValues[1] }.toList()
        return titles.take(5).mapIndexed { index, match ->
            SearchResult(
                title = clean(match.groupValues[2]),
                url = decodeDuckDuckGoUrl(match.groupValues[1]),
                snippet = snippets.getOrNull(index)?.let(::clean).orEmpty(),
            )
        }
    }

    private fun decodeDuckDuckGoUrl(raw: String): String {
        val decoded = raw.replace("&amp;", "&")
        if (decoded.startsWith("//duckduckgo.com/l/?uddg=")) {
            val target = decoded.substringAfter("uddg=").substringBefore("&")
            return runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(decoded)
        }
        return decoded
    }

    private fun clean(html: String): String = html
        .replace(Regex("<[^>]+>"), " ")
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&nbsp;", " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun errorResult(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    private data class SearchResult(val title: String, val url: String, val snippet: String)
}
