package com.voicechat.assistant.memory

/**
 * Keeps secrets and identifiers out of device memory.
 *
 * Facts are injected into every later session's instructions, so anything that
 * looks like a credential must never become one. This is a cheap last line of
 * defence: the extractor already only stores templated sentences, and the model
 * can only store what it passes to `remember_fact`.
 */
object MemoryGuard {

    /** A single injected fact stays short; anything longer is a paragraph. */
    const val MAX_FACT_CHARS = 180

    private val SECRETS = listOf(
        Regex("sk-[A-Za-z0-9_-]{8,}"),
        Regex("(?i)\\bbearer\\s+[A-Za-z0-9._-]{12,}"),
        Regex(
            "(?i)\\b(?:password|passcode|passphrase|pin code|pin|api[ _-]?key|secret|" +
                "access token|token)\\b\\s*(?:is|was|=|:)?\\s*\\S{3,}",
        ),
        Regex("\\b\\d{13,19}\\b"),
        Regex("[A-Za-z0-9+/]{40,}={0,2}"),
    )

    private val WHITESPACE = Regex("\\s+")
    private val TRIM_CHARS = charArrayOf(' ', ',', ';', ':', '-', '"', '\'')

    /**
     * Collapses whitespace and strips the trailing punctuation a transcript
     * carries, so a stored fact reads like a sentence and dedupes reliably.
     */
    fun sanitize(raw: String): String {
        var value = raw.trim().replace(WHITESPACE, " ").trim(*TRIM_CHARS)
        if (value.length > MAX_FACT_CHARS) {
            value = value.take(MAX_FACT_CHARS).trimEnd(*TRIM_CHARS)
        }
        return value
    }

    /** False for secrets, identifiers and text that states nothing durable. */
    fun isStorable(text: String): Boolean {
        if (text.length < 3 || text.length > MAX_FACT_CHARS) return false
        if (text.none { it.isLetter() }) return false
        return SECRETS.none { it.containsMatchIn(text) }
    }

    /** Defensive pass before a fact is written into a prompt. */
    fun redact(text: String): String {
        var value = text
        SECRETS.forEach { pattern -> value = pattern.replace(value, "[redacted]") }
        return value
    }
}
