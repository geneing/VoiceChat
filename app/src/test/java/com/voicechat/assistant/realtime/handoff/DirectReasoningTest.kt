package com.voicechat.assistant.realtime.handoff

import com.voicechat.assistant.realtime.SessionPayloads
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectReasoningTest {
    @Test fun `native search is enabled only for supported models`() {
        val messages = listOf(HandoffPrompt.Turn("user", "Current facts"))
        val enabled = Json.parseToJsonElement(HandoffPrompt.requestBody(HandoffConfig(model = "qwen3.8-flash", nativeWebSearch = true), messages)).jsonObject
        assertEquals("true", enabled["enable_search"]?.jsonPrimitive?.content)
        val unsupported = HandoffPrompt.requestBody(HandoffConfig(model = "qwen-max", nativeWebSearch = true), messages)
        assertFalse(unsupported.contains("enable_search"))
    }

    @Test fun `image and text are sent to the reasoning model together`() {
        val body = Json.parseToJsonElement(HandoffPrompt.requestBody(HandoffConfig(), listOf(
            HandoffPrompt.Turn("user", "What is this?", listOf("abcd")),
        ))).jsonObject
        val content = body["messages"]!!.jsonArray.single().jsonObject["content"]!!.jsonArray
        assertEquals("What is this?", content[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("data:image/jpeg;base64,abcd", content[1].jsonObject["image_url"]!!.jsonObject["url"]!!.jsonPrimitive.content)
    }

    @Test fun `typed exchange is added to voice context without response create`() {
        val event = Json.parseToJsonElement(SessionPayloads.typedExchangeContext("Question", "Answer")).jsonObject
        assertEquals("conversation.item.create", event["type"]!!.jsonPrimitive.content)
        val content = event["item"]!!.jsonObject["content"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(content.contains("User: Question"))
        assertTrue(content.contains("Assistant: Answer"))
    }
}
