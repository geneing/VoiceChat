package com.voicechat.assistant.realtime.tools

import java.time.ZoneOffset
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRecencyTest {

    private val now = ZonedDateTime.of(2026, 11, 14, 10, 0, 0, 0, ZoneOffset.UTC)

    @Test
    fun `news questions are limited to the last few days`() {
        assertEquals(
            "2026-11-11T10:00:00Z",
            SearchRecency.startPublishedDate("top news today headlines", now),
        )
        assertEquals(
            "2026-11-11T10:00:00Z",
            SearchRecency.startPublishedDate("What's the latest in AI?", now),
        )
    }

    @Test
    fun `evergreen questions keep the full index`() {
        assertNull(SearchRecency.startPublishedDate("plot of joseph technicolor dreamcoat", now))
        assertNull(SearchRecency.startPublishedDate("Rust release 1.100 highlights", now))
        assertFalse(SearchRecency.isNewsQuery("how does a quicksort partition step work"))
    }

    @Test
    fun `an undated weekend plan still counts as a current-events question`() {
        assertTrue(SearchRecency.isNewsQuery("things to do in San Francisco this Saturday"))
    }

    @Test
    fun `cutoff parses back to the same instant`() {
        assertEquals(
            now.minusDays(SearchRecency.NEWS_WINDOW_DAYS).toInstant(),
            SearchRecency.cutoff("news today", now),
        )
    }
}
