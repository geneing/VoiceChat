package com.voicechat.agent.domain

/**
 * A persisted conversation and its turns in order.
 *
 * [updatedAtEpochMillis] orders the conversation list; timestamps are wall time
 * for display only and are never used for latency measurement.
 */
data class Conversation(
    val id: ConversationId,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val title: String? = null,
    val turns: List<Turn> = emptyList(),
) {
    init {
        require(title == null || title.isNotBlank()) { "title must not be blank when present" }
        require(turns.map { it.id }.distinct().size == turns.size) {
            "Turn IDs must be unique within a conversation"
        }
    }

    val isEmpty: Boolean get() = turns.isEmpty()
}

/**
 * Lightweight conversation row for the history list, so the list does not load
 * every turn of every conversation.
 */
data class ConversationSummary(
    val id: ConversationId,
    val updatedAtEpochMillis: Long,
    val title: String? = null,
    val turnCount: Int = 0,
) {
    init {
        require(turnCount >= 0) { "turnCount must not be negative" }
    }
}
