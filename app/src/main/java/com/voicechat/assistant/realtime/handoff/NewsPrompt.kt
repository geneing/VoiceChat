package com.voicechat.assistant.realtime.handoff

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * News digest request and result shaping for the large text model.
 *
 * The realtime models answer "what's the news" badly on their own: they either
 * search a homepage and read a cached sidebar aloud, or answer from stale
 * training data. News turns are therefore composed by the reasoning model, which
 * receives the app's dated search results and writes one digest the realtime
 * model only has to read out.
 */
object NewsPrompt {

    private val DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)
    private const val MAX_REFERENCE_CHARS = 8_000

    fun system(today: LocalDate): String = buildString {
        append(
            """
            You are the news editor for a voice assistant. Today is ${today.format(DATE)}.
            Write the digest the assistant will read aloud.

            Structure, in this order:
            1. World: the most impactful world news of the day, in 5-6 sentences.
               Lead with what matters most, not with what came first.
            2. Markets: 5-6 sentences on the most impactful news in the US and
               world markets — indices with their moves, bonds, and technology
               shares — plus the trend behind each move (rates, earnings,
               rotation, risk appetite).
            3. AI: two or three paragraphs of technical AI news: influential
               papers discussed on Reddit, important model releases, open-source
               models and library changes. Explain why each matters, not just
               that it happened.

            Rules: you have no web access for this request; the app has attached
            dated search results and they are your only source of facts. Give
            every story its date and, where it matters, its source. If a story is
            older than today, say how old it is; never present an older story as
            today's news. If a section has nothing recent, say that instead of
            filling it. No filler, no praise, no restating of the question. Write
            plain spoken text with no markdown, no bullets and no headings, at
            most 550 words. Write numbers and index names as digits (S&P 500,
            Nasdaq 100, 0.95%), not as spelled-out words.
            """.trimIndent(),
        )
    }

    /** One user message: the material the app searched plus the task. */
    fun user(today: LocalDate, reference: String?): String = buildString {
        if (!reference.isNullOrBlank()) {
            append("Dated search results from the app:\n")
            append(reference.take(MAX_REFERENCE_CHARS))
            append("\n\n")
        }
        append("Write today's digest (")
        append(today.format(DATE))
        append(") in the three sections and speak-ready style described above.")
    }

    /** `function_call_output` handed back to the realtime model. */
    fun toolOutput(digest: String, model: String, asOf: LocalDate, failed: String? = null): String =
        buildJsonObject {
            put("ok", failed == null)
            put("as_of", asOf.toString())
            if (failed == null) {
                put("model", model)
                put("digest", digest)
                put(
                    "hint",
                    "Read this digest as the answer: lead with the world news, then " +
                        "the markets, then the AI section. Keep the dates, do not add " +
                        "stories of your own, and do not mention the search.",
                )
            } else {
                put("error", failed)
                put(
                    "hint",
                    "Say briefly that today's news could not be fetched and do not " +
                        "invent stories.",
                )
            }
        }.toString()
}
