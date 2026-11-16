package com.voicechat.assistant.realtime.handoff

import com.voicechat.assistant.data.model.NewsSearchMode
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsPromptTest {

    private val today = LocalDate.of(2026, 11, 16)
    private val config = HandoffConfig(model = "qwen3.8-flash", thinkingBudget = 0)

    @Test
    fun `the digest prompt asks for the three sections and dates`() {
        val system = NewsPrompt.system(today, NewsSearchMode.APP)
        assertTrue(system.contains("Monday, 16 November 2026"))
        assertTrue(system.contains("Three top world stories"))
        assertTrue(system.contains("market conditions"))
        assertTrue(system.contains("Five technical AI items"))
        assertTrue(system.contains("date"))
        assertTrue(system.contains("no markdown"))
    }

    @Test
    fun `app search mode uses the attached results and denies web access`() {
        val system = NewsPrompt.system(today, NewsSearchMode.APP)
        assertTrue(system.contains("no web access"))
        assertTrue(system.contains("dated search results"))
        val user = NewsPrompt.user(today, """
            {"world":{"items":[{"title":"T","published":"2026-11-16"}]}}
        """.trimIndent())
        assertTrue(user.contains("Dated search results from the app"))
        assertTrue(user.contains("\"published\":\"2026-11-16\""))
    }

    @Test
    fun `a refusal to search is detected so the app can retry`() {
        assertTrue(
            NewsPrompt.looksUnverified(
                "I cannot provide real-time news for today as my knowledge cutoff is in 2024.",
            ),
        )
        assertTrue(NewsPrompt.looksUnverified("I do not have access to live web search."))
        assertFalse(
            NewsPrompt.looksUnverified(
                "Three world stories today: talks resumed in Vienna (16 Nov). " +
                    "Markets: the S&P 500 rose 0.4% on chip earnings. AI: a new " +
                    "open-weight model from Stanford was released.",
            ),
        )
    }

    @Test
    fun `model search mode enables the vendor flag in the request body`() {
        val messages = listOf(
            HandoffPrompt.Turn("system", NewsPrompt.system(today, NewsSearchMode.MODEL)),
            HandoffPrompt.Turn("user", NewsPrompt.user(today, null)),
        )
        val native = HandoffPrompt.requestBody(config, messages, nativeSearch = true)
        assertTrue(native.contains("\"enable_search\":true"))
        assertTrue(native.contains("\"forced_search\":true"))
        val plain = HandoffPrompt.requestBody(config, messages)
        assertFalse(plain.contains("enable_search"))
    }

    @Test
    fun `no search mode tells the model to say it cannot verify`() {
        val system = NewsPrompt.system(today, NewsSearchMode.OFF)
        assertTrue(system.contains("If you cannot verify"))
        assertFalse(system.contains("Web search is enabled"))
    }

    @Test
    fun `the tool result carries the digest and a spoken hint`() {
        val output = NewsPrompt.toolOutput("World: a story. Markets: up.", "qwen3.8-flash", today)
        assertTrue(output.contains("\"ok\":true"))
        assertTrue(output.contains("World: a story."))
        assertTrue(output.contains("lead with the world stories"))
        assertFalse(output.contains("\"error\""))
    }

    @Test
    fun `a failed digest tells the model not to invent stories`() {
        val output = NewsPrompt.toolOutput("", "qwen3.8-flash", today, "HTTP 500")
        assertTrue(output.contains("\"ok\":false"))
        assertTrue(output.contains("do not invent stories"))
    }
}
