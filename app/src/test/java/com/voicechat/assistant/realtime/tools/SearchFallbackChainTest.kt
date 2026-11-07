package com.voicechat.assistant.realtime.tools

import com.voicechat.assistant.data.model.SearchProvider
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchFallbackChainTest {

    @Test
    fun `selected provider is tried first`() {
        assertEquals(
            listOf(SearchProvider.EXA, SearchProvider.DUCKDUCKGO),
            searchFallbackChain(SearchProvider.EXA, hasExaKey = true, hasBraveKey = false),
        )
    }

    @Test
    fun `providers without credentials are skipped`() {
        assertEquals(
            listOf(SearchProvider.DUCKDUCKGO),
            searchFallbackChain(SearchProvider.EXA, hasExaKey = false, hasBraveKey = false),
        )
    }

    @Test
    fun `an unusable selection still falls back to a working provider`() {
        assertEquals(
            listOf(SearchProvider.DUCKDUCKGO),
            searchFallbackChain(SearchProvider.BRAVE, hasExaKey = false, hasBraveKey = false),
        )
    }

    @Test
    fun `all credentialed providers remain available`() {
        assertEquals(
            listOf(SearchProvider.BRAVE, SearchProvider.EXA, SearchProvider.DUCKDUCKGO),
            searchFallbackChain(SearchProvider.BRAVE, hasExaKey = true, hasBraveKey = true),
        )
    }

    @Test
    fun `providers that rejected their key are skipped`() {
        assertEquals(
            listOf(SearchProvider.DUCKDUCKGO),
            searchFallbackChain(
                SearchProvider.EXA,
                hasExaKey = true,
                hasBraveKey = false,
                rejected = setOf(SearchProvider.EXA),
            ),
        )
    }
}
