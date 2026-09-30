package com.voicechat.agent.orchestration

import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic tests for the pure [TurnStateMachine].
 *
 * No dispatcher, provider, TTS engine, device, or network is involved: each test
 * feeds a typed sequence of events and asserts the resulting lifecycle. This is
 * where revisions, out-of-order late events, cancellation races, and truthful
 * delivery accounting are proven exactly.
 */
class TurnStateMachineTest {
    private val turn = TurnId("turn-1")
    private val next = TurnId("turn-2")

    private fun textTurn(): TurnStateMachine =
        TurnStateMachine(speechEnabled = false).apply {
            beginGeneration(turn, UserTurnSource.TEXT, Transcript.final("question"))
        }

    private fun speechTurn(): TurnStateMachine =
        TurnStateMachine(speechEnabled = true).apply {
            beginGeneration(turn, UserTurnSource.VOICE, Transcript.final("question"))
        }

    // region manual text turns

    @Test
    fun aManualTextTurnCompletesWithDeliveredEqualToGenerated() {
        val machine = textTurn()

        assertTrue(machine.reduce(TurnEvent.ProviderDelta(turn, "Hello")))
        assertTrue(machine.reduce(TurnEvent.ProviderDelta(turn, " there")))
        assertTrue(machine.reduce(TurnEvent.ProviderCompleted(turn)))

        assertEquals(TurnPhase.COMPLETED, machine.phase)
        assertEquals(GenerationState.COMPLETED, machine.generationState)
        assertEquals(TurnOutcome.Completed, machine.outcome)
        assertEquals("Hello there", machine.generatedText)

        val assistant = machine.assistantTurn(TurnId("assistant-1"))!!
        assertEquals("Hello there", assistant.generated.text)
        assertEquals(GenerationState.COMPLETED, assistant.generated.state)
        assertEquals("Hello there", assistant.delivery.deliveredText)
        assertEquals(DeliveryState.COMPLETED, assistant.delivery.state)
        assertFalse(assistant.hasUndeliveredText)
    }

    @Test
    fun aFlowThatEndsBeforeAnyOutputLeavesNoAssistantTurn() {
        val machine = speechTurn()
        assertNull(machine.assistantTurn(TurnId("assistant-1")))

        machine.reduce(TurnEvent.CancelRequested(turn))
        assertNull(machine.assistantTurn(TurnId("assistant-1")))
    }

    // endregion

    // region voice listening and revisions

    @Test
    fun interimTranscriptRevisionsAreAppliedInOrderAndStaleOnesAreDropped() {
        val machine = TurnStateMachine()
        machine.beginListening(turn, UserTurnSource.VOICE)

        assertTrue(machine.reduce(TurnEvent.Provisional(turn, Transcript.interim("hel", TranscriptRevision(1)))))
        assertTrue(machine.reduce(TurnEvent.Provisional(turn, Transcript.interim("hello", TranscriptRevision(2)))))
        // A late, older revision must not overwrite the newer hypothesis.
        assertFalse(machine.reduce(TurnEvent.Provisional(turn, Transcript.interim("he", TranscriptRevision(1)))))

        assertEquals("hello", machine.listening!!.provisional!!.text)
        assertEquals(1, machine.staleEventDrops)
    }

    @Test
    fun noSpeechEndsListeningWithoutACommittedTurn() {
        val machine = TurnStateMachine()
        machine.beginListening(turn, UserTurnSource.VOICE)

        assertTrue(machine.reduce(TurnEvent.TranscriptRejected(turn, TranscriptRejection.NO_SPEECH)))

        assertEquals(TurnPhase.IDLE, machine.phase)
        assertEquals(TurnOutcome.NoSpeech(), machine.outcome)
        assertNull(machine.assistantTurn(TurnId("assistant-1")))
    }

    @Test
    fun anEmptyOrLowConfidenceTranscriptIsAnExplicitOutcome() {
        val empty = TurnStateMachine()
        empty.beginListening(turn)
        empty.reduce(TurnEvent.TranscriptRejected(turn, TranscriptRejection.EMPTY))
        assertEquals(TurnOutcome.EmptyTranscript(TranscriptRejection.EMPTY), empty.outcome)

        val lowConfidence = TurnStateMachine()
        lowConfidence.beginListening(next)
        lowConfidence.reduce(TurnEvent.TranscriptRejected(next, TranscriptRejection.LOW_CONFIDENCE))
        assertEquals(TurnOutcome.EmptyTranscript(TranscriptRejection.LOW_CONFIDENCE), lowConfidence.outcome)
    }

    // endregion

    // region provider errors

    @Test
    fun aProviderFailurePersistsThePartialTextAsAFailure() {
        val machine = textTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "half a sen"))
        machine.reduce(
            TurnEvent.ProviderFailed(
                turnId = turn,
                error = VoiceAgentError(ErrorCode.LLM_RATE_LIMITED),
                partialText = "half a sen",
            ),
        )

        assertEquals(TurnPhase.FAILED, machine.phase)
        assertEquals(GenerationState.FAILED, machine.generationState)
        assertEquals(DeliveryState.FAILED, machine.deliveryStateForTest())
        assertEquals(TurnOutcome.ProviderError(VoiceAgentError(ErrorCode.LLM_RATE_LIMITED)), machine.outcome)

        val assistant = machine.assistantTurn(TurnId("assistant-1"))!!
        assertEquals("half a sen", assistant.generated.text)
        assertEquals("half a sen", assistant.delivery.deliveredText)
    }

    @Test
    fun aRemoteCancellationIsNeverStoredAsACompletedReply() {
        val machine = textTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "partial"))
        machine.reduce(TurnEvent.ProviderCancelled(turn, partialText = "partial"))

        assertEquals(TurnPhase.CANCELLED, machine.phase)
        assertEquals(GenerationState.CANCELLED, machine.generationState)
        assertEquals(DeliveryState.INTERRUPTED, machine.deliveryStateForTest())
        assertEquals(TurnOutcome.Cancelled, machine.outcome)
    }

    // endregion

    // region cancellation races and late events

    @Test
    fun cancellingMidStreamKeepsOnlyTheDeliveredPrefixAndDropsLateEvents() {
        val machine = textTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "Hello"))
        machine.reduce(TurnEvent.CancelRequested(turn))

        assertEquals(TurnPhase.CANCELLED, machine.phase)
        assertEquals(DeliveryState.INTERRUPTED, machine.deliveryStateForTest())

        // A late completion and a late delta from the cancelled turn are dropped.
        assertFalse(machine.reduce(TurnEvent.ProviderDelta(turn, " world")))
        assertFalse(machine.reduce(TurnEvent.ProviderCompleted(turn)))

        assertEquals("Hello", machine.generatedText)
        val assistant = machine.assistantTurn(TurnId("assistant-1"))!!
        assertEquals(GenerationState.CANCELLED, assistant.generated.state)
        assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
        assertEquals("Hello", assistant.delivery.deliveredText)
        assertEquals(2, machine.staleEventDrops)
    }

    @Test
    fun eventsFromASupersededTurnCannotMutateTheNewTurn() {
        val machine = textTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "old"))
        machine.reduce(TurnEvent.CancelRequested(turn))

        machine.beginGeneration(next, UserTurnSource.TEXT, Transcript.final("next question"))
        assertFalse(machine.reduce(TurnEvent.ProviderDelta(turn, "stale")))
        assertFalse(machine.reduce(TurnEvent.ProviderCompleted(turn)))

        assertEquals(next, machine.turnId)
        assertEquals("", machine.generatedText)
        assertEquals(TurnPhase.GENERATING, machine.phase)
        assertEquals(2, machine.staleEventDrops)
    }

    // endregion

    // region TTS delivery accounting

    @Test
    fun speechDeliveryTracksQueuedVersusDeliveredText() {
        val machine = speechTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "Hello "))
        machine.reduce(TurnEvent.TtsQueued(turn, utterance("u1"), "Hello "))
        assertEquals(TurnPhase.SPEAKING, machine.phase)

        machine.reduce(TurnEvent.TtsStarted(turn, utterance("u1"), "Hello "))
        // Provider finished while the first utterance is still playing.
        machine.reduce(TurnEvent.ProviderCompleted(turn))
        assertEquals(TurnPhase.SPEAKING, machine.phase)
        assertNull(machine.outcome)

        machine.reduce(TurnEvent.TtsDelivered(turn, utterance("u1"), "Hello "))
        assertEquals(TurnPhase.COMPLETED, machine.phase)
        assertEquals("Hello ", machine.deliveredText)
    }

    @Test
    fun aBargeInInterruptionStoresOnlyWhatWasAudible() {
        val machine = speechTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "Hello world"))
        machine.reduce(TurnEvent.TtsQueued(turn, utterance("u1"), "Hello world"))
        machine.reduce(TurnEvent.TtsStarted(turn, utterance("u1"), "Hello world"))

        // Barge-in: the utterance is cut short and only the audible prefix is kept.
        machine.reduce(TurnEvent.TtsInterrupted(turn, utterance("u1"), deliveredText = "Hello "))
        machine.reduce(TurnEvent.InterruptRequested(turn))

        assertEquals(TurnPhase.INTERRUPTED, machine.phase)
        assertEquals(TurnOutcome.Interrupted, machine.outcome)
        assertEquals("Hello ", machine.deliveredText)

        val assistant = machine.assistantTurn(TurnId("assistant-1"))!!
        assertEquals("Hello world", assistant.generated.text)
        assertEquals("Hello ", assistant.delivery.deliveredText)
        assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
        assertTrue(assistant.hasUndeliveredText)
    }

    @Test
    fun aTtsFailureEndsTheTurnAsATtsFailureNotASuccess() {
        val machine = speechTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "Hello"))
        machine.reduce(TurnEvent.TtsQueued(turn, utterance("u1"), "Hello"))
        machine.reduce(TurnEvent.ProviderCompleted(turn))
        machine.reduce(TurnEvent.TtsFailed(turn, utterance("u1"), VoiceAgentError(ErrorCode.TTS_PLAYBACK_FAILED)))

        assertEquals(TurnPhase.FAILED, machine.phase)
        assertEquals(
            TurnOutcome.TtsFailure(VoiceAgentError(ErrorCode.TTS_PLAYBACK_FAILED)),
            machine.outcome,
        )
        assertEquals(VoiceAgentError(ErrorCode.TTS_PLAYBACK_FAILED), machine.failure)
        assertEquals(DeliveryState.FAILED, machine.deliveryStateForTest())
    }

    @Test
    fun aTtsEventAfterTheTurnFinishedIsDropped() {
        val machine = speechTurn()
        machine.reduce(TurnEvent.ProviderDelta(turn, "Hello"))
        machine.reduce(TurnEvent.CancelRequested(turn))

        assertFalse(machine.reduce(TurnEvent.TtsDelivered(turn, utterance("u1"), "Hello")))
        assertFalse(machine.reduce(TurnEvent.TtsStarted(turn, utterance("u1"), "Hello")))
        assertEquals(2, machine.staleEventDrops)
    }

    // endregion

    private fun utterance(id: String) = UtteranceId(id)
}

/** Snapshot accessors used only by these tests. */
private fun TurnStateMachine.deliveryStateForTest(): DeliveryState = record()!!.deliveryState
