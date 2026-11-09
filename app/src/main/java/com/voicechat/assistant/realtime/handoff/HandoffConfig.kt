package com.voicechat.assistant.realtime.handoff

/**
 * Snapshot of the deep-reasoning handoff for one session. A `null` value on
 * [com.voicechat.assistant.realtime.SessionConfig] means the session runs
 * without the `deep_reasoning` tool at all.
 */
data class HandoffConfig(
    val model: String = ReasoningModels.DEFAULT_ID,
    /** Caps reasoning tokens. 0 turns thinking off for models that support it. */
    val thinkingBudget: Int = ReasoningModels.DEFAULT_THINKING_BUDGET,
    val timeoutMs: Int = DEFAULT_TIMEOUT_MS,
    /** Conversation turns handed to the reasoning model as context. */
    val contextTurns: Int = DEFAULT_CONTEXT_TURNS,
    /** Escalate hard typed turns before they reach the realtime model. */
    val forceForTypedTurns: Boolean = false,
    /** Warm a reasoning call from a hard voice transcript while the model speaks. */
    val speculativeVoice: Boolean = true,
    val priceInputPerMillion: Double = 0.0,
    val priceOutputPerMillion: Double = 0.0,
) {
    val modelInfo: ReasoningModelInfo get() = ReasoningModels.info(model)

    /** The id actually sent on the wire; a stored model may have been retired. */
    val resolvedModel: String get() = modelInfo.id

    val thinking: Boolean
        get() = when (modelInfo.thinkingMode) {
            ThinkingMode.BUDGET -> thinkingBudget > 0
            ThinkingMode.ALWAYS -> true
            ThinkingMode.NONE -> false
        }

    /** Answer budget; the reasoning trace is billed separately by `thinkingBudget`. */
    val maxAnswerTokens: Int
        get() = if (thinking) 1_600 else 2_400

    companion object {
        const val DEFAULT_TIMEOUT_MS = 45_000
        const val DEFAULT_CONTEXT_TURNS = 8
    }
}
