package com.voicechat.assistant.realtime

/**
 * Recognises a turn that announced an action and then ended without doing it.
 *
 * `qwen3.8-omni-flash-realtime` sometimes answers a request with one sentence
 * such as "I'll pull the latest info on that" — or a finished thought followed
 * by "but I need to check the actual reviews" — and completes the response
 * without a `function_call` item, so the user waits for an answer that never
 * comes. The protocol has no error for this: `response.done` reports
 * `completed`. [RealtimeSession] uses this check to retry the turn once.
 *
 * Deliberately narrow — a false positive costs one extra response and a
 * repeated answer, so it only fires when the *last* sentence is a first-person
 * promise of a lookup, not a question and not advice to the user.
 */
object TurnProgress {

    private const val MAX_PROMISE_CHARS = 400

    /** First-person intent to act: "I'll …", "I need to …", "let me …". */
    private val PROMISE = Regex(
        "\\b(i'll|i will|i need to|i'd need to|i'd have to|i have to|i should|" +
            "i'm going to|i am going to|i may need to|let me|i can|i'm on it|" +
            "one moment|give me a moment|let's|let us)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The action the promise is about; without it the sentence is not a lookup. */
    private val ACTION_VERB = Regex(
        "\\b(look|check|search|find|pull|fetch|get|gather|dig|verify|research|" +
            "summarize|summarise)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** True when [text] ends on "I'll do X" rather than on an answer. */
    fun promisesAction(text: String): Boolean {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > MAX_PROMISE_CHARS) return false
        // A question is a clarification request, not an unfulfilled promise.
        if (clean.endsWith("?")) return false
        val last = clean.split(Regex("(?<=[.!?])\\s+"))
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            ?: return false
        return PROMISE.containsMatchIn(last) && ACTION_VERB.containsMatchIn(last)
    }
}
