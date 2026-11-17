package com.voicechat.assistant.realtime.handoff

import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsPromptTest {

    private val today = LocalDate.of(2026, 11, 16)

    @Test
    fun `the digest prompt asks for the three sections and their lengths`() {
        val system = NewsPrompt.system(today)
        assertTrue(system.contains("Monday, 16 November 2026"))
        assertTrue(system.contains("most impactful world news"))
        assertTrue(system.contains("5-6 sentences"))
        assertTrue(system.contains("bonds"))
        assertTrue(system.contains("two or three paragraphs"))
        assertTrue(system.contains("date"))
        assertTrue(system.contains("no markdown"))
    }

    @Test
    fun `the digest prompt uses only the attached results`() {
        val system = NewsPrompt.system(today)
        assertTrue(system.contains("no web access"))
        assertTrue(system.contains("dated search results"))
        val user = NewsPrompt.user(today, """
            {"world":{"items":[{"title":"T","published":"2026-11-16"}]}}
        """.trimIndent())
        assertTrue(user.contains("Dated search results from the app"))
        assertTrue(user.contains("\"published\":\"2026-11-16\""))
    }

    @Test
    fun `the tool result carries the digest and a spoken hint`() {
        val output = NewsPrompt.toolOutput("World: a story. Markets: up.", "qwen3.8-flash", today)
        assertTrue(output.contains("\"ok\":true"))
        assertTrue(output.contains("World: a story."))
        assertTrue(output.contains("lead with the world news"))
        assertFalse(output.contains("\"error\""))
    }

    @Test
    fun `a failed digest tells the model not to invent stories`() {
        val output = NewsPrompt.toolOutput("", "qwen3.8-flash", today, "HTTP 500")
        assertTrue(output.contains("\"ok\":false"))
        assertTrue(output.contains("do not invent stories"))
    }
}
