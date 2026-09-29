package com.voicechat.agent.domain

/**
 * One turn in a conversation: either the user's input or the assistant's reply.
 */
sealed interface Turn {
    val id: TurnId
}

/** How a user turn's text was produced. Voice and text turns share one path. */
enum class UserTurnSource {
    VOICE,
    TEXT,
}

/**
 * The user's committed turn.
 *
 * [transcript] is expected to be finalized before a turn is committed to a
 * conversation; interim revisions stay in the live dialog, not the persisted
 * turn.
 */
data class UserTurn(
    override val id: TurnId,
    val transcript: Transcript,
    val source: UserTurnSource,
) : Turn

/** Whether the assistant's generated text is still streaming or has ended. */
enum class GenerationState {
    IN_PROGRESS,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/** Assistant text produced by the LLM, independent of what was spoken. */
data class GeneratedText(
    val text: String,
    val state: GenerationState,
)

/** How far playback of an assistant turn progressed. */
enum class DeliveryState {
    NOT_STARTED,
    SPEAKING,
    COMPLETED,
    INTERRUPTED,
    FAILED,
}

/**
 * What was actually delivered to playback.
 *
 * This is intentionally separate from [GeneratedText]: if the user interrupts,
 * the assistant may have generated more text than was ever spoken. Persisting
 * only [deliveredText] keeps the conversation truthful about what the user
 * heard (see `docs/architecture.md`, "Streaming and lifecycle").
 */
data class AssistantDelivery(
    val deliveredText: String,
    val state: DeliveryState,
)

/**
 * The assistant's turn.
 *
 * [deliveredText][AssistantDelivery.deliveredText] must be a prefix of the
 * generated text: the app never reports speech that was not generated.
 */
data class AssistantTurn(
    override val id: TurnId,
    val generated: GeneratedText,
    val delivery: AssistantDelivery,
) : Turn {
    init {
        require(generated.text.startsWith(delivery.deliveredText)) {
            "delivered text must be a prefix of the generated text"
        }
    }

    /** True when generation produced text that playback has not yet delivered. */
    val hasUndeliveredText: Boolean
        get() = delivery.deliveredText != generated.text

    companion object {
        /** An empty assistant turn that is still generating. */
        fun pending(id: TurnId): AssistantTurn =
            AssistantTurn(
                id = id,
                generated = GeneratedText(text = "", state = GenerationState.IN_PROGRESS),
                delivery = AssistantDelivery(deliveredText = "", state = DeliveryState.NOT_STARTED),
            )
    }
}
