package com.voicechat.assistant.realtime.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.voicechat.assistant.BuildConfig
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.SearchProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLDecoder
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Client-side tools exposed to the model. Definitions are sent in
 * `session.tools`; results are written back as `function_call_output` items.
 */
class ToolRegistry(
    private val context: Context,
    private val httpClient: OkHttpClient,
    private val exaApiKey: String = BuildConfig.EXA_API_KEY,
) {

    private val json = Json { prettyPrint = false }

    /** Reports a provider that rejected its key, so the UI can tell the user. */
    @Volatile
    var onSearchIssue: ((String) -> Unit)? = null

    private val rejectedProviders = java.util.Collections.synchronizedSet(mutableSetOf<SearchProvider>())
    private val reportedProviders = java.util.Collections.synchronizedSet(mutableSetOf<SearchProvider>())

    private data class SearchAttempt(
        val results: List<SearchResult>,
        val rejected: Boolean = false,
    )

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val DIRECT_EXECUTOR = Executor { runnable -> runnable.run() }
        const val FIX_TIMEOUT_MS = 3_500L
        const val MAX_LOCATION_AGE_MS = 120_000L
    }

    fun definitions(includeWebSearch: Boolean): String = ToolDefinitions.json(includeWebSearch)

    val definitionsJson: String get() = ToolDefinitions.all

    suspend fun execute(
        name: String,
        argumentsJson: String,
        searchProvider: SearchProvider = SearchProvider.EXA,
    ): String = withContext(Dispatchers.IO) {
        AppLog.info(LogCategory.TOOLS, "Executing $name", argumentsJson.take(400))
        val result = runCatching {
            when (name) {
                "get_current_datetime" -> currentDateTime(argumentsJson)
                "get_location" -> location()
                "get_weather" -> weather()
                "web_search" -> webSearch(argumentsJson, searchProvider)
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
        return when (val located = locate()) {
            is Located.Failure -> errorResult(located.message)
            is Located.Ok -> buildJsonObject {
                put("latitude", String.format(Locale.US, "%.5f", located.location.latitude))
                put("longitude", String.format(Locale.US, "%.5f", located.location.longitude))
                put("accuracy_m", located.location.accuracy.toInt())
                put(
                    "age_seconds",
                    ((System.currentTimeMillis() - located.location.time) / 1000).coerceAtLeast(0),
                )
                put("provider", located.location.provider)
                located.place?.let { put("place", it) }
            }.toString()
        }
    }

    /**
     * Current weather for the device location. Open-Meteo needs no API key, so
     * weather answers do not depend on a configured search provider.
     */
    private fun weather(): String {
        val located = locate()
        if (located is Located.Failure) return errorResult(located.message)
        located as Located.Ok
        val latitude = located.location.latitude
        val longitude = located.location.longitude
        val url = buildString {
            append("https://api.open-meteo.com/v1/forecast")
            append("?latitude=").append(String.format(Locale.US, "%.4f", latitude))
            append("&longitude=").append(String.format(Locale.US, "%.4f", longitude))
            append("&current=temperature_2m,apparent_temperature,relative_humidity_2m,")
            append("precipitation,weather_code,wind_speed_10m")
            append("&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            append("&timezone=auto&forecast_days=1")
        }
        val body = runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    AppLog.warn(LogCategory.TOOLS, "Weather lookup failed", "status=${response.code}")
                    null
                } else {
                    response.body?.string()
                }
            }
        }.getOrNull() ?: return errorResult("The weather service did not answer.")
        return runCatching {
            val root = json.parseToJsonElement(body).jsonObject
            val current = root["current"]?.jsonObject
            val daily = root["daily"]?.jsonObject
            requireNotNull(current) { "missing current weather" }
            buildJsonObject {
                located.place?.let { put("place", it) }
                numberField(current, "temperature_2m")?.let { put("temperature_c", it) }
                numberField(current, "apparent_temperature")?.let { put("feels_like_c", it) }
                numberField(current, "relative_humidity_2m")?.let { put("humidity_pct", it) }
                numberField(current, "precipitation")?.let { put("precipitation_mm", it) }
                numberField(current, "wind_speed_10m")?.let { put("wind_kmh", it) }
                numberField(current, "weather_code")?.let { code ->
                    put("condition", weatherCondition(code.toInt()))
                }
                numberField(daily, "temperature_2m_max", 0)?.let { put("today_high_c", it) }
                numberField(daily, "temperature_2m_min", 0)?.let { put("today_low_c", it) }
                numberField(daily, "precipitation_probability_max", 0)?.let {
                    put("rain_chance_pct", it)
                }
                put("observed_at", current["time"]?.jsonPrimitive?.content.orEmpty())
                put("units", "celsius, km/h")
            }.toString()
        }.getOrElse { error ->
            AppLog.warn(LogCategory.TOOLS, "Weather response unreadable", error.message)
            errorResult("The weather response could not be read.")
        }
    }

    private fun numberField(
        source: kotlinx.serialization.json.JsonObject?,
        key: String,
        index: Int = -1,
    ): Double? {
        val element = source?.get(key) ?: return null
        if (index < 0) return element.jsonPrimitive.doubleOrNull
        val array = element as? JsonArray ?: return null
        return (array.getOrNull(index) as? JsonPrimitive)?.doubleOrNull
    }

    private fun weatherCondition(code: Int): String = when (code) {
        0 -> "clear sky"
        1, 2 -> "partly cloudy"
        3 -> "overcast"
        45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "drizzle"
        61, 63, 65, 66, 67 -> "rain"
        71, 73, 75, 77 -> "snow"
        80, 81, 82 -> "rain showers"
        85, 86 -> "snow showers"
        95 -> "thunderstorm"
        96, 99 -> "thunderstorm with hail"
        else -> "unknown"
    }

    /** Resolves the device location, or explains why it is unavailable. */
    @SuppressLint("MissingPermission")
    private fun locate(): Located {
        val fine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            return Located.Failure(
                "Location permission was not granted. Ask the user to allow location " +
                    "access for VoiceChat in Android settings, then try again.",
            )
        }
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return Located.Failure("Location service unavailable.")
        val providers = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) {
            return Located.Failure("Location is turned off on this device.")
        }
        val lastKnown = providers
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .maxByOrNull { it.time }
        val stale = lastKnown == null ||
            System.currentTimeMillis() - lastKnown.time > MAX_LOCATION_AGE_MS
        val fresh = if (stale) providers.firstNotNullOfOrNull { requestFix(manager, it) } else null
        val best = fresh ?: lastKnown
            ?: return Located.Failure(
                "No location fix is available yet. Ask the user to step outside or " +
                    "enable Location Accuracy, then try again.",
            )
        return Located.Ok(best, placeName(best))
    }

    /** Best-effort reverse geocoding so answers can name the city. */
    private fun placeName(location: Location): String? = runCatching {
        if (!android.location.Geocoder.isPresent()) return null
        val geocoder = android.location.Geocoder(context, Locale.getDefault())
        val address = geocoder.getFromLocation(location.latitude, location.longitude, 1)
            ?.firstOrNull()
            ?: return null
        listOfNotNull(
            address.locality ?: address.subAdminArea,
            address.adminArea,
            address.countryName,
        ).distinct().joinToString(", ").ifBlank { null }
    }.getOrNull()

    private sealed interface Located {
        data class Ok(val location: Location, val place: String?) : Located
        data class Failure(val message: String) : Located
    }

    /**
     * Blocks briefly for a single fresh fix. Last known locations can be hours
     * old or missing entirely, which made voice answers claim the location was
     * unavailable.
     */
    @SuppressLint("MissingPermission")
    private fun requestFix(manager: LocationManager, provider: String): Location? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>(null)
        return try {
            manager.getCurrentLocation(provider, null, DIRECT_EXECUTOR) { location ->
                result.set(location)
                latch.countDown()
            }
            if (latch.await(FIX_TIMEOUT_MS, TimeUnit.MILLISECONDS)) result.get() else null
        } catch (error: SecurityException) {
            AppLog.warn(LogCategory.TOOLS, "Location fix denied", error.message)
            null
        } catch (error: IllegalArgumentException) {
            AppLog.warn(LogCategory.TOOLS, "Location provider unavailable", error.message)
            null
        }
    }

    private fun webSearch(argumentsJson: String, provider: SearchProvider): String {
        val query = runCatching {
            json.parseToJsonElement(argumentsJson).jsonObject["query"]?.jsonPrimitive?.content
        }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return errorResult("Missing query.")
        val chain = searchFallbackChain(
            selected = provider,
            hasExaKey = exaApiKey.isNotBlank(),
            hasBraveKey = BuildConfig.BRAVE_SEARCH_API_KEY.isNotBlank(),
            rejected = rejectedProviders.toSet(),
        )
        if (chain.isEmpty()) {
            return errorResult(
                "No web search provider is available. Configure a valid Exa key in " +
                    "secrets/exa.key or a Brave key in secrets/brave.key and rebuild.",
            )
        }
        val rejected = mutableListOf<String>()
        chain.forEach { candidate ->
            val attempt = when (candidate) {
                SearchProvider.EXA -> searchExa(query)
                SearchProvider.BRAVE -> searchBrave(query)
                SearchProvider.DUCKDUCKGO -> SearchAttempt(searchDuckDuckGo(query))
            }
            if (attempt.rejected) {
                rejectedProviders.add(candidate)
                rejected += candidate.name.lowercase()
                reportRejected(candidate)
            }
            if (attempt.results.isNotEmpty()) {
                AppLog.info(LogCategory.TOOLS, "Search provider used", candidate.name.lowercase())
                AppLog.info(
                    LogCategory.TOOLS,
                    "Search results",
                    "${candidate.name.lowercase()}: ${attempt.results.size}",
                )
                return buildJsonObject {
                    put("query", query)
                    put("provider", candidate.name.lowercase())
                    if (rejected.isNotEmpty()) {
                        put(
                            "provider_warnings",
                            JsonArray(rejected.map { JsonPrimitive("$it rejected its API key") }),
                        )
                    }
                    put(
                        "results",
                        buildJsonArray {
                            attempt.results.forEach { item ->
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
            AppLog.warn(
                LogCategory.TOOLS,
                "Search provider returned nothing",
                candidate.name.lowercase(),
            )
        }
        return buildJsonObject {
            put("query", query)
            put("results", JsonArray(emptyList()))
            put("providers_tried", JsonArray(chain.map { JsonPrimitive(it.name.lowercase()) }))
            if (rejected.isNotEmpty()) {
                put(
                    "provider_warnings",
                    JsonArray(rejected.map { JsonPrimitive("$it rejected its API key") }),
                )
            }
            put("note", "No search results were returned by any provider.")
        }.toString()
    }

    private fun reportRejected(provider: SearchProvider) {
        if (!reportedProviders.add(provider)) return
        AppLog.warn(
            LogCategory.TOOLS,
            "Search provider rejected its API key",
            provider.name.lowercase(),
        )
        val secret = when (provider) {
            SearchProvider.EXA -> "secrets/exa.key"
            SearchProvider.BRAVE -> "secrets/brave.key"
            SearchProvider.DUCKDUCKGO -> return
        }
        onSearchIssue?.invoke(
            "${provider.label} rejected its API key. Replace $secret and rebuild; " +
                "searches keep using another provider meanwhile.",
        )
    }

    private fun searchExa(query: String): SearchAttempt {
        val body = buildJsonObject {
            put("query", query)
            put("type", "auto")
            put("numResults", 5)
            put("contents", buildJsonObject {
                put("highlights", true)
                put("text", false)
            })
        }.toString()
        val request = Request.Builder()
            .url("https://api.exa.ai/search")
            .header("x-api-key", exaApiKey)
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                AppLog.warn(LogCategory.TOOLS, "Exa search failed", "status=${it.code}")
                return SearchAttempt(emptyList(), rejected = it.code == 401 || it.code == 403)
            }
            return SearchAttempt(SearchResponseParser.exa(it.body?.string().orEmpty()))
        }
    }

    private fun searchBrave(query: String): SearchAttempt {
        val url = "https://api.search.brave.com/res/v1/web/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&count=5"
        val request = Request.Builder()
            .url(url)
            .header("X-Subscription-Token", BuildConfig.BRAVE_SEARCH_API_KEY)
            .header("Accept", "application/json")
            .build()
        val response = httpClient.newCall(request).execute()
        response.use {
            if (!it.isSuccessful) {
                AppLog.warn(LogCategory.TOOLS, "Brave search failed", "status=${it.code}")
                return SearchAttempt(emptyList(), rejected = it.code == 401 || it.code == 403)
            }
            return SearchAttempt(SearchResponseParser.brave(it.body?.string().orEmpty()))
        }
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

}

/**
 * Providers to try for one search, in order. The selected provider goes first,
 * then the remaining ones that have credentials, so an invalid key or an empty
 * result never leaves the assistant claiming it cannot look something up.
 */
internal fun searchFallbackChain(
    selected: SearchProvider,
    hasExaKey: Boolean,
    hasBraveKey: Boolean,
    rejected: Set<SearchProvider> = emptySet(),
): List<SearchProvider> {
    val usable = SearchProvider.entries.filter { provider ->
        if (provider in rejected) return@filter false
        when (provider) {
            SearchProvider.EXA -> hasExaKey
            SearchProvider.BRAVE -> hasBraveKey
            SearchProvider.DUCKDUCKGO -> true
        }
    }
    val ordered = listOf(selected) + usable.filter { it != selected }
    return ordered.filter { it in usable }
}
