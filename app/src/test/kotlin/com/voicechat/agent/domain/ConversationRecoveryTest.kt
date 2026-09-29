package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationRecoveryTest {
    private fun conversation(vararg turns: Turn): Conversation =
        Conversation(
            id = ConversationId("conversation-1"),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = 2L,
            turns = turns.toList(),
        )

    private fun user(
        id: String,
        text: String,
        isFinal: Boolean = true,
    ): UserTurn =
        UserTurn(
            id = TurnId(id),
            transcript = if (isFinal) Transcript.final(text) else Transcript.interim(text),
            source = UserTurnSource.VOICE,
        )

    @Test
    fun reconcileDropsAUserTurnThatWasNeverCommitted() {
        val recovered =
            conversation(
                user("u1", "committed turn"),
                user("u2", "interim guess", isFinal = false),
            ).reconcileAfterProcessDeath()

        assertEquals(listOf(TurnId("u1")), recovered.turns.map { it.id })
    }

    @Test
    fun reconcileCancelsInProgressGenerationAndInterruptsUnfinishedDelivery() {
        val recovered = conversation(AssistantTurn.pending(TurnId("a1"))).reconcileAfterProcessDeath()
        val turn = recovered.turns.single() as AssistantTurn

        assertEquals(GenerationState.CANCELLED, turn.generated.state)
        assertEquals(DeliveryState.INTERRUPTED, turn.delivery.state)
    }

    @Test
    fun reconcileMarksSpeakingDeliveryInterruptedButKeepsTheDeliveredPrefix() {
        val interruptedByProcessDeath =
            AssistantTurn(
                id = TurnId("a1"),
                generated = GeneratedText("the complete answer", GenerationState.IN_PROGRESS),
                delivery = AssistantDelivery("the complete", DeliveryState.SPEAKING),
            )

        val turn = conversation(interruptedByProcessDeath).reconcileAfterProcessDeath().turns.single() as AssistantTurn

        assertEquals(GenerationState.CANCELLED, turn.generated.state)
        assertEquals(DeliveryState.INTERRUPTED, turn.delivery.state)
        assertEquals("the complete", turn.delivery.deliveredText)
        assertEquals("the complete answer", turn.generated.text)
    }

    @Test
    fun reconcileLeavesTerminalTurnsUntouched() {
        val completed =
            AssistantTurn(
                id = TurnId("a1"),
                generated = GeneratedText("all spoken", GenerationState.COMPLETED),
                delivery = AssistantDelivery("all spoken", DeliveryState.COMPLETED),
            )
        val original = conversation(user("u1", "hello"), completed)

        assertTrue(original.reconcileAfterProcessDeath() === original)
    }

    @Test
    fun renameUpdatesTitleAndTimestampWithoutChangingTurns() {
        val original = conversation(user("u1", "hello"))

        val renamed = original.renamed(title = "Trip planning", updatedAtEpochMillis = 99L)

        assertEquals("Trip planning", renamed.title)
        assertEquals(99L, renamed.updatedAtEpochMillis)
        assertEquals(original.turns, renamed.turns)
    }

    @Test
    fun withoutTurnRemovesOnlyTheNamedTurn() {
        val original = conversation(user("u1", "first"), user("u2", "second"))

        val trimmed = original.withoutTurn(TurnId("u1"))

        assertEquals(listOf(TurnId("u2")), trimmed.turns.map { it.id })
    }
}
