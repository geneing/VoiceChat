package com.voicechat.assistant.realtime.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tool schemas sent in `session.update`. Pure so the payload can be asserted in
 * unit tests; [ToolRegistry] only executes them.
 */
object ToolDefinitions {

    private val currentDateTime = buildJsonObject {
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
                // Qwen Audio's function-call parser rejects a schema without
                // `required` (internal service error), even when no argument is
                // mandatory.
                putJsonArray("required") {}
            }
        }
    }

    private val location = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "get_location")
            put(
                "description",
                "Get the device's current or last known coordinates. Call this for " +
                    "questions about the user's location, weather, nearby places, " +
                    "local time or directions instead of saying the location is " +
                    "unavailable.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
                putJsonArray("required") {}
            }
        }
    }

    private val weather = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "get_weather")
            put(
                "description",
                "Get today's weather for the user's current location: temperature, " +
                    "feels-like, humidity, wind, rain chance and the daily high/low. " +
                    "Call this for any weather question instead of searching the web.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
                putJsonArray("required") {}
            }
        }
    }

    private val webSearch = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "web_search")
            put(
                "description",
                "Search the public web before answering questions about current " +
                    "or recent information, including news, today's headlines, " +
                    "weather, prices, or events. Returns results with title, URL " +
                    "and snippet; summarize these results and do not answer from " +
                    "memory when fresh information is requested.",
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
    }

    /**
     * The client `web_search` is left out when the session uses the server-side
     * search, so the model has exactly one way to search.
     */
    fun json(includeWebSearch: Boolean): String = buildJsonArray {
        add(currentDateTime)
        add(location)
        add(weather)
        if (includeWebSearch) add(webSearch)
    }.toString()

    /** Convenience for callers that do not care about the distinction. */
    val all: String by lazy { json(includeWebSearch = true) }
}
