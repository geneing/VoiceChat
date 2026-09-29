package com.voicechat.agent.domain.context

import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.GeneratedText
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelContextBuilderTest {
    private fun user(
        id: String,
        text: String,
        isFinal: Boolean = true,
    ): UserTurn =
        UserTurn(
            id = TurnId(id),
            transcript = if (isFinal) Transcript.final(text) else Transcript.interim(text),
            source = UserTurnSource.TEXT,
        )

    private fun assistant(
        id: String,
        generated: String,
        delivered: String,
        deliveryState: DeliveryState = DeliveryState.COMPLETED,
    ): AssistantTurn =
        AssistantTurn(
            id = TurnId(id),
            generated = GeneratedText(generated, GenerationState.COMPLETED),
            delivery = AssistantDelivery(delivered, deliveryState),
        )

    private fun conversation(
        id: String,
        vararg turns: Turn,
    ): Conversation =
        Conversation(
            id = ConversationId(id),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 1L,
            turns = turns.toList(),
        )

    @Test
    fun windowKeepsChronologicalOrderAndCarriesConversationIdentity() {
        val window =
            ModelContextBuilder().build(
                conversation(
                    "conversation-1",
                    user("t1", "hello"),
                    assistant("t2", "hi there", "hi there"),
                    user("t3", "how are you"),
                ),
            )

        assertEquals(ConversationId("conversation-1"), window.conversationId)
        assertEquals(
            listOf(
                ContextMessage(ContextRole.USER, "hello"),
                ContextMessage(ContextRole.ASSISTANT, "hi there"),
                ContextMessage(ContextRole.USER, "how are you"),
            ),
            window.messages,
        )
    }

    @Test
    fun messageBoundKeepsOnlyTheMostRecentMessages() {
        val turns =
            (0 until 6).map { index ->
                if (index % 2 == 0) user("u$index", "user-$index") else assistant("a$index", "assistant-$index", "assistant-$index")
            }

        val window = ModelContextBuilder(maxMessages = 3).build(conversation("conversation-1", *turns.toTypedArray()))

        assertEquals(listOf("assistant-3", "user-4", "assistant-5"), window.messages.map { it.text })
    }

    @Test
    fun characterBoundDropsOlderMessagesThatDoNotFit() {
        val window =
            ModelContextBuilder(maxCharacters = 12).build(
                conversation(
                    "conversation-1",
                    user("u1", "old message"),
                    user("u2", "next"),
                    user("u3", "latest"),
                ),
            )

        // "latest" (6) + "next" (4) = 10 fits; "old message" (11) would exceed 12.
        assertEquals(listOf("next", "latest"), window.messages.map { it.text })
        assertTrue(window.characterCount <= 12)
    }

    @Test
    fun newestMessageIsAlwaysKeptEvenWhenItAloneExceedsTheCharacterBound() {
        val window =
            ModelContextBuilder(maxCharacters = 4).build(
                conversation(
                    "conversation-1",
                    user("u1", "short"),
                    user("u2", "a much longer current request"),
                ),
            )

        assertEquals(listOf("a much longer current request"), window.messages.map { it.text })
    }

    @Test
    fun interimUserTranscriptIsNotEligibleForTheModel() {
        val window =
            ModelContextBuilder().build(
                conversation(
                    "conversation-1",
                    user("u1", "hello there"),
                    user("u2", "helo ther", isFinal = false),
                ),
            )

        assertEquals(listOf("hello there"), window.messages.map { it.text })
    }

    @Test
    fun onlyDeliveredAssistantTextIsEligible() {
        val window =
            ModelContextBuilder().build(
                conversation(
                    "conversation-1",
                    user("u1", "tell me a story"),
                    assistant(
                        id = "a1",
                        generated = "Once upon a time there was a long tale",
                        delivered = "Once upon a",
                        deliveryState = DeliveryState.INTERRUPTED,
                    ),
                ),
            )

        assertEquals(listOf("tell me a story", "Once upon a"), window.messages.map { it.text })
    }

    @Test
    fun assistantTurnWithNothingDeliveredIsSkipped() {
        val window =
            ModelContextBuilder().build(
                conversation(
                    "conversation-1",
                    user("u1", "hello"),
                    assistant(id = "a1", generated = "not spoken", delivered = "", deliveryState = DeliveryState.INTERRUPTED),
                ),
            )

        assertEquals(listOf("hello"), window.messages.map { it.text })
    }

    @Test
    fun defaultBuilderBoundsALongConversationToItsDocumentedMessageBudget() {
        val turns =
            (0 until (ModelContextBuilder.DEFAULT_MAX_MESSAGES * 3)).map { index ->
                if (index % 2 == 0) user("u$index", "user-$index") else assistant("a$index", "assistant-$index", "assistant-$index")
            }

        val window = ModelContextBuilder().build(conversation("conversation-1", *turns.toTypedArray()))

        assertEquals(ModelContextBuilder.DEFAULT_MAX_MESSAGES, window.messages.size)
        assertTrue(window.characterCount <= ModelContextBuilder.DEFAULT_MAX_CHARACTERS)
    }
}
