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
                    "or recent information that is not news — prices, products, people, " +
                    "schedules, events. Returns results with title, URL and snippet; " +
                    "summarize these results and do not answer from memory when fresh " +
                    "information is requested. Use get_news for news, headlines or markets.",
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
     * A focused news digest instead of a generic web search: each section is
     * fetched as recent, dated articles, so the model never has to guess whether
     * a snippet is today's story.
     */
    private val news = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "get_news")
            put(
                "description",
                "Get today's news as dated items grouped by section: world (top " +
                    "international stories), markets (stock market conditions and what " +
                    "moved them) and ai (model releases, research papers, open-source " +
                    "models and library changes). Call this for every request about the " +
                    "news, headlines, what happened today or the market. \"all\" returns " +
                    "the three sections together.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("section") {
                        put("type", "string")
                        put(
                            "description",
                            "Section to fetch; all returns world, markets and ai together.",
                        )
                        putJsonArray("enum") {
                            add(NewsSections.ALL)
                            add(NewsSections.WORLD)
                            add(NewsSections.MARKETS)
                            add(NewsSections.AI)
                        }
                    }
                }
                putJsonArray("required") {}
            }
        }
    }

    /**
     * Handed to the realtime model so it can escalate a hard turn to a stronger
     * text model without the user switching anything.
     */
    private val deepReasoning = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", HANDOFF_NAME)
            put(
                "description",
                "Consult a stronger reasoning model for work that needs care: " +
                    "multi-step arithmetic or logic, code, derivations, planning, " +
                    "comparing options, or analysing a long document. Do not use it " +
                    "for small talk, weather or current facts, and not for anything " +
                    "you can answer confidently in one step. Pass a self-contained " +
                    "task, because the reasoning model cannot see this conversation " +
                    "unless you restate it.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("task") {
                        put("type", "string")
                        put(
                            "description",
                            "What must be worked out, including every fact the " +
                                "reasoning model needs.",
                        )
                    }
                    putJsonObject("depth") {
                        put("type", "string")
                        put("description", "brief for a short answer, detailed for full working.")
                        putJsonArray("enum") {
                            add("brief")
                            add("detailed")
                        }
                    }
                }
                putJsonArray("required") { add("task") }
            }
        }
    }

    /**
     * The client `web_search` is left out when the session uses the server-side
     * search, so the model has exactly one way to search. `deep_reasoning` is
     * only offered when the handoff is configured: it is executed by
     * `RealtimeSession`, not by [ToolRegistry].
     */
    fun json(includeWebSearch: Boolean, includeHandoff: Boolean = false): String = buildJsonArray {
        add(currentDateTime)
        add(location)
        add(weather)
        if (includeWebSearch) add(webSearch)
        add(news)
        if (includeHandoff) add(deepReasoning)
    }.toString()

    /** Convenience for callers that do not care about the distinction. */
    val all: String by lazy { json(includeWebSearch = true) }

    const val HANDOFF_NAME = "deep_reasoning"
}
