package com.voicechat.assistant.realtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NewsSectionsTest {

    @Test
    fun `the three sections keep the answer order`() {
        assertEquals(listOf("world", "markets", "ai"), NewsSections.order)
    }

    @Test
    fun `every section asks for news, not a homepage`() {
        NewsSections.order.forEach { section ->
            val query = NewsSections.queryFor(section)
            assertTrue(
                "$section query was $query",
                query.contains("news today") || query.contains("market today"),
            )
        }
    }

    @Test
    fun `the world section is small, the AI one carries five items`() {
        assertEquals(4, NewsSections.itemsFor("world"))
        assertEquals(4, NewsSections.itemsFor("markets"))
        assertEquals(5, NewsSections.itemsFor("ai"))
    }
}
