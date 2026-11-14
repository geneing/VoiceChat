package com.voicechat.assistant.realtime.tools

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSectionsTest {

    private val now = ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `no section means the whole digest in answer order`() {
        assertEquals(listOf("world", "markets", "ai"), NewsSections.parse(null))
        assertEquals(listOf("world", "markets", "ai"), NewsSections.parse(""))
        assertEquals(listOf("world", "markets", "ai"), NewsSections.parse("all"))
    }

    @Test
    fun `a single section is fetched alone`() {
        assertEquals(listOf("markets"), NewsSections.parse("markets"))
        assertEquals(listOf("ai"), NewsSections.parse(" AI "))
    }

    @Test
    fun `an unknown section falls back to the whole digest`() {
        assertEquals(listOf("world", "markets", "ai"), NewsSections.parse("sports"))
    }

    @Test
    fun `every section asks for news, not a homepage`() {
        NewsSections.order.forEach { section ->
            val query = NewsSections.queryFor(section)
            assertTrue("$section query was $query", query.contains("news today") || query.contains("market today"))
        }
    }

    @Test
    fun `exa news window reaches back two days`() {
        assertEquals(2L, NewsSections.WINDOW_DAYS)
        assertEquals("2026-11-12T10:00:00Z", now.minusDays(NewsSections.WINDOW_DAYS).toInstant().toString())
    }
}
