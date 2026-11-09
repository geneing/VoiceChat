package com.voicechat.assistant.realtime.handoff

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningClientRequestTest {

    private val client = ReasoningClient(
        client = OkHttpClient(),
        endpoint = "https://reasoning.test/compatible-mode/v1",
    )

    private val messages = listOf(
        HandoffPrompt.Turn("system", "consultant"),
        HandoffPrompt.Turn("user", "Task: derive x"),
    )

    private fun bodyOf(request: Request): String {
        val buffer = Buffer()
        request.body?.writeTo(buffer)
        return buffer.readUtf8()
    }

    @Test
    fun `request authorizes with the workspace key`() {
        val request = client.request("test-key", HandoffConfig(), messages)
        assertEquals("Bearer test-key", request.header("Authorization"))
        assertEquals(
            "https://reasoning.test/compatible-mode/v1/chat/completions",
            request.url.toString(),
        )
        assertEquals("POST", request.method)
    }

    @Test
    fun `request streams and asks for usage`() {
        val body = Json.parseToJsonElement(
            bodyOf(client.request("test-key", HandoffConfig(), messages)),
        ).jsonObject
        assertTrue(body.getValue("stream").jsonPrimitive.content.toBoolean())
        assertEquals(
            "true",
            body.getValue("stream_options").jsonObject.getValue("include_usage")
                .jsonPrimitive.content,
        )
        assertEquals(ReasoningModels.DEFAULT_ID, body.getValue("model").jsonPrimitive.content)
    }

    @Test
    fun `budgeted thinking sends enable_thinking and the budget`() {
        val config = HandoffConfig(model = "qwen3.8-flash", thinkingBudget = 1_024)
        val body = Json.parseToJsonElement(
            bodyOf(client.request("test-key", config, messages)),
        ).jsonObject
        assertEquals("true", body.getValue("enable_thinking").jsonPrimitive.content)
        assertEquals(1_024, body.getValue("thinking_budget").jsonPrimitive.content.toInt())
    }

    @Test
    fun `a zero budget turns thinking off without sending a budget`() {
        val config = HandoffConfig(model = "qwen3.8-flash", thinkingBudget = 0)
        val body = Json.parseToJsonElement(
            bodyOf(client.request("test-key", config, messages)),
        ).jsonObject
        assertEquals("false", body.getValue("enable_thinking").jsonPrimitive.content)
        assertFalse(body.containsKey("thinking_budget"))
    }

    @Test
    fun `always-thinking models never receive a budget they reject`() {
        val config = HandoffConfig(model = "qwq-plus-2025-03-05", thinkingBudget = 2_048)
        val body = Json.parseToJsonElement(
            bodyOf(client.request("test-key", config, messages)),
        ).jsonObject
        assertFalse(body.containsKey("thinking_budget"))
        assertFalse(body.containsKey("enable_thinking"))
        assertTrue(config.thinking)
    }

    @Test
    fun `non-thinking models send no thinking fields`() {
        val config = HandoffConfig(model = "qwen-max", thinkingBudget = 2_048)
        val body = Json.parseToJsonElement(
            bodyOf(client.request("test-key", config, messages)),
        ).jsonObject
        assertFalse(body.containsKey("thinking_budget"))
        assertFalse(body.containsKey("enable_thinking"))
        assertFalse(config.thinking)
    }

    @Test
    fun `an unknown stored model falls back to the default`() {
        val body = Json.parseToJsonElement(
            bodyOf(client.request("test-key", HandoffConfig(model = "qwen-retired"), messages)),
        ).jsonObject
        assertEquals(ReasoningModels.DEFAULT_ID, body.getValue("model").jsonPrimitive.content)
    }
}
