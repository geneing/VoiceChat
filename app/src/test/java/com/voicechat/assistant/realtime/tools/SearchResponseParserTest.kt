package com.voicechat.assistant.realtime.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchResponseParserTest {

    @Test
    fun `exa keeps the publication date so stale snippets are visible`() {
        val results = SearchResponseParser.exa(
            """
            {"results":[
              {"title":"Roundup","url":"https://example.com/a",
               "publishedDate":"2026-11-13T00:00:00.000Z",
               "highlights":["today's story"]},
              {"title":"Homepage","url":"https://example.com/",
               "highlights":["undated sidebar"]}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, results.size)
        assertEquals("2026-11-13T00:00:00.000Z", results[0].published)
        assertNull(results[1].published)
    }

    @Test
    fun `brave reports the page age when it has one`() {
        val results = SearchResponseParser.brave(
            """
            {"web":{"results":[{"title":"T","url":"https://example.com","description":"d",
              "page_age":"2026-11-12T00:00:00"}]}}
            """.trimIndent(),
        )
        assertEquals("2026-11-12T00:00:00", results.single().published)
    }
}
