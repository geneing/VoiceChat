package com.voicechat.assistant.realtime.handoff

/** How a text model exposes its reasoning trace. */
enum class ThinkingMode {
    /** Accepts `enable_thinking` + `thinking_budget`; a budget of 0 turns it off. */
    BUDGET,

    /** Always reasons and rejects `thinking_budget`, so the budget must be omitted. */
    ALWAYS,

    /** No reasoning trace at all; `enable_thinking` is ignored or rejected. */
    NONE,
}

/**
 * Text models the realtime session can hand a hard turn to. Verified against
 * this workspace's OpenAI-compatible endpoint: [ThinkingMode.BUDGET] models
 * stream `reasoning_content` deltas and honour `thinking_budget`, and a body
 * that sends a budget to a model that rejects it fails the whole request.
 */
data class ReasoningModelInfo(
    val id: String,
    val label: String,
    val description: String,
    val thinkingMode: ThinkingMode = ThinkingMode.NONE,
) {
    val supportsThinking: Boolean get() = thinkingMode != ThinkingMode.NONE
}

object ReasoningModels {

    const val DEFAULT_ID = "qwen3.8-flash"

    val models: List<ReasoningModelInfo> = listOf(
        ReasoningModelInfo(
            id = "qwen3.8-flash",
            label = "Qwen 3.8 Flash",
            description = "Newest thinking model. Shortest wait before the answer.",
            thinkingMode = ThinkingMode.BUDGET,
        ),
        ReasoningModelInfo(
            id = "qwen3.8-max",
            label = "Qwen 3.8 Max",
            description = "Strongest reasoning model. Best for hard code and maths.",
            thinkingMode = ThinkingMode.BUDGET,
        ),
        ReasoningModelInfo(
            id = "qwen3.7-max",
            label = "Qwen 3.7 Max",
            description = "Strong reasoning model, slightly older generation.",
            thinkingMode = ThinkingMode.BUDGET,
        ),
        ReasoningModelInfo(
            id = "qwen-plus",
            label = "Qwen Plus",
            description = "Balanced thinking model. Cheaper than Max.",
            thinkingMode = ThinkingMode.BUDGET,
        ),
        ReasoningModelInfo(
            id = "qwen-flash",
            label = "Qwen Flash",
            description = "Cheapest thinking model. Good enough for most work.",
            thinkingMode = ThinkingMode.BUDGET,
        ),
        ReasoningModelInfo(
            id = "qwq-plus-2025-03-05",
            label = "QwQ Plus",
            description = "Always reasons and ignores the budget. Slowest, most deliberate.",
            thinkingMode = ThinkingMode.ALWAYS,
        ),
        ReasoningModelInfo(
            id = "qwen-max",
            label = "Qwen Max",
            description = "Strongest model without a reasoning trace. Fastest to spend nothing on thinking.",
            thinkingMode = ThinkingMode.NONE,
        ),
    )

    /** Thinking budget bounds. 0 disables thinking for [ThinkingMode.BUDGET] models. */
    const val MIN_THINKING_BUDGET = 0
    const val MAX_THINKING_BUDGET = 8_192
    const val DEFAULT_THINKING_BUDGET = 2_048

    const val MIN_TIMEOUT_MS = 15_000
    const val MAX_TIMEOUT_MS = 120_000

    fun info(id: String): ReasoningModelInfo =
        models.firstOrNull { it.id == id } ?: models.first()

    /** Guards against a stored id that this workspace no longer serves. */
    fun resolve(id: String): String = info(id).id
}
