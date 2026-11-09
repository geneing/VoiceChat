package com.voicechat.assistant.realtime.handoff

/**
 * Decides whether a typed turn is worth pre-reasoning. Deliberately simple and
 * deterministic: the realtime model can always escalate by itself through the
 * `deep_reasoning` tool, this only guarantees an escalation for prompts that
 * clearly need one.
 */
object HandoffRouter {

    const val THRESHOLD = 2

    private val CODE = Regex(
        "```|\\bdef \\w|\\bclass \\w|\\bfunction \\w|\\bimport \\w|stack trace|" +
            "exception|compile error|\\bsql\\b|regex|\\bapi\\b.*\\bendpoint",
    )

    private val MATH = Regex(
        "\\b(prove|derive|derivation|calculate|compute|solve|step by step|" +
            "algorithm|complexity|probability|integral|derivative|equation|" +
            "matrix|simulate|simulation|estimate the|closed form|optimi[sz]e)\\b",
    )

    private val ANALYSIS = Regex(
        "\\b(design|architect\\w*|trade-?offs?|compare|comparing|evaluate|strategy|" +
            "plan|refactor|debug|root cause|analy[sz]e|analysis|explain why|" +
            "why does|why is|should i|pros and cons|critique|review my|migrate)\\b",
    )

    /** Short small-talk turns that must never escalate, however they are phrased. */
    private val TRIVIAL = Regex(
        "\\b(weather|temperature|forecast|what time|current date|what day|" +
            "hello|hi|hey|thanks|thank you|who are you|how are you|good morning)\\b",
    )

    fun shouldEscalate(
        text: String,
        config: HandoffConfig?,
        documentAttached: Boolean = false,
    ): Boolean {
        if (config == null || !config.forceForTypedTurns) return false
        return score(text, documentAttached) >= THRESHOLD
    }

    /**
     * Voice speculation uses the same bar as escalation: a warm call the model
     * never uses is cancelled as soon as the turn ends, so the only cost is the
     * tokens it streamed before then. Warming at a higher bar made the lane
     * almost never fire for natural speech.
     */
    fun shouldSpeculate(text: String, config: HandoffConfig?): Boolean {
        if (config == null || !config.speculativeVoice) return false
        return score(text) >= THRESHOLD
    }

    fun score(text: String, documentAttached: Boolean = false): Int {
        val prompt = text.trim()
        if (prompt.length < 40) return 0
        val lower = prompt.lowercase()
        if (prompt.length < 160 && TRIVIAL.containsMatchIn(lower)) return 0

        var score = when {
            prompt.length >= 400 -> 2
            prompt.length >= 220 -> 1
            else -> 0
        }
        if (CODE.containsMatchIn(lower)) score += 2
        if (MATH.containsMatchIn(lower)) score += 2
        score += ANALYSIS.findAll(lower).count().coerceAtMost(2)
        if (prompt.count { it == '?' } >= 2) score += 1
        if (prompt.count { it == '.' || it == '?' || it == '!' } >= 3) score += 1
        if (documentAttached) score += 2
        return score
    }
}
