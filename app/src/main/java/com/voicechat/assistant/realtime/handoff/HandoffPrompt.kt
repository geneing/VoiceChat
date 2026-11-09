package com.voicechat.assistant.realtime.handoff

import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Request building, answer parsing and result shaping for the handoff. Pure so
 * the protocol can be asserted in unit tests without a network or a device.
 */
object HandoffPrompt {

    const val ANSWER_HEADING = "## Answer"
    const val SUMMARY_HEADING = "## Spoken summary"

    private const val MAX_TURN_CHARS = 1_200
    private const val MAX_SUMMARY_CHARS = 420
    private const val MAX_REFERENCE_CHARS = 6_000

    /** One conversation turn. `role` is `user` or `assistant`. */
    data class Turn(val role: String, val text: String)

    data class Sections(val answer: String, val summary: String)

    val SYSTEM: String = """
        You are the deep-reasoning consultant for a voice assistant. The
        assistant has handed you a task that needs more careful work than it can
        do in one conversational step. You never talk to the user directly: the
        assistant reads your answer and speaks it.

        Work the task out properly. Be correct and complete, show the essential
        steps, and never invent facts, sources or tool results. When the task
        needs information you do not have, say exactly what is missing instead of
        guessing. Use markdown in the answer; the spoken summary must stay plain
        text.

        Reply exactly in this shape:

        $ANSWER_HEADING
        <the full answer, as detailed as the task needs>

        $SUMMARY_HEADING
        <2-4 sentences the assistant can read aloud: lead with the result, keep
        numbers and names exact, no markdown, no lists>
    """.trimIndent()

    /**
     * Assembled chat messages: one system prompt plus one user dossier holding
     * the recent conversation and the task itself.
     */
    fun messages(
        transcript: List<Turn>,
        task: String,
        contextTurns: Int,
        depth: String = "detailed",
        now: ZonedDateTime = ZonedDateTime.now(),
    ): List<Turn> {
        val recent = if (contextTurns <= 0) emptyList() else transcript.takeLast(contextTurns)
        val dossier = buildString {
            if (recent.isNotEmpty()) {
                append("Conversation so far:\n")
                recent.forEach { turn ->
                    append(turn.role)
                    append(": ")
                    append(turn.text.trim().take(MAX_TURN_CHARS))
                    append('\n')
                }
                append('\n')
            }
            append("Task: ")
            append(task.trim())
            append("\nDepth: ")
            append(depth)
            append("\nCurrent date and time: ")
            append(now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
            append(" (")
            append(now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US))
            append(')')
        }
        return listOf(Turn("system", SYSTEM), Turn("user", dossier))
    }

    /** OpenAI-compatible streaming body for `/chat/completions`. */
    fun requestBody(config: HandoffConfig, messages: List<Turn>): String = buildJsonObject {
        put("model", config.resolvedModel)
        putJsonArray("messages") {
            messages.forEach { message ->
                addJsonObject {
                    put("role", message.role)
                    put("content", message.text)
                }
            }
        }
        put("stream", true)
        putJsonObject("stream_options") { put("include_usage", true) }
        when (config.modelInfo.thinkingMode) {
            // Only hybrid models take a budget; the others reject or ignore it.
            ThinkingMode.BUDGET -> if (config.thinking) {
                put("enable_thinking", true)
                put("thinking_budget", config.thinkingBudget)
            } else {
                put("enable_thinking", false)
            }
            ThinkingMode.ALWAYS, ThinkingMode.NONE -> Unit
        }
        put("max_tokens", config.maxAnswerTokens)
        put("temperature", 0.3)
    }.toString()

    /** `function_call_output` payload handed back to the realtime model. */
    fun toolOutput(trace: HandoffTrace): String = buildJsonObject {
        put("ok", !trace.failed)
        put("model", trace.model)
        if (trace.failed) {
            put("error", trace.error ?: "Deep reasoning failed")
            put(
                "hint",
                "Answer from your own knowledge, say briefly that deep reasoning was " +
                    "unavailable, and do not ask the user to retry.",
            )
        } else {
            put("spoken_summary", trace.summary)
            put("answer", trace.answer)
        }
        put("reasoning_tokens", trace.reasoningTokens)
        put("elapsed_ms", trace.elapsedMs)
    }.toString()

    fun toolError(model: String, reason: String, elapsedMs: Long): String = toolOutput(
        HandoffTrace(
            model = model,
            task = "",
            answer = "",
            summary = "",
            reasoning = "",
            usage = com.voicechat.assistant.data.model.TokenUsage(),
            reasoningTokens = 0,
            elapsedMs = elapsedMs,
            costUsd = 0.0,
            error = reason,
        ),
    )

    /**
     * Answer and spoken summary split out of the model output. Falls back to the
     * whole text and its first sentences when the model ignores the headings.
     */
    fun parseSections(text: String): Sections {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return Sections("", "")
        val answerAt = trimmed.indexOf(ANSWER_HEADING, ignoreCase = true)
        val summaryAt = trimmed.indexOf(SUMMARY_HEADING, ignoreCase = true)
        if (answerAt >= 0 && summaryAt > answerAt) {
            val answer = trimmed.substring(answerAt + ANSWER_HEADING.length, summaryAt).trim()
            val summary = trimmed.substring(summaryAt + SUMMARY_HEADING.length).trim()
            if (answer.isNotEmpty()) return Sections(answer, summary.ifBlank { fallbackSummary(answer) })
        }
        if (answerAt >= 0) {
            val answer = trimmed.substring(answerAt + ANSWER_HEADING.length).trim()
            if (answer.isNotEmpty()) return Sections(answer, fallbackSummary(answer))
        }
        return Sections(trimmed, fallbackSummary(trimmed))
    }

    fun fallbackSummary(answer: String): String {
        val clean = answer
            .replace(Regex("[`*#>_~]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (clean.isEmpty()) return ""
        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
        return sentences.take(2).joinToString(" ").take(MAX_SUMMARY_CHARS).trim()
    }

    /**
     * Typed turns are pre-reasoned by the app, so the reference answer rides in
     * the same user item: one user item keeps the wire shape identical to a
     * normal text turn.
     */
    fun questionWithReference(question: String, answer: String): String = buildString {
        append(question.trim())
        append("\n\n[Deep-reasoning reference — the user does not see this block. ")
        append("Base your reply on it, lead with the conclusion, keep it short for ")
        append("speech, and do not read it verbatim or mention that another model ")
        append("was consulted.]\n")
        append(answer.trim().take(MAX_REFERENCE_CHARS))
    }

    /** Compact markdown stored as the `Deep reasoning` chat message. */
    fun cardText(trace: HandoffTrace): String {
        val header = buildString {
            append("Deep reasoning · ")
            append(trace.model)
            append(" · ")
            append(formatSeconds(trace.elapsedMs))
            if (trace.reasoningTokens > 0) {
                append(" · ")
                append(trace.reasoningTokens)
                append(" reasoning tokens")
            }
        }
        if (trace.failed) {
            return "$header\n\n${trace.error ?: "Deep reasoning failed"}"
        }
        return buildString {
            append(header)
            append("\n\n")
            append(trace.answer.ifBlank { trace.summary })
            if (trace.reasoning.isNotBlank()) {
                append("\n\n---\nReasoning trace\n\n")
                append(trace.reasoning.trim())
            }
        }
    }

    fun formatSeconds(ms: Long): String =
        if (ms < 1_000) "${ms}ms" else String.format(Locale.US, "%.1fs", ms / 1000.0)
}
