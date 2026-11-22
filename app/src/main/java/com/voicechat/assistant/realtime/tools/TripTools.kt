package com.voicechat.assistant.realtime.tools

import android.location.Location
import android.os.SystemClock
import com.voicechat.assistant.BuildConfig
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.add
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.math.cos

internal class TripTools(private val http: OkHttpClient) {
    private val mapsKey = BuildConfig.GOOGLE_MAPS_API_KEY
    private val routingLock = Mutex()
    private var lastOsmRoute = 0L
    private val geocodingLock = Mutex()
    private var lastGeocodeAt = 0L
    private val geocodingCache = LinkedHashMap<String, Pair<Long, List<JsonObject>>>()

    suspend fun nearby(origin: Location, args: JsonObject): String {
        val category = args.string("category").ifBlank { "discovery" }
        val query = args.string("query").take(120)
        val destination = args.string("destination").take(200)
        val openNow = args.string("open_now") == "true"
        val label = when (category) {
            "food" -> "restaurants or cafes"
            "gas" -> "gas stations"
            "park" -> "public parks"
            "hike" -> "hiking trailheads"
            "attraction" -> "visitor attractions"
            "discovery" -> "interesting places to visit, including attractions, parks and hiking trailheads"
            else -> return toolError("Choose food, gas, park, hike, attraction, or discovery.")
        }
        if (mapsKey.isBlank()) {
            if (destination.isNotBlank()) return toolError("Search along a route needs a configured Google Maps key.")
            return osmNearby(origin, category, query, openNow)
        }
        val route = if (destination.isNotBlank()) route(origin, destination) else null
        val polyline = route?.obj("polyline")?.string("encodedPolyline").orEmpty()
        if (destination.isNotBlank() && polyline.isBlank()) {
            return toolError("No driving route was found for the destination.")
        }
        val body = buildJsonObject {
            put("textQuery", listOf(query, label).filter { it.isNotBlank() }.joinToString(" "))
            put("pageSize", 5)
            if (openNow) put("openNow", true)
            putJsonObject("routingParameters") {
                put("travelMode", "DRIVE")
                put("routingPreference", "TRAFFIC_AWARE")
                if (polyline.isBlank()) put("origin", latLng(origin))
            }
            if (polyline.isNotBlank()) {
                putJsonObject("searchAlongRouteParameters") { put("polyline", buildJsonObject { put("encodedPolyline", polyline) }) }
            } else {
                putJsonObject("locationBias") {
                    putJsonObject("circle") {
                        put("center", latLng(origin))
                        put("radius", if (category == "hike" || category == "discovery") 18_000.0 else 8_000.0)
                    }
                }
            }
        }
        val result = google(
            "https://places.googleapis.com/v1/places:searchText",
            "places.id,places.displayName,places.formattedAddress,places.primaryType," +
                "places.location,places.currentOpeningHours.openNow,places.googleMapsUri,routingSummaries.legs.duration",
            body,
        )
        return buildJsonObject {
            put("ok", true)
            put("source", "Google Maps")
            putJsonArray("places") {
                result.array("places").forEachIndexed { index, place ->
                    val lat = place.obj("location")?.string("latitude")?.toDoubleOrNull() ?: return@forEachIndexed
                    val lng = place.obj("location")?.string("longitude")?.toDoubleOrNull() ?: return@forEachIndexed
                    val name = place.obj("displayName")?.string("text").orEmpty()
                    val distance = distance(origin, lat, lng)
                    if (destination.isBlank() && distance > 30_000) return@forEachIndexed
                    val legs = result.array("routingSummaries").getOrNull(index)?.array("legs").orEmpty()
                    val driveSeconds = legs.firstOrNull()?.let(::duration)
                    val onwards = legs.getOrNull(1)?.let(::duration)
                    val detour = if (driveSeconds != null && onwards != null && route != null) {
                        duration(route)?.let { (driveSeconds + onwards - it).coerceAtLeast(0.0) }
                    } else null
                    add(buildJsonObject {
                        put("name", name)
                        put("place_id", "place_id:" + place.string("id"))
                        put("address", place.string("formattedAddress"))
                        put("category", place.string("primaryType"))
                        put("straight_line_meters", distance)
                        driveSeconds?.let { put("drive_minutes", (it / 60).roundToInt().coerceAtLeast(1)) }
                        detour?.let { put("estimated_detour_minutes", (it / 60).roundToInt()) }
                        place.obj("currentOpeningHours")?.get("openNow")?.let { put("open_now", it) }
                        put("url", place.string("googleMapsUri"))
                    })
                }
            }
        }.toString()
    }

    suspend fun eta(origin: Location, destination: String, geocode: (String) -> Pair<Double, Double>?): String {
        if (destination.isBlank()) return toolError("A destination is required.")
        if (mapsKey.isBlank()) {
            val point = coordinates(destination) ?: geocode(destination)
                ?: return toolError("Could not locate that destination. Try a full address.")
            val path = "${origin.longitude},${origin.latitude};${point.second},${point.first}"
            val response = routingLock.withLock {
                delay((1_000 - (android.os.SystemClock.elapsedRealtime() - lastOsmRoute)).coerceAtLeast(0))
                lastOsmRoute = android.os.SystemClock.elapsedRealtime()
                http.toolJson(Request.Builder()
                    .url("https://routing.openstreetmap.de/routed-car/route/v1/driving/$path?overview=false")
                    .header("User-Agent", "VoiceChat Android/1.0")
                    .build())
            }
            val route = response.array("routes").firstOrNull() ?: return toolError("No driving route was found.")
            val seconds = route.string("duration").toDoubleOrNull() ?: return toolError("No drive time was returned.")
            return etaResult(destination, seconds, route.string("distance").toDoubleOrNull()?.roundToInt(), false)
        }
        val route = route(origin, destination) ?: return toolError("No driving route was found.")
        val seconds = duration(route)
            ?: return toolError("The route service did not return a drive time.")
        return etaResult(destination, seconds, route.string("distanceMeters").toIntOrNull(), true)
    }

    private fun etaResult(destination: String, seconds: Double, meters: Int?, traffic: Boolean): String {
        return buildJsonObject {
            put("ok", true)
            put("destination", destination)
            put("estimated_minutes", (seconds / 60).roundToInt().coerceAtLeast(1))
            meters?.let { put("distance_meters", it) }
            put("traffic_aware", traffic)
            if (!traffic) {
                put("source", "FOSSGIS routing · © OpenStreetMap contributors")
                put("source_url", "https://www.openstreetmap.org/copyright")
                put("fix_map_url", "https://www.openstreetmap.org/fixthemap")
            }
            put("url", mapsUrl(destination))
        }.toString()
    }

    private fun duration(objectWithDuration: JsonObject): Double? =
        objectWithDuration.string("duration").removeSuffix("s").toDoubleOrNull()

    private fun coordinates(destination: String): Pair<Double, Double>? {
        val lat = destination.substringBefore(',').trim().toDoubleOrNull() ?: return null
        val lng = destination.substringAfter(',', "").trim().toDoubleOrNull() ?: return null
        return (lat to lng).takeIf { lat in -90.0..90.0 && lng in -180.0..180.0 }
    }

    private suspend fun route(origin: Location, destination: String): JsonObject? = google(
        "https://routes.googleapis.com/directions/v2:computeRoutes",
        "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline",
        buildJsonObject {
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE")
            putJsonObject("origin") { putJsonObject("location") { put("latLng", latLng(origin)) } }
            putJsonObject("destination") {
                val point = coordinates(destination)
                if (destination.startsWith("place_id:")) put("placeId", destination.substringAfter(':'))
                else if (point != null) putJsonObject("location") {
                    putJsonObject("latLng") { put("latitude", point.first); put("longitude", point.second) }
                }
                else put("address", destination)
            }
        },
    ).array("routes").firstOrNull()

    private suspend fun google(url: String, fields: String, body: JsonObject): JsonObject =
        http.toolJson(Request.Builder().url(url)
            .header("X-Goog-Api-Key", mapsKey)
            .header("X-Goog-FieldMask", fields)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build())

    // Keyless discovery around the current fix. OSM opening-hours rules are
    // shown as source data; they are not interpreted as verified open now.
    private suspend fun osmNearby(origin: Location, category: String, query: String, openNow: Boolean): String {
        val filters = when (category) {
            "food" -> listOf("[amenity~\"^(restaurant|fast_food|cafe)$\"]")
            "gas" -> listOf("[amenity=fuel]")
            "park" -> listOf("[leisure~\"^(park|nature_reserve)$\"]")
            "hike" -> listOf("[highway=trailhead]")
            "attraction" -> listOf("[tourism~\"^(attraction|museum|viewpoint|gallery)$\"]")
            else -> listOf("[tourism~\"^(attraction|museum|viewpoint)$\"]", "[leisure=park]", "[highway=trailhead]")
        }
        val radius = if (category in setOf("discovery", "hike", "attraction")) 4500 else 2500
        val scope = "(around:$radius,${origin.latitude},${origin.longitude})"
        val expression = "[out:json][timeout:4];(" +
            filters.joinToString("") { "nwr$scope$it[\"name\"];" } + ");out center;"
        val overpass = try {
            withTimeoutOrNull(4_500) {
                http.toolJson(Request.Builder().url("https://overpass.private.coffee/api/interpreter")
                    .header("User-Agent", "VoiceChat Android/1.0")
                    .post(FormBody.Builder().add("data", expression).build()).build()).array("elements")
            }
        } catch (_: IOException) {
            null
        }
        val elements = overpass ?: geocodeNearby(origin, category, radius)
        val keywords = query.split(' ').filter { it.isNotBlank() }
        val candidates = elements.mapNotNull { item ->
            val coordinates = item.obj("center") ?: item
            val lat = coordinates.string("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lng = coordinates.string("lon").toDoubleOrNull() ?: return@mapNotNull null
            val tags = item.obj("tags") ?: return@mapNotNull null
            if (keywords.isNotEmpty() && keywords.none { word ->
                    listOf("name", "cuisine", "brand").any { tags.string(it).contains(word, ignoreCase = true) }
                }) return@mapNotNull null
            if (openNow && tags.string("opening_hours").trim() != "24/7") return@mapNotNull null
            val meters = distance(origin, lat, lng)
            meters to buildJsonObject {
                put("name", tags.string("name"))
                put("category", listOf("amenity", "leisure", "tourism", "highway")
                    .firstNotNullOfOrNull { tags.string(it).takeIf(String::isNotBlank) } ?: category)
                put("coordinates", "$lat,$lng")
                put("straight_line_meters", meters)
                tags.string("opening_hours").takeIf(String::isNotBlank)?.let { put("listed_hours", it) }
                if (tags.string("opening_hours").trim() == "24/7") put("listed_as_24_hours", true)
                put("url", "https://www.openstreetmap.org/${item.string("type")}/${item.string("id")}")
            }
        }.sortedBy { it.first }.distinctBy { it.second.string("name") }
        val selected = if (category == "discovery") candidates.distinctBy { it.second.string("category") } else candidates
        return buildJsonObject {
            put("ok", true)
            put("source", "© OpenStreetMap contributors")
            put("source_url", "https://www.openstreetmap.org/copyright")
            if (openNow) put("coverage", "Only places listed as 24 hours; other opening hours are unverified.")
            putJsonArray("places") { selected.take(5).forEach { add(it.second) } }
        }.toString()
    }

    /** Low-volume, user-triggered search if the public Overpass server is busy. */
    private suspend fun geocodeNearby(origin: Location, category: String, radius: Int): List<JsonObject> {
        val types = when (category) {
            "food" -> listOf("restaurant")
            "gas" -> listOf("fuel")
            "park" -> listOf("park")
            "hike" -> listOf("trailhead")
            "attraction" -> listOf("attraction")
            else -> listOf("attraction", "park", "trailhead")
        }
        val lat = (origin.latitude * 1000).roundToInt() / 1000.0
        val lon = (origin.longitude * 1000).roundToInt() / 1000.0
        val dLat = radius / 111_000.0
        val dLon = dLat / cos(Math.toRadians(lat)).coerceAtLeast(0.1)
        val viewBox = listOf(
            (lon - dLon).coerceAtLeast(-180.0), (lat + dLat).coerceAtMost(90.0),
            (lon + dLon).coerceAtMost(180.0), (lat - dLat).coerceAtLeast(-90.0),
        ).joinToString(",")
        return types.flatMap { type ->
            val key = "$type:$lat,$lon"
            geocodingLock.withLock {
                val now = SystemClock.elapsedRealtime()
                geocodingCache[key]?.takeIf { now - it.first < 120_000 }?.second?.let { return@withLock it }
                delay((1_000 - (now - lastGeocodeAt)).coerceAtLeast(0))
                lastGeocodeAt = SystemClock.elapsedRealtime()
                val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
                    .addQueryParameter("q", "[$type]").addQueryParameter("format", "jsonv2")
                    .addQueryParameter("viewbox", viewBox).addQueryParameter("bounded", "1")
                    .addQueryParameter("extratags", "1").addQueryParameter("limit", "8").build()
                val payload = http.toolResponse(Request.Builder().url(url)
                    .header("User-Agent", "VoiceChat Android/1.0").build())
                val rows = (Json.parseToJsonElement(payload) as JsonArray).filterIsInstance<JsonObject>()
                    .map { row ->
                        buildJsonObject {
                            put("lat", row.string("lat"))
                            put("lon", row.string("lon"))
                            put("type", row.string("osm_type"))
                            put("id", row.string("osm_id"))
                            putJsonObject("tags") {
                                put("name", row.string("name").ifBlank { row.string("display_name").substringBefore(',') })
                                put(row.string("category"), row.string("type"))
                                row.obj("extratags")?.get("opening_hours")?.let { put("opening_hours", it) }
                            }
                        }
                    }
                if (geocodingCache.size >= 32) geocodingCache.remove(geocodingCache.keys.first())
                geocodingCache[key] = lastGeocodeAt to rows
                rows
            }
        }
    }

    private fun latLng(location: Location) = buildJsonObject {
        put("latitude", location.latitude)
        put("longitude", location.longitude)
    }

    private fun distance(origin: Location, lat: Double, lng: Double): Int {
        val result = FloatArray(1)
        Location.distanceBetween(origin.latitude, origin.longitude, lat, lng, result)
        return result[0].roundToInt()
    }

    companion object {
        fun mapsUrl(destination: String): String {
            val url = "https://www.google.com/maps/dir/".toHttpUrl().newBuilder()
                .addQueryParameter("api", "1")
                .addQueryParameter("travelmode", "driving")
                .addQueryParameter("dir_action", "navigate")
            if (destination.startsWith("place_id:")) {
                url.addQueryParameter("destination", "Destination")
                url.addQueryParameter("destination_place_id", destination.substringAfter(':'))
            } else {
                url.addQueryParameter("destination", destination)
            }
            return url.build().toString()
        }
    }
}
