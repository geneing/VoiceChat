package com.voicechat.agent.orchestration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for [TtsTextChunker]. */
class TtsTextChunkerTest {
    @Test
    fun emitsAChunkAtASentenceBoundary() {
        val chunker = TtsTextChunker()

        assertEquals(listOf("Hello. "), chunker.append("Hello. "))
        assertEquals(emptyList<String>(), chunker.append("Wor"))
        assertEquals(listOf("World."), chunker.append("ld."))
        assertNull(chunker.flush())
    }

    @Test
    fun splitsLongTextAtWhitespaceNearTheCap() {
        val chunker = TtsTextChunker(maxCharacters = 16)
        val text = "alpha beta gamma delta"

        val chunks = chunker.append(text)
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks.all { it.length <= 16 })
        // Nothing is lost or duplicated across chunks.
        assertEquals(text, chunks.joinToString(separator = "") + (chunker.flush() ?: ""))
    }

    @Test
    fun hardSplitsAnUnbrokenToken() {
        val chunker = TtsTextChunker(maxCharacters = 16)
        val text = "a".repeat(40)

        val chunks = chunker.append(text)
        assertEquals(listOf("a".repeat(16), "a".repeat(16)), chunks)
        assertEquals("a".repeat(8), chunker.flush())
    }

    @Test
    fun flushReturnsTheTrailingFragmentOnlyOnce() {
        val chunker = TtsTextChunker()
        chunker.append("trailing text")

        assertEquals("trailing text", chunker.flush())
        assertNull(chunker.flush())
    }

    @Test
    fun blankRemainderIsNeverEmitted() {
        val chunker = TtsTextChunker()
        chunker.append("   ")
        assertNull(chunker.flush())
    }
}
