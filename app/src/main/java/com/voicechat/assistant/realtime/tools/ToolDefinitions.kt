package com.voicechat.assistant.realtime.tools

import kotlinx.serialization.json.add
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private data class Field(
        val name: String,
        val description: String,
        val type: String = "string",
        val required: Boolean = false,
    )

    private fun tool(name: String, description: String, vararg fields: Field): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties") {
                    fields.forEach { field ->
                        putJsonObject(field.name) {
                            put("type", field.type)
                            put("description", field.description)
                        }
                    }
                }
                putJsonArray("required") { fields.filter { it.required }.forEach { add(it.name) } }
            }
        }
    }

    private val weather = tool(
        "get_weather", "Get current conditions or a forecast. Use for all weather questions.",
        Field("location", "City or destination; omit for the phone's current location."),
        Field("start", "today, tomorrow, or a local YYYY-MM-DD date. Default today."),
        Field("days", "Number of forecast days beginning at start, e.g. 7 or 10.", "integer"),
        Field("hour", "Optional local hour 0–23 for a single-day hourly request.", "integer"),
    )

    internal val drivingTools = listOf(
        tool("find_nearby", "Find real places near the device. For 'what is nearby?' choose discovery.",
            Field("category", "food, gas, park, hike, attraction, or discovery."),
            Field("query", "Optional cuisine, business name, or other place filter."),
            Field("open_now", "Require verified current opening status.", "boolean"),
            Field("destination", "Optional destination to search along a driving route toward it."),
        ),
        tool("start_navigation", "Open driving directions in Maps only when the user asks.",
            Field("destination", "Explicit destination name/address or exact place_id from find_nearby.", required = true),
        ),
        tool("get_trip_eta", "Estimate drive time from the device to a destination.",
            Field("destination", "Explicit destination name/address or exact place_id from find_nearby.", required = true),
        ),
        tool("call_phone", "Place a cellular call only on an explicit user request. Ask any confirmation returned by the tool.",
            Field("target", "Exact contact name, optionally ending in mobile, home or work, or a phone number.", required = true),
        ),
        tool("play_pocket_casts", "Open or control Pocket Casts. Find named or recent episodes in the catalog.",
            Field("action", "open, resume, pause, next, skip_forward, skip_backward, find_episode, latest_episode, open_episode, or play_search.", required = true),
            Field("query", "Show name for latest_episode; episode/topic for find_episode; name to ask Pocket Casts to play for play_search."),
            Field("url", "Exact catalog result link for open_episode."),
            Field("seconds", "Skip duration for skip_forward or skip_backward.", "integer"),
        ),
        tool("prepare_whatsapp_message", "Prepare a one-to-one WhatsApp draft for use when parked. WhatsApp requires a tap to send.",
            Field("target", "Contact name or international phone number.", required = true),
            Field("message", "The exact message the user wants to send.", required = true),
        ),
        tool("confirm_phone_action", "Complete a pending phone call or WhatsApp draft only after the user explicitly confirms on a later turn."),
        tool("get_reddit_briefing", "Find recent dated Reddit discussions and summarize the themes with thread links.",
            Field("topic", "Topic or community name; defaults to machine learning."),
            Field("communities", "Optional comma-separated subreddit names. For ML defaults to MachineLearning, LocalLLaMA and StableDiffusion."),
            Field("days", "Lookback in days; default 7, use 1 for today or the last day.", "integer"),
        ),
        tool("save_for_later", "Save a local note, link, place or recap, or list recent saved items.",
            Field("action", "save or list.", required = true),
            Field("title", "Short label for the thing to save."),
            Field("note", "Note or recap to save, using the current conversation."),
            Field("url", "Original HTTPS link, if present."),
        ),
    )

    internal val drivingNames = drivingTools.map {
        it.getValue("function").let { fn -> (fn as JsonObject).getValue("name").toString().trim('"') }
    }.toSet()

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
        includeTutor: Boolean = false,
    ): String = buildJsonArray {
        add(tool("switch_conversation_mode", "Switch app modes ONLY on an explicit request, never for quoted practice or translation text.",
            Field("mode", "assistant or spanish_tutor", required = true)))
        if (includeTutor) {
            add(tutorProgress)
            add(tool("recall_tutor_progress", "Read the Spanish learning record. Use query for a word, grammar point or old discussion; omit for an overview.",
                Field("query", "Optional word, grammar topic or discussion to retrieve.")))
            if (includeMemory) {
                add(recallMemory)
                add(rememberFact)
                add(forgetFact)
            }
            if (includeHandoff) add(deepReasoning)
            return@buildJsonArray
        }
        add(currentDateTime)
        add(location)
        add(weather)
        drivingTools.forEach { add(it) }
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

    private val tutorProgress = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", "record_tutor_progress")
            put("description", "Silently save observed Spanish practice after each evaluated answer. Updates merge; never count the tutor's examples as learner successes.")
            put("parameters", buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { })
                put("properties", buildJsonObject {
                    listOf(
                        "level" to "Estimated CEFR level A1/A2/B1/B2/C1/C2; omit unless supported by several turns.",
                        "level_evidence" to "Concrete independent performance supporting a level change.",
                        "summary" to "Cumulative progress, strengths, weaknesses, past discussion topics and next steps. Up to 1800 characters.",
                        "activity" to "Scene, exercise type and example sentence just used, to avoid repetition.",
                    ).forEach { (name, description) ->
                        put(name, buildJsonObject { put("type", "string"); put("description", description) })
                    }
                    listOf("vocabulary", "grammar").forEach { name ->
                        put(name, buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject {
                                put("type", "object")
                                put("required", buildJsonArray { add(JsonPrimitive("topic")); add(JsonPrimitive("outcome")) })
                                put("properties", buildJsonObject {
                                    put("topic", buildJsonObject { put("type", "string"); put("description", "Canonical word/phrase or grammar point.") })
                                    put("outcome", buildJsonObject {
                                        put("type", "string")
                                        put("enum", buildJsonArray { listOf("introduced", "correct", "needs_work").forEach { add(JsonPrimitive(it)) } })
                                    })
                                    put("note", buildJsonObject { put("type", "string"); put("description", "Meaning, observed mistake, correction or pronunciation cue.") })
                                })
                            })
                        })
                    }
                })
            })
        })
    }
}
