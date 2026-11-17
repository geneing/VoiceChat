package com.voicechat.assistant.memory

import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns remembered facts into prompt text and tool results. Pure, so the wording
 * and the size caps are asserted in unit tests.
 */
object MemoryPrompt {

    /** The standing block injected into the session instructions. */
    const val SESSION_MAX_CHARS = 700

    /** The block attached to one typed turn. */
    const val TURN_MAX_CHARS = 340

    fun sessionBlock(facts: List<MemoryFact>): String {
        if (facts.isEmpty()) return ""
        val lines = mutableListOf<String>()
        var used = 0
        var dropped = 0
        facts.forEach { fact ->
            val line = "- " + oneLine(fact.text)
            if (used + line.length + 1 > SESSION_MAX_CHARS) {
                dropped++
                return@forEach
            }
            lines += line
            used += line.length + 1
        }
        if (lines.isEmpty()) return ""
        return buildString {
            append(
                "What you remember about the user from earlier conversations (stored on " +
                    "this device). Use it naturally when it is relevant: never read it out " +
                    "as a list, and never say you looked it up or that you have a memory " +
                    "file.",
            )
            append("\n")
            append(lines.joinToString("\n"))
            if (dropped > 0) {
                append("\n($dropped more facts are stored and can be recalled when needed.)")
            }
        }
    }

    fun turnBlock(facts: List<MemoryFact>): String {
        if (facts.isEmpty()) return ""
        val lines = mutableListOf<String>()
        var used = 0
        facts.forEach { fact ->
            val line = "- " + oneLine(fact.text)
            if (used + line.length + 1 > TURN_MAX_CHARS) return@forEach
            lines += line
            used += line.length + 1
        }
        return lines.joinToString("\n")
    }

    /**
     * A typed turn carries its relevant facts in the same user item, the way the
     * handoff reference does: one item keeps the wire shape of a normal text turn.
     */
    fun withTurnContext(text: String, block: String): String {
        if (block.isBlank()) return text
        return buildString {
            append(text.trim())
            append(
                "\n\n[Device memory — background facts about the user from earlier " +
                    "conversations. The user does not see this block. Use only what is " +
                    "relevant to the request; do not read it out or mention that you " +
                    "have it.]\n",
            )
            append(block.trim())
        }
    }

    fun recallResult(facts: List<MemoryFact>, query: String): String = buildJsonObject {
        put("ok", true)
        put("query", query)
        put("count", facts.size)
        put(
            "facts",
            buildJsonArray {
                facts.forEach { fact ->
                    add(
                        buildJsonObject {
                            put("kind", fact.kind.name.lowercase())
                            put("fact", oneLine(fact.text))
                        },
                    )
                }
            },
        )
        if (facts.isEmpty()) {
            put(
                "note",
                "No stored fact matches that. Say you do not have it rather than " +
                    "inventing one.",
            )
        }
    }.toString()

    fun rememberResult(candidate: MemoryCandidate): String = buildJsonObject {
        put("ok", true)
        put("stored", candidate.text)
        put("kind", candidate.kind.name.lowercase())
        if (candidate.pinned) put("pinned", true)
    }.toString()

    fun forgetResult(fact: MemoryFact): String = buildJsonObject {
        put("ok", true)
        put("forgotten", fact.text)
    }.toString()

    fun error(message: String): String = buildJsonObject {
        put("ok", false)
        put("error", message)
    }.toString()

    private fun oneLine(text: String): String =
        MemoryGuard.redact(text).replace(Regex("\\s+"), " ").trim()
}
