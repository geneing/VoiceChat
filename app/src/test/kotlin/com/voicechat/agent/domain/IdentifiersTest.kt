package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IdentifiersTest {
    @Test
    fun blankIdentifiersAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { ConversationId("") }
        assertThrows(IllegalArgumentException::class.java) { ConversationId("   ") }
        assertThrows(IllegalArgumentException::class.java) { TurnId("") }
        assertThrows(IllegalArgumentException::class.java) { ProviderId("") }
        assertThrows(IllegalArgumentException::class.java) { ModelId("") }
        assertThrows(IllegalArgumentException::class.java) { EngineId("") }
        assertThrows(IllegalArgumentException::class.java) { UtteranceId("") }
        assertThrows(IllegalArgumentException::class.java) { TraceId("") }
        assertThrows(IllegalArgumentException::class.java) { TraceId("   ") }
    }

    @Test
    fun identifiersSerialiseToTheirRawValue() {
        assertEquals("conversation-1", ConversationId("conversation-1").toString())
        assertEquals("turn-1", TurnId("turn-1").toString())
        assertEquals("openai", ProviderId("openai").toString())
        assertEquals("gpt", ModelId("gpt").toString())
        assertEquals("mlkit-stt", EngineId("mlkit-stt").toString())
        assertEquals("utterance-1", UtteranceId("utterance-1").toString())
        assertEquals("trace-1", TraceId("trace-1").toString())
    }
}
