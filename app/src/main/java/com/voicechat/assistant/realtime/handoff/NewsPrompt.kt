package com.voicechat.assistant.realtime.handoff

import com.voicechat.assistant.data.model.NewsSearchMode
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
 * gets either the app's dated search results ([NewsSearchMode.APP]) or the
 * vendor's own web search ([NewsSearchMode.MODEL]) and writes one short digest
 * the realtime model only has to read out.
 */
object NewsPrompt {

    private val DATE = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.US)
    private const val MAX_REFERENCE_CHARS = 8_000

    fun system(today: LocalDate, search: NewsSearchMode): String = buildString {
        append(
            """
            You are the news editor for a voice assistant. Today is ${today.format(DATE)}.
            Write the digest the assistant will read aloud.

            Structure, in this order:
            1. Three top world stories, one or two sentences each.
            2. Today's market conditions: the main index moves with numbers, and
               what moved them.
            3. Five technical AI items: influential papers discussed on Reddit,
               important model releases, open-source models, library changes.

            Rules: use only facts you can verify ${if (search == NewsSearchMode.OFF) "from memory" else "from the material or your search"}. Give every story its
            date and, where it matters, its source. If a story is older than today,
            say how old it is; never present an older story as today's news. If a
            section has nothing recent, say that instead of filling it. No filler,
            no praise, no restating of the question. Write plain spoken text with
            no markdown, no bullets and no headings, at most 250 words. Write
            numbers and index names as digits (S&P 500, Nasdaq 100, 0.95%), not as
            spelled-out words.
            """.trimIndent(),
        )
        when (search) {
            NewsSearchMode.MODEL -> append(
                "\n\nWeb search is enabled for this request. Search for today's news in " +
                    "each section before writing and prefer results published today.",
            )
            NewsSearchMode.APP -> append(
                "\n\nYou have no web access for this request. The app has attached dated " +
                    "search results; treat them as the only source of facts.",
            )
            NewsSearchMode.OFF -> append(
                "\n\nYou have no web access for this request. If you cannot verify what " +
                    "happened today, say so plainly in one sentence; do not invent stories, " +
                    "numbers or sources.",
            )
        }
    }

    /** One user message: the material (when the app searched) plus the task. */
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

    /**
     * True when the digest is a refusal to give today's news, which is what a
     * model without working web search answers even when the request asked for
     * it (`qwen3.8-flash` does this; `qwen-plus` searches). The session then
     * retries with the app's search results.
     */
    fun looksUnverified(digest: String): Boolean {
        val text = digest.lowercase(Locale.US)
        if (text.length > 600) return false
        return UNVERIFIED_MARKERS.any { text.contains(it) }
    }

    private val UNVERIFIED_MARKERS = listOf(
        "knowledge cutoff",
        "cannot provide real-time",
        "cannot provide real time",
        "no access to live",
        "do not have access to live",
        "don't have access to live",
        "unable to access live",
        "cannot access live",
        "no verified",
        "cannot verify",
    )

    /** `function_call_output` handed back to the realtime model. */    fun toolOutput(digest: String, model: String, asOf: LocalDate, failed: String? = null): String =
        buildJsonObject {
            put("ok", failed == null)
            put("as_of", asOf.toString())
            if (failed == null) {
                put("model", model)
                put("digest", digest)
                put(
                    "hint",
                    "Read this digest as the answer: lead with the world stories, then " +
                        "the market, then the AI items. Keep the dates, do not add stories " +
                        "of your own, and do not mention the search.",
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
