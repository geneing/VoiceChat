package com.voicechat.assistant.realtime

import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.realtime.handoff.HandoffPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectPolicyTest {

    @Test
    fun `backoff starts short and stays bounded`() {
        assertEquals(250L, ReconnectPolicy.delayMs(1))
        assertEquals(600L, ReconnectPolicy.delayMs(2))
        assertTrue(ReconnectPolicy.delayMs(3) > ReconnectPolicy.delayMs(2))
        assertTrue(ReconnectPolicy.delayMs(4) <= 3_000L)
    }

    @Test
    fun `backoff is clamped for out of range attempts`() {
        assertEquals(ReconnectPolicy.delayMs(1), ReconnectPolicy.delayMs(0))
        assertEquals(3_000L, ReconnectPolicy.delayMs(99))
    }

    @Test
    fun `retries use a shorter connect timeout than the first attempt`() {
        assertEquals(20_000L, ReconnectPolicy.connectTimeoutMs(1))
        assertTrue(ReconnectPolicy.connectTimeoutMs(2) < ReconnectPolicy.connectTimeoutMs(1))
        assertEquals(8_000L, ReconnectPolicy.connectTimeoutMs(5))
    }

    @Test
    fun `total recovery stays within a few seconds`() {
        val total = (1..ReconnectPolicy.RECOVERY_ATTEMPTS).sumOf { ReconnectPolicy.delayMs(it) }
        assertTrue("recovery budget was ${total}ms", total <= 11_000L)
    }

    @Test
    fun `credential and platform errors are permanent`() {
        assertTrue(ReconnectPolicy.isPermanent("API key is not configured."))
        assertTrue(ReconnectPolicy.isPermanent("AOQ requires an API key to allocate a token."))
        assertTrue(ReconnectPolicy.isPermanent("Request failed with HTTP 401"))
        assertTrue(
            ReconnectPolicy.isPermanent(
                "AOQ native libraries are not available for this CPU architecture.",
            ),
        )
    }

    @Test
    fun `network problems are retried`() {
        assertFalse(ReconnectPolicy.isPermanent("Timed out while opening the realtime socket."))
        assertFalse(ReconnectPolicy.isPermanent("Software caused connection abort"))
        assertFalse(ReconnectPolicy.isPermanent("ICE connection failed"))
        assertFalse(ReconnectPolicy.isPermanent(null))
        assertFalse(ReconnectPolicy.isPermanent(""))
    }
}

class SessionResumeTest {

    private fun config() = SessionConfig(
        model = "qwen3.8-omni-flash-realtime",
        voice = "Tina",
        instructions = "Be helpful.",
        transport = TransportKind.WEBSOCKET,
        apiKey = "test",
        host = "ws-test.example.com",
        workspaceId = "ws-test",
    )

    @Test
    fun `recap is appended to the instructions with both roles`() {
        val instructions = SessionPayloads.resumeInstructions(
            "Be helpful.",
            listOf(
                HandoffPrompt.Turn("user", "What is the weather in Osaka?"),
                HandoffPrompt.Turn("assistant", "It is 21 degrees and clear."),
            ),
        )
        assertTrue(instructions.startsWith("Be helpful."))
        assertTrue(instructions.contains("User: What is the weather in Osaka?"))
        assertTrue(instructions.contains("Assistant: It is 21 degrees and clear."))
        assertTrue(instructions.contains("do not mention the reconnect"))
    }

    @Test
    fun `empty history leaves the instructions untouched`() {
        assertEquals("Be helpful.", SessionPayloads.resumeInstructions("Be helpful.", emptyList()))
        assertEquals(
            "Be helpful.",
            SessionPayloads.resumeInstructions(
                "Be helpful.",
                listOf(HandoffPrompt.Turn("user", "   ")),
            ),
        )
    }

    @Test
    fun `recap keeps only the newest turns and collapses whitespace`() {
        val turns = (1..20).map {
            HandoffPrompt.Turn(if (it % 2 == 0) "assistant" else "user", "line   $it\nnext")
        }
        val instructions = SessionPayloads.resumeInstructions("Sys.", turns)
        assertFalse(instructions.contains("line 1\n"))
        assertTrue(instructions.contains("User: line 19 next"))
        assertTrue(instructions.contains("Assistant: line 20 next"))
        assertTrue(instructions.length < 5_000)
    }

    @Test
    fun `session update carries the recap so a reconnect keeps the context`() {
        val restored = config().copy(
            instructions = SessionPayloads.resumeInstructions(
                config().instructions,
                listOf(HandoffPrompt.Turn("user", "Remember the code word: mango.")),
            ),
        )
        val json = SessionPayloads.sessionUpdate(restored)
        assertTrue(json.contains("Remember the code word: mango."))
        assertTrue(json.contains("\"type\":\"session.update\""))
    }
}
