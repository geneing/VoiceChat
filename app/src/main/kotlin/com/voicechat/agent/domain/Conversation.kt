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

    /**
     * Returns a copy with a new [title] and [updatedAtEpochMillis].
     *
     * Rename is a domain operation persisted through the repository's existing
     * `save`, so the M02 [com.voicechat.agent.contracts.ConversationRepository]
     * contract needs no storage-specific method.
     */
    fun renamed(
        title: String?,
        updatedAtEpochMillis: Long,
    ): Conversation = copy(title = title, updatedAtEpochMillis = updatedAtEpochMillis)

    /** Returns a copy without the turn identified by [turnId]; unknown IDs are ignored. */
    fun withoutTurn(turnId: TurnId): Conversation = copy(turns = turns.filterNot { it.id == turnId })

    /**
     * Reconciles state after the process died mid-turn.
     *
     * A restart cannot resume generation or playback, so recovery keeps the
     * stored state truthful instead of reporting false success (see
     * `docs/architecture.md`, "Streaming and lifecycle"):
     *
     * - A user turn whose transcript is not [final][Transcript.isFinal] was never
     *   committed; it is dropped so provisional recognition is not mistaken for a
     *   sent turn.
     * - Assistant generation still [IN_PROGRESS][GenerationState.IN_PROGRESS]
     *   becomes [CANCELLED][GenerationState.CANCELLED]: the reply can never
     *   finish.
     * - Delivery that had not finished ([NOT_STARTED][DeliveryState.NOT_STARTED]
     *   or [SPEAKING][DeliveryState.SPEAKING]) becomes
     *   [INTERRUPTED][DeliveryState.INTERRUPTED], preserving the delivered prefix
     *   as what the user actually heard.
     *
     * Turns already in a terminal state are returned unchanged, so recovery is
     * safe to run on every reopen.
     */
    fun reconcileAfterProcessDeath(): Conversation {
        val reconciledTurns = turns.mapNotNull { it.reconciledAfterProcessDeath() }
        return if (reconciledTurns == turns) this else copy(turns = reconciledTurns)
    }
}

private fun Turn.reconciledAfterProcessDeath(): Turn? =
    when (this) {
        is UserTurn -> {
            takeIf { transcript.isFinal }
        }

        is AssistantTurn -> {
            val reconciledGenerationState =
                if (generated.state == GenerationState.IN_PROGRESS) {
                    GenerationState.CANCELLED
                } else {
                    generated.state
                }
            val reconciledDeliveryState =
                when (delivery.state) {
                    DeliveryState.NOT_STARTED, DeliveryState.SPEAKING -> DeliveryState.INTERRUPTED
                    else -> delivery.state
                }
            if (reconciledGenerationState == generated.state && reconciledDeliveryState == delivery.state) {
                this
            } else {
                copy(
                    generated = generated.copy(state = reconciledGenerationState),
                    delivery = delivery.copy(state = reconciledDeliveryState),
                )
            }
        }
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
