package com.voicechat.agent.domain.context

import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.domain.UserTurn

/** Who produced one message in the bounded model context. */
enum class ContextRole {
    USER,
    ASSISTANT,
}

/** One message selected for the next model request. */
data class ContextMessage(
    val role: ContextRole,
    val text: String,
)

/**
 * The bounded set of messages sent to a model for one request.
 *
 * The full conversation remains in the repository; this window is a deliberate
 * subset. It carries the [conversationId] it was built from so orchestration can
 * assert the request never silently mixes conversations.
 */
data class ModelContextWindow(
    val conversationId: ConversationId,
    val messages: List<ContextMessage>,
) {
    val characterCount: Int get() = messages.sumOf { it.text.length }
}

/**
 * Selects the messages sent to the model from **one** conversation.
 *
 * **Independence.** The builder only reads the [Conversation] it is given. It has
 * no repository and no storage types, so it cannot append another conversation's
 * turns; full local history is preserved elsewhere and is not automatically sent
 * (`docs/product-requirements.md`, "Dialog history").
 *
 * **Bounds.** At most [maxMessages] messages and [maxCharacters] characters are
 * selected, newest first, walking backwards through the conversation. The newest
 * message is always kept even if it alone exceeds [maxCharacters], so a user's
 * current request is never dropped; every older message must fit the remaining
 * budget. Constants record the concrete default bound used by the app.
 *
 * **Truthfulness.** Only final user transcripts and actually
 * [delivered][AssistantTurn.delivery] assistant text are eligible. Interim
 * recognition and generated-but-unheard text are never sent.
 */
class ModelContextBuilder(
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    private val maxCharacters: Int = DEFAULT_MAX_CHARACTERS,
) {
    init {
        require(maxMessages >= 1) { "maxMessages must be at least 1" }
        require(maxCharacters >= 1) { "maxCharacters must be at least 1" }
    }

    /** Builds the bounded window for [conversation]. */
    fun build(conversation: Conversation): ModelContextWindow {
        val eligible = conversation.turns.mapNotNull { it.toContextMessage() }

        val selectedNewestFirst = mutableListOf<ContextMessage>()
        var characters = 0
        for (message in eligible.asReversed()) {
            val exceedsBound =
                selectedNewestFirst.size >= maxMessages ||
                    characters + message.text.length > maxCharacters
            // The newest message is always kept; only later (older) messages are
            // bounded.
            if (selectedNewestFirst.isNotEmpty() && exceedsBound) {
                break
            }
            selectedNewestFirst += message
            characters += message.text.length
        }

        return ModelContextWindow(
            conversationId = conversation.id,
            messages = selectedNewestFirst.asReversed(),
        )
    }

    private fun Turn.toContextMessage(): ContextMessage? =
        when (this) {
            is UserTurn -> {
                takeIf { transcript.isFinal && transcript.text.isNotBlank() }
                    ?.let { ContextMessage(ContextRole.USER, transcript.text) }
            }

            is AssistantTurn -> {
                delivery.deliveredText
                    .takeIf { it.isNotBlank() }
                    ?.let { ContextMessage(ContextRole.ASSISTANT, it) }
            }
        }

    companion object {
        /** Default message-count bound for one request. */
        const val DEFAULT_MAX_MESSAGES: Int = 20

        /** Default character bound for one request. */
        const val DEFAULT_MAX_CHARACTERS: Int = 4_000
    }
}
