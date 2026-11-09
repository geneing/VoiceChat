package com.voicechat.assistant.realtime.handoff

import com.voicechat.assistant.data.model.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffPromptTest {

    private val transcript = listOf(
        HandoffPrompt.Turn("user", "first question"),
        HandoffPrompt.Turn("assistant", "first answer"),
        HandoffPrompt.Turn("user", "second question"),
    )

    @Test
    fun `messages keep only the configured number of turns`() {
        val limited = HandoffPrompt.messages(transcript, "task", contextTurns = 1)
        val dossier = limited.last().text
        assertTrue(dossier.contains("second question"))
        assertFalse(dossier.contains("first question"))
        assertEquals("system", limited.first().role)
        assertEquals("user", limited.last().role)
    }

    @Test
    fun `messages omit the conversation when context is switched off`() {
        val dossier = HandoffPrompt.messages(transcript, "task", contextTurns = 0).last().text
        assertFalse(dossier.contains("Conversation so far"))
        assertTrue(dossier.contains("Task: task"))
    }

    @Test
    fun `messages carry the task and the depth`() {
        val dossier = HandoffPrompt.messages(transcript, "  derive x  ", 8, "brief").last().text
        assertTrue(dossier.contains("Task: derive x"))
        assertTrue(dossier.contains("Depth: brief"))
        assertTrue(dossier.contains("Current date and time:"))
    }

    @Test
    fun `request body streams with a thinking budget`() {
        val config = HandoffConfig(model = "qwen-flash", thinkingBudget = 2_048)
        val body = Json.parseToJsonElement(
            HandoffPrompt.requestBody(config, HandoffPrompt.messages(emptyList(), "task", 0)),
        ).jsonObject
        assertEquals("qwen-flash", body["model"]?.jsonPrimitive?.content)
        assertEquals("true", body["stream"]?.jsonPrimitive?.content)
        assertEquals(
            "true",
            body["stream_options"]?.jsonObject?.get("include_usage")?.jsonPrimitive?.content,
        )
        assertEquals("true", body["enable_thinking"]?.jsonPrimitive?.content)
        assertEquals("2048", body["thinking_budget"]?.jsonPrimitive?.content)
        assertEquals(2, body["messages"]?.jsonArray?.size)
    }

    @Test
    fun `request body switches thinking off explicitly when the budget is off`() {
        val config = HandoffConfig(model = "qwen-flash", thinkingBudget = 0)
        val body = HandoffPrompt.requestBody(config, HandoffPrompt.messages(emptyList(), "task", 0))
        assertTrue(body.contains("\"enable_thinking\":false"))
        assertFalse(body.contains("thinking_budget"))
    }

    @Test
    fun `request body drops thinking for a model without it`() {
        val config = HandoffConfig(model = "qwen-max", thinkingBudget = 4_096)
        assertFalse(config.thinking)
        assertFalse(
            HandoffPrompt.requestBody(config, HandoffPrompt.messages(emptyList(), "task", 0))
                .contains("enable_thinking"),
        )
    }

    @Test
    fun `known model ids resolve and unknown ones fall back`() {
        assertEquals("qwen-plus", ReasoningModels.resolve("qwen-plus"))
        assertEquals(ReasoningModels.DEFAULT_ID, ReasoningModels.resolve("qwen3-max"))
    }

    @Test
    fun `sections are split on the headings`() {
        val sections = HandoffPrompt.parseSections(
            "## Answer\n" +
                "The discriminant decides the roots.\n\n" +
                "## Spoken summary\n" +
                "The discriminant tells you how many roots there are.",
        )
        assertTrue(sections.answer.contains("decides the roots"))
        assertFalse(sections.answer.contains("## Spoken summary"))
        assertEquals("The discriminant tells you how many roots there are.", sections.summary)
    }

    @Test
    fun `missing headings keep the whole answer and derive a summary`() {
        val sections = HandoffPrompt.parseSections(
            "Start with 5 and 7. Add them to get 12. That is the answer.",
        )
        assertEquals("Start with 5 and 7. Add them to get 12. That is the answer.", sections.answer)
        assertEquals("Start with 5 and 7. Add them to get 12.", sections.summary)
    }

    @Test
    fun `tool output exposes the summary and the answer`() {
        val output = Json.parseToJsonElement(HandoffPrompt.toolOutput(successTrace())).jsonObject
        assertEquals("true", output["ok"]?.jsonPrimitive?.content)
        assertEquals("qwen-flash", output["model"]?.jsonPrimitive?.content)
        assertEquals("It is 391.", output["spoken_summary"]?.jsonPrimitive?.content)
        assertEquals("391", output["answer"]?.jsonPrimitive?.content)
        assertEquals("120", output["reasoning_tokens"]?.jsonPrimitive?.content)
    }

    @Test
    fun `failed tool output tells the model to answer from its own knowledge`() {
        val failed = successTrace().copy(answer = "", summary = "", error = "Timed out.")
        val output = Json.parseToJsonElement(HandoffPrompt.toolOutput(failed)).jsonObject
        assertEquals("false", output["ok"]?.jsonPrimitive?.content)
        assertEquals("Timed out.", output["error"]?.jsonPrimitive?.content)
        assertTrue(output["hint"]?.jsonPrimitive?.content.orEmpty().contains("own knowledge"))
    }

    @Test
    fun `card text shows the model, the timing and the trace`() {
        val text = HandoffPrompt.cardText(successTrace())
        assertTrue(text.startsWith("Deep reasoning · qwen-flash · 9.4s · 120 reasoning tokens"))
        assertTrue(text.contains("391"))
        assertTrue(text.contains("Reasoning trace"))
    }

    @Test
    fun `card text shows the failure reason`() {
        val text = HandoffPrompt.cardText(
            successTrace().copy(error = "Deep reasoning key was rejected."),
        )
        assertTrue(text.contains("Deep reasoning key was rejected."))
        assertFalse(text.contains("Reasoning trace"))
    }

    @Test
    fun `reference keeps the question first and stays delimited`() {
        val prompt = HandoffPrompt.questionWithReference("How many ways?", "Seven ways, by stars and bars.")
        assertTrue(prompt.startsWith("How many ways?"))
        assertTrue(prompt.contains("[Deep-reasoning reference"))
        assertTrue(prompt.contains("Seven ways"))
    }

    private fun successTrace() = HandoffTrace(
        model = "qwen-flash",
        task = "17*23",
        answer = "391",
        summary = "It is 391.",
        reasoning = "17*20=340, 17*3=51, 340+51=391.",
        usage = TokenUsage(inputTokens = 30, outputTokens = 400, totalTokens = 430),
        reasoningTokens = 120,
        elapsedMs = 9_400,
        costUsd = 0.0,
    )
}
