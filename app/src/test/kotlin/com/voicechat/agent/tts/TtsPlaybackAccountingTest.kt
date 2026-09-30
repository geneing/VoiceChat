package com.voicechat.agent.tts

import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Playback accounting must distinguish generated, queued, started, and audible
 * text, and must never leave a queued chunk without a terminal state.
 */
class TtsPlaybackAccountingTest {
    private val first = UtteranceId("utterance-1")
    private val second = UtteranceId("utterance-2")

    @Test
    fun tracksGeneratedQueuedStartedAndDelivered() {
        val accounting = TtsPlaybackAccounting()
        accounting.onGenerated("Hello ")
        accounting.onGenerated("world.")

        accounting.onEvent(first, TtsEvent.Queued(first, "Hello "))
        accounting.onEvent(second, TtsEvent.Queued(second, "world."))
        accounting.onEvent(first, TtsEvent.Started(first, "Hello "))
        accounting.onEvent(second, TtsEvent.Started(second, "world."))
        accounting.onEvent(first, TtsEvent.Delivered(first, "Hello "))
        accounting.onEvent(second, TtsEvent.Delivered(second, "world."))

        assertEquals(12, accounting.generatedCharacterCount)
        assertEquals(12, accounting.queuedCharacterCount)
        assertEquals(12, accounting.startedCharacterCount)
        assertEquals(12, accounting.deliveredCharacterCount)
        assertEquals("Hello world.", accounting.queuedText)
        assertEquals("Hello world.", accounting.startedText)
        assertEquals("Hello world.", accounting.deliveredText)
        assertTrue(accounting.isFullyAccounted)
        assertFalse(accounting.hasUnaccounted)
        assertEquals(DeliveryState.COMPLETED, accounting.state)
        assertEquals(AssistantDelivery("Hello world.", DeliveryState.COMPLETED), accounting.toDelivery())
    }

    @Test
    fun anInterruptionLeavesNothingUnaccountedAndKeepsOnlyWhatWasHeard() {
        val accounting = TtsPlaybackAccounting()
        accounting.onGenerated("Hello world.")

        accounting.onEvent(first, TtsEvent.Queued(first, "Hello "))
        accounting.onEvent(second, TtsEvent.Queued(second, "world."))
        accounting.onEvent(first, TtsEvent.Started(first, "Hello "))
        accounting.onEvent(second, TtsEvent.Started(second, "world."))
        accounting.onEvent(first, TtsEvent.Delivered(first, "Hello "))
        accounting.onEvent(second, TtsEvent.Interrupted(second, "wor"))

        assertEquals("Hello world.", accounting.queuedText)
        assertEquals("Hello wor", accounting.deliveredText)
        assertEquals(9, accounting.deliveredCharacterCount)
        assertTrue("a stop must account for every queued chunk", accounting.isFullyAccounted)
        assertEquals(DeliveryState.INTERRUPTED, accounting.state)
        assertEquals(AssistantDelivery("Hello wor", DeliveryState.INTERRUPTED), accounting.toDelivery())
    }

    @Test
    fun aChunkWithNoTerminalEventIsReportedAsUnaccounted() {
        val accounting = TtsPlaybackAccounting()
        accounting.onEvent(first, TtsEvent.Queued(first, "Hello "))
        accounting.onEvent(second, TtsEvent.Queued(second, "world."))
        accounting.onEvent(first, TtsEvent.Started(first, "Hello "))

        assertTrue(accounting.hasUnaccounted)
        assertEquals(DeliveryState.SPEAKING, accounting.state)
    }

    @Test
    fun aFailedChunkIsTerminal() {
        val accounting = TtsPlaybackAccounting()
        accounting.onGenerated("Hello")
        accounting.onEvent(first, TtsEvent.Queued(first, "Hello"))
        accounting.onEvent(first, TtsEvent.Failed(VoiceAgentError(ErrorCode.TTS_SYNTHESIS_FAILED)))

        assertTrue(accounting.isFullyAccounted)
        assertEquals(0, accounting.deliveredCharacterCount)
        assertEquals(DeliveryState.FAILED, accounting.state)
    }

    @Test
    fun deliveredTextIsClampedToTheGeneratedPrefix() {
        val accounting = TtsPlaybackAccounting()
        accounting.onEvent(first, TtsEvent.Queued(first, "Hello world"))
        accounting.onEvent(first, TtsEvent.Delivered(first, "Hello world"))

        // The caller believes less text was generated than the ledger delivered.
        assertEquals(AssistantDelivery("Hello ", DeliveryState.COMPLETED), accounting.toDelivery("Hello there"))
    }

    @Test
    fun anEmptyLedgerIsNotStarted() {
        val accounting = TtsPlaybackAccounting()

        assertTrue(accounting.isFullyAccounted)
        assertEquals(DeliveryState.NOT_STARTED, accounting.state)
        assertEquals(AssistantDelivery("", DeliveryState.NOT_STARTED), accounting.toDelivery(""))
    }
}
