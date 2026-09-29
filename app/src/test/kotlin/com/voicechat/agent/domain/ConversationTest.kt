package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTest {
    @Test
    fun duplicateTurnIdsAreRejected() {
        val turn = UserTurn(TurnId("turn-1"), Transcript.final("hello"), UserTurnSource.VOICE)

        assertThrows(IllegalArgumentException::class.java) {
            Conversation(
                id = ConversationId("conversation-1"),
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 2L,
                turns = listOf(turn, turn.copy(transcript = Transcript.final("again"))),
            )
        }
    }

    @Test
    fun blankTitleIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Conversation(
                id = ConversationId("conversation-1"),
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 2L,
                title = "  ",
            )
        }
    }

    @Test
    fun emptyConversationReportsItselfEmpty() {
        val conversation =
            Conversation(
                id = ConversationId("conversation-1"),
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 2L,
            )

        assertTrue(conversation.isEmpty)
    }

    @Test
    fun conversationKeepsTurnsInOrder() {
        val first = UserTurn(TurnId("turn-1"), Transcript.final("hello"), UserTurnSource.VOICE)
        val second = AssistantTurn.pending(TurnId("turn-2"))
        val conversation =
            Conversation(
                id = ConversationId("conversation-1"),
                createdAtEpochMillis = 1L,
                updatedAtEpochMillis = 2L,
                turns = listOf(first, second),
            )

        assertEquals(listOf(TurnId("turn-1"), TurnId("turn-2")), conversation.turns.map { it.id })
        assertFalse(conversation.isEmpty)
    }

    @Test
    fun summaryTurnCountMustNotBeNegative() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationSummary(id = ConversationId("conversation-1"), updatedAtEpochMillis = 1L, turnCount = -1)
        }
    }
}
