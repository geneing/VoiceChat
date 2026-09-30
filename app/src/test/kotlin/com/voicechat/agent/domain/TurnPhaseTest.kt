package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnPhaseTest {
    @Test
    fun happyPathLifecycleIsLegal() {
        var phase = TurnPhase.IDLE
        phase = phase.transitionTo(TurnPhase.LISTENING)
        phase = phase.transitionTo(TurnPhase.GENERATING)
        phase = phase.transitionTo(TurnPhase.SPEAKING)
        phase = phase.transitionTo(TurnPhase.COMPLETED)

        assertEquals(TurnPhase.COMPLETED, phase)
        assertTrue(phase.isTerminal)
    }

    @Test
    fun bargeInInterruptionReturnsToListening() {
        assertEquals(
            TurnPhase.LISTENING,
            TurnPhase.SPEAKING.transitionTo(TurnPhase.INTERRUPTED).transitionTo(TurnPhase.LISTENING),
        )
    }

    @Test
    fun listeningCanBeDiscardedWhenThereIsNoSpeech() {
        assertEquals(TurnPhase.IDLE, TurnPhase.LISTENING.transitionTo(TurnPhase.IDLE))
    }

    @Test
    fun everyNonTerminalPhaseCanBeCancelledOrFail() {
        listOf(TurnPhase.LISTENING, TurnPhase.GENERATING, TurnPhase.SPEAKING).forEach { phase ->
            assertTrue(phase.canTransitionTo(TurnPhase.CANCELLED))
            assertTrue(phase.canTransitionTo(TurnPhase.FAILED))
        }
        assertEquals(
            TurnPhase.CANCELLED,
            TurnPhase.GENERATING.transitionTo(TurnPhase.CANCELLED),
        )
    }

    @Test
    fun terminalPhasesHaveNoOutgoingTransitions() {
        listOf(TurnPhase.COMPLETED, TurnPhase.CANCELLED, TurnPhase.FAILED).forEach { phase ->
            assertTrue(phase.isTerminal)
            TurnPhase.entries.forEach { next ->
                assertFalse("$phase should not transition to $next", phase.canTransitionTo(next))
            }
        }
    }

    @Test
    fun invalidTransitionsAreRejected() {
        assertFalse(TurnPhase.IDLE.canTransitionTo(TurnPhase.SPEAKING))
        assertFalse(TurnPhase.IDLE.canTransitionTo(TurnPhase.IDLE))
        assertFalse(TurnPhase.COMPLETED.canTransitionTo(TurnPhase.LISTENING))
        assertFalse(TurnPhase.GENERATING.canTransitionTo(TurnPhase.LISTENING))

        assertThrows(IllegalArgumentException::class.java) { TurnPhase.IDLE.transitionTo(TurnPhase.SPEAKING) }
        assertThrows(IllegalArgumentException::class.java) { TurnPhase.COMPLETED.transitionTo(TurnPhase.LISTENING) }
        assertThrows(IllegalArgumentException::class.java) { TurnPhase.SPEAKING.transitionTo(TurnPhase.SPEAKING) }
    }

    @Test
    fun manualTextCanGenerateWithoutListening() {
        // M21 finalized this edge: manual text has no listening stage.
        assertEquals(TurnPhase.GENERATING, TurnPhase.IDLE.transitionTo(TurnPhase.GENERATING))
    }

    @Test
    fun generationCanCompleteOrBeInterruptedBeforeTheFirstAudibleChunk() {
        // A text-only reply completes straight from GENERATING.
        assertEquals(TurnPhase.COMPLETED, TurnPhase.GENERATING.transitionTo(TurnPhase.COMPLETED))
        // A streamed reply may start speaking while generation is still in flight.
        assertEquals(TurnPhase.SPEAKING, TurnPhase.GENERATING.transitionTo(TurnPhase.SPEAKING))
        // Barge-in before the first chunk is audible still interrupts the turn.
        assertEquals(TurnPhase.INTERRUPTED, TurnPhase.GENERATING.transitionTo(TurnPhase.INTERRUPTED))
    }
}
