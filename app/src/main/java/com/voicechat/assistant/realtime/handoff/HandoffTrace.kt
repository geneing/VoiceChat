package com.voicechat.assistant.realtime.handoff

import com.voicechat.assistant.data.model.TokenUsage

/** Progress of a handoff, surfaced in the live strip and the chat card. */
enum class HandoffPhase { RUNNING, FAILED }

/**
 * One completed handoff. The session turns it into a `function_call_output`,
 * a `Deep reasoning` chat card and a usage entry.
 */
data class HandoffTrace(
    val model: String,
    val task: String,
    val answer: String,
    val summary: String,
    val reasoning: String,
    val usage: TokenUsage,
    val reasoningTokens: Int,
    val elapsedMs: Long,
    val costUsd: Double,
    val error: String? = null,
) {
    val failed: Boolean get() = error != null
}

data class HandoffUiState(
    val model: String,
    val phase: HandoffPhase = HandoffPhase.RUNNING,
    /** Reasoning characters streamed so far, so the UI can show progress. */
    val reasoningChars: Int = 0,
    val elapsedMs: Long = 0,
    val error: String? = null,
)
