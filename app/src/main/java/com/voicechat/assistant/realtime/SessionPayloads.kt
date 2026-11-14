package com.voicechat.assistant.realtime

import com.voicechat.assistant.realtime.handoff.HandoffPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Builds the client → server events. Keeping this pure makes protocol changes
 * reviewable and unit testable.
 */
object SessionPayloads {

    private val SILENCE_FRAME: String = run {
        val bytes = ByteArray(6_400) // 200 ms of 16 kHz s16le mono silence
        Base64.getEncoder().encodeToString(bytes)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_RECAP_TURNS = 12
    private const val MAX_RECAP_TURN_CHARS = 600
    private const val MAX_RECAP_CHARS = 4_000
    private val DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)

    /**
     * A reconnected session is a *new* server conversation, so the turns this
     * client still remembers are handed back as a short recap appended to the
     * instructions. Carrying the recap in the instructions (instead of replaying
     * `conversation.item.create` messages) keeps the reconnect payload identical
     * in shape to the one that already works for this vendor.
     */
    fun resumeInstructions(instructions: String, turns: List<HandoffPrompt.Turn>): String {
        val lines = turns.mapNotNull { turn ->
            val text = turn.text.trim().replace(WHITESPACE, " ")
            if (text.isEmpty()) return@mapNotNull null
            val label = if (turn.role == "assistant") "Assistant" else "User"
            "$label: ${text.take(MAX_RECAP_TURN_CHARS)}"
        }.takeLast(MAX_RECAP_TURNS)
        if (lines.isEmpty()) return instructions
        val recap = lines.joinToString("\n").take(MAX_RECAP_CHARS)
        return buildString {
            append(instructions.trim())
            append(
                "\n\nEarlier in this conversation (restored after a connection drop, " +
                    "so the audio is gone; treat it as what was already said):\n",
            )
            append(recap)
            append(
                "\n\nContinue the conversation naturally. Do not read this recap back " +
                    "and do not mention the reconnect.",
            )
        }
    }

    fun sessionUpdate(config: SessionConfig, now: LocalDate = LocalDate.now()): String {
        val instructions = buildString {
            append(config.instructions.trim())
            // The models have no clock of their own, so every "today" question
            // (news, prices, events) needs the date in the prompt or the answer
            // drifts to whatever the training data calls recent.
            append("\n\nToday is ")
            append(now.format(DATE_FORMAT))
            append(". Use it for anything time-dependent and never call an older " +
                "story today's news.")
            // The pacing is applied locally to the decoded PCM; the realtime API
            // ignores every server-side speed hint, so asking for a rate here
            // only produced the same normal-speed audio.
            append(
                "\n\nDelivery: natural phrasing and crisp articulation at a brisk " +
                    "conversational pace.",
            )
            if (config.toolsJson?.contains("\"name\":\"get_weather\"") == true) {
                append(
                    "\n\nWeather: call get_weather for any question about the weather, " +
                        "temperature, rain or what to wear outside. It resolves the user's " +
                        "location itself, so do not ask for a city first and do not search " +
                        "the web for it.",
                )
            }
            if (config.toolsJson?.contains("\"name\":\"get_location\"") == true) {
                append(
                    "\n\nLocation: when the user asks where they are, about the weather, " +
                        "nearby places, local time, directions or anything else that depends " +
                        "on their position, call the get_location function first and use the " +
                        "returned coordinates. Never say you cannot access the user's " +
                        "location without calling it. If the call fails, say briefly why and " +
                        "offer to use a city name instead.",
                )
            }
            if (config.toolsJson?.contains("\"name\":\"web_search\"") == true) {
                append(
                    "\n\nMandatory web search: for any request about current or recent " +
                        "facts—including news, headlines, weather, prices, or events—" +
                        "call the web_search function before answering. Do not answer " +
                        "from memory or claim you cannot access live information without " +
                        "first calling it. After the results return, summarize them " +
                        "concisely for speech and distinguish reported facts from " +
                        "uncertainty. Treat each result's published date as the age of " +
                        "that story: a search snippet without a recent date is not today's " +
                        "news, so leave it out or say how old it is instead of presenting " +
                        "it as current. If the search returns no useful results, say the " +
                        "search did not find enough information; never invent results.",
                )
            } else if (config.nativeSearch) {
                append(
                    "\n\nCurrent information: use the enabled native web search for " +
                        "requests about current or recent facts, including news, " +
                        "headlines, weather, prices, or events. Do not answer from " +
                        "memory or claim you cannot access live information. If search " +
                        "returns no useful results, say so plainly; never invent results.",
                )
            }
            if (config.toolsJson?.contains("\"name\":\"deep_reasoning\"") == true) {
                append(
                    "\n\nDeep reasoning: for work the user would notice getting wrong — " +
                        "multi-step arithmetic or logic, code, derivations, planning, " +
                        "comparing options, or a long document — call deep_reasoning " +
                        "with a self-contained task description before you answer. " +
                        "Do not call it for small talk, weather or current facts " +
                        "(search for those), and not for anything you can answer " +
                        "confidently in one step. One short spoken sentence such as " +
                        "\"let me work that through\" is fine while it runs. When the " +
                        "result returns, lead with the conclusion from spoken_summary, " +
                        "keep the reply short for speech, and use answer only for the " +
                        "detail the user asked for; never read it out verbatim. A spoken " +
                        "turn receives spoken_summary only, so speak that; the full answer " +
                        "stays in the chat. If it " +
                        "fails, answer from your own knowledge and say briefly that " +
                        "deep reasoning was unavailable.",
                )
            }
            if (!config.toolsJson.isNullOrBlank()) {
                append(
                    "\n\nTool use: do not announce a tool. If you say one short sentence " +
                        "while working, it belongs to the same response as the function " +
                        "call — never end a turn by saying you will look something up, " +
                        "and never promise a result you have not fetched. After a tool " +
                        "returns, answer the question; do not narrate the tool again. A " +
                        "short fragment or continuation from the user (\"I mean, X\", " +
                        "\"in Reddit\") continues the previous request: act on it instead " +
                        "of asking what they meant.",
                )
            }
            if (config.languageHint.isNotBlank()) {
                append("\n\nPreferred language: ")
                append(config.languageHint.trim())
            }
        }
        val session = buildJsonObject {
            put("modalities", buildJsonArray {
                add(JsonPrimitive("text"))
                add(JsonPrimitive("audio"))
            })
            put("voice", config.voice)
            put("instructions", instructions)
            put("audio", buildJsonObject {
                put("input", buildJsonObject {
                    put("format", buildJsonObject {
                        put("type", "pcm")
                        put("sample_rate", 16_000)
                        put("sample_format", "s16le")
                        put("channels", 1)
                        put("packing", "interleaved")
                        put("channel_layout", "mono")
                    })
                })
                put("output", buildJsonObject {
                    put("voice", config.voice)
                    put("format", buildJsonObject {
                        put("type", "pcm")
                        put("sample_rate", 24_000)
                    })
                })
            })
            if (config.turnDetection.isNotBlank()) {
                put("turn_detection", buildJsonObject {
                    put("type", config.turnDetection)
                    // smart_turn/smart_turn_v2 are semantic; the vendor rejects
                    // acoustic tuning fields for them (or ignores them).
                    if (config.turnDetection != "smart_turn" &&
                        config.turnDetection != "smart_turn_v2"
                    ) {
                        put("threshold", config.vadThreshold.toDouble())
                        put("silence_duration_ms", config.silenceMs)
                        // Keep enough audio before the VAD trigger; the vendor
                        // default of 300 ms cuts the first word of a turn.
                        put("prefix_padding_ms", config.prefixPaddingMs)
                    }
                })
            } else {
                put("turn_detection", kotlinx.serialization.json.JsonNull)
            }
            if (config.inputTranscription) {
                put("input_audio_transcription", buildJsonObject {
                    put("model", config.transcriptionModel)
                })
            }
            if (!config.toolsJson.isNullOrBlank()) {
                runCatching {
                    val parsed = kotlinx.serialization.json.Json.parseToJsonElement(config.toolsJson)
                    if (parsed is JsonArray) put("tools", parsed)
                }
            }
            if (config.nativeSearch) {
                put("enable_search", true)
                put(
                    "search_options",
                    buildJsonObject { put("enable_source", config.searchSources) },
                )
            }
        }
        return buildJsonObject {
            put("type", "session.update")
            put("session", session)
        }.toString()
    }

    fun textTurn(text: String, images: List<String> = emptyList()): List<String> {
        val events = mutableListOf<String>()
        if (images.isNotEmpty()) {
            // Images ride on the audio buffer: append at least one audio frame,
            // then the images, then commit so the server accepts them.
            events += audioAppend(SILENCE_FRAME)
            images.forEach { events += imageAppend(it) }
            events += commitAudio()
        }
        events += buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message")
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject {
                        put("type", "input_text")
                        put("text", text)
                    })
                })
            })
        }.toString()
        events += responseCreate(listOf("text"))
        return events
    }

    fun responseCreate(modalities: List<String>): String = buildJsonObject {
        put("type", "response.create")
        put("response", buildJsonObject {
            put("modalities", buildJsonArray { modalities.forEach { add(JsonPrimitive(it)) } })
        })
    }.toString()

    /**
     * A corrective `response.create`: the previous response ended on a promise
     * to act without calling a tool, so the follow-up carries a per-response
     * instruction instead of a new user item.
     */
    fun responseNudge(modalities: List<String>, instructions: String): String =
        buildJsonObject {
            put("type", "response.create")
            put("response", buildJsonObject {
                put("modalities", buildJsonArray { modalities.forEach { add(JsonPrimitive(it)) } })
                put("instructions", instructions)
            })
        }.toString()

    fun functionOutputItem(callId: String, outputJson: String): String = buildJsonObject {
        put("type", "conversation.item.create")
        put("item", buildJsonObject {
            put("type", "function_call_output")
            put("call_id", callId)
            put("output", outputJson)
        })
    }.toString()

    fun functionCallOutput(callId: String, outputJson: String, modalities: List<String>): List<String> {
        return listOf(functionOutputItem(callId, outputJson), responseCreate(modalities))
    }

    fun cancelResponse(): String = buildJsonObject {
        put("type", "response.cancel")
    }.toString()

    fun audioAppend(base64: String): String = buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", base64)
    }.toString()

    fun imageAppend(base64: String): String = buildJsonObject {
        put("type", "input_image_buffer.append")
        put("image", base64)
    }.toString()

    fun commitAudio(): String = buildJsonObject {
        put("type", "input_audio_buffer.commit")
    }.toString()

    fun clearAudio(): String = buildJsonObject {
        put("type", "input_audio_buffer.clear")
    }.toString()

    fun silenceFrame(): String = SILENCE_FRAME

    fun aoqBootstrapBody(clientIp: String): String = buildJsonObject {
        put("clientIp", clientIp)
    }.toString()
}
