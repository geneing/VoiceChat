package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantTurnTest {
    @Test
    fun pendingTurnHasNoGeneratedOrDeliveredText() {
        val turn = AssistantTurn.pending(TurnId("turn-1"))

        assertEquals("", turn.generated.text)
        assertEquals("", turn.delivery.deliveredText)
        assertEquals(GenerationState.IN_PROGRESS, turn.generated.state)
        assertEquals(DeliveryState.NOT_STARTED, turn.delivery.state)
        assertFalse(turn.hasUndeliveredText)
    }

    @Test
    fun deliveredTextMustBeAPrefixOfGeneratedText() {
        assertThrows(IllegalArgumentException::class.java) {
            AssistantTurn(
                id = TurnId("turn-1"),
                generated = GeneratedText("hello world", GenerationState.COMPLETED),
                delivery = AssistantDelivery("goodbye", DeliveryState.SPEAKING),
            )
        }
    }

    @Test
    fun interruptionKeepsGeneratedTextLongerThanDeliveredText() {
        val turn =
            AssistantTurn(
                id = TurnId("turn-1"),
                generated = GeneratedText("the complete answer", GenerationState.CANCELLED),
                delivery = AssistantDelivery("the complete", DeliveryState.INTERRUPTED),
            )

        assertTrue(turn.hasUndeliveredText)
        assertTrue(turn.generated.text.startsWith(turn.delivery.deliveredText))
        assertEquals(GenerationState.CANCELLED, turn.generated.state)
        assertEquals(DeliveryState.INTERRUPTED, turn.delivery.state)
    }

    @Test
    fun fullyDeliveredTurnHasNoUndeliveredText() {
        val turn =
            AssistantTurn(
                id = TurnId("turn-1"),
                generated = GeneratedText("all spoken", GenerationState.COMPLETED),
                delivery = AssistantDelivery("all spoken", DeliveryState.COMPLETED),
            )

        assertFalse(turn.hasUndeliveredText)
    }
}
