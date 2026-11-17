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
     * A focused news digest instead of a generic web search. The app composes it
     * (search plus the news model), so the tool takes no arguments and is called
     * once per news request.
     */
    private val news = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", "get_news")
            put(
                "description",
                "Get today's news digest: the most impactful world news, the most " +
                    "impactful US and world market news (indices, bonds, technology and " +
                    "the trends behind them), and a longer AI section (papers, model " +
                    "releases, open-source models, library changes). Call this once for " +
                    "any request about the news, headlines, what happened today or the " +
                    "market.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {}
                putJsonArray("required") {}
            }
        }
    }

    /**
     * Device memory: the model asks for facts the app stored earlier, stores a
     * fact the user wants remembered, or forgets one. All three run in
     * `ToolRegistry` against the local database; the model never sees the table.
     */
    private val recallMemory = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", RECALL_NAME)
            put(
                "description",
                "Look up what you remember about the user from earlier " +
                    "conversations: names, preferences, people, places, routines " +
                    "and projects stored on this device. Call it when the user " +
                    "refers to something personal or earlier (\"what did I tell " +
                    "you about…\", \"my usual…\", \"where do I live\") and before " +
                    "answering from assumption. Returns matching facts or a count " +
                    "of none.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put(
                            "description",
                            "What to look for, in a few words: the topic, person " +
                                "or preference the user is asking about.",
                        )
                    }
                }
                putJsonArray("required") { add("query") }
            }
        }
    }

    private val rememberFact = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", REMEMBER_NAME)
            put(
                "description",
                "Store one durable fact about the user for later conversations. " +
                    "Call it when the user asks to be remembered or states a " +
                    "lasting preference, constraint (allergies, diet), person, " +
                    "place, routine or project. Do not store small talk, one-off " +
                    "requests, passwords, keys or card numbers.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("fact") {
                        put("type", "string")
                        put(
                            "description",
                            "The fact as one short third-person sentence, for " +
                                "example \"The user is allergic to peanuts.\"",
                        )
                    }
                    putJsonObject("kind") {
                        put("type", "string")
                        put("description", "Optional category for the fact.")
                        putJsonArray("enum") {
                            add("identity")
                            add("preference")
                            add("person")
                            add("place")
                            add("routine")
                            add("project")
                            add("note")
                        }
                    }
                }
                putJsonArray("required") { add("fact") }
            }
        }
    }

    private val forgetFact = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", FORGET_NAME)
            put(
                "description",
                "Delete a remembered fact about the user. Call it when the user " +
                    "asks you to forget something or corrects a fact you recited.",
            )
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("query") {
                        put("type", "string")
                        put(
                            "description",
                            "The fact to delete, in a few words.",
                        )
                    }
                }
                putJsonArray("required") { add("query") }
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
     * `RealtimeSession`, not by [ToolRegistry]. The memory tools are the only
     * ones a session with device memory on always gets.
     */
    fun json(
        includeWebSearch: Boolean,
        includeHandoff: Boolean = false,
        includeMemory: Boolean = false,
    ): String = buildJsonArray {
        add(currentDateTime)
        add(location)
        add(weather)
        if (includeWebSearch) add(webSearch)
        add(news)
        if (includeMemory) {
            add(recallMemory)
            add(rememberFact)
            add(forgetFact)
        }
        if (includeHandoff) add(deepReasoning)
    }.toString()

    /** Convenience for callers that do not care about the distinction. */
    val all: String by lazy { json(includeWebSearch = true) }

    const val HANDOFF_NAME = "deep_reasoning"
    const val NEWS_NAME = "get_news"
    const val RECALL_NAME = "recall_memory"
    const val REMEMBER_NAME = "remember_fact"
    const val FORGET_NAME = "forget_fact"
}
