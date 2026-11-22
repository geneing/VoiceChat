package com.voicechat.assistant.realtime.ws

import com.voicechat.assistant.realtime.RealtimeEvent
import com.voicechat.assistant.realtime.RealtimeEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseAudioGateTest {
    @Test
    fun `replacing a response requests a flush and ignores late audio and termination`() {
        val gate = ResponseAudioGate()
        assertFalse(gate.begin("first"))
        assertTrue(gate.accepts("first"))
        assertTrue(gate.begin("second"))
        assertFalse(gate.accepts("first"))
        assertFalse(gate.end("first"))
        assertTrue(gate.accepts("second"))
    }

    @Test
    fun `speech start keeps the interrupted stream closed through speech stop`() {
        val gate = ResponseAudioGate()
        gate.begin("first")
        gate.speechStarted()
        assertFalse(gate.accepts("first"))
        gate.speechStopped()
        gate.begin("first")
        assertFalse(gate.accepts("first"))
        gate.begin("second")
        assertTrue(gate.accepts("second"))
    }

    @Test
    fun `completed responses close admission and let the following response queue normally`() {
        val gate = ResponseAudioGate()
        gate.begin("first")
        assertTrue(gate.end("first"))
        assertFalse(gate.accepts("first"))
        assertFalse(gate.begin("second"))
        assertTrue(gate.accepts("second"))
        gate.interrupt()
        assertFalse(gate.accepts("second"))
    }

    @Test
    fun `parsing retains response identity for media and cancellation`() {
        val audio = RealtimeEventParser.parse(
            """{"type":"response.audio.delta","response_id":"first","delta":"AAA="}""",
        ) as RealtimeEvent.AssistantAudioDelta
        val cancelled = RealtimeEventParser.parse(
            """{"type":"response.cancelled","response_id":"second"}""",
        ) as RealtimeEvent.ResponseCancelled
        assertEquals("first", audio.responseId)
        assertEquals("second", cancelled.responseId)
    }
}
