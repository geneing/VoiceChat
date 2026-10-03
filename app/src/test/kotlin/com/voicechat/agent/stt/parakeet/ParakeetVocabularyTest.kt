package com.voicechat.agent.stt.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Loader and decode behavior of [ParakeetVocabulary]. */
class ParakeetVocabularyTest {
    private val json =
        """
        {
          "0": "<unk>",
          "1": "\u2581hello",
          "2": "\u2581world",
          "3": "!",
          "1024": "<EOU>",
          "1025": "<EOB>"
        }
        """.trimIndent()

    @Test
    fun fromJsonCountsEntriesAndExposesPieces() {
        val vocab = ParakeetVocabulary.fromJson(json)

        assertEquals(6, vocab.count)
        assertEquals("<unk>", vocab.piece(0))
        assertEquals("\u2581world", vocab.piece(2))
        assertNull(vocab.piece(999))
    }

    @Test
    fun decodeMapsTheWordBoundaryToASpaceAndTrims() {
        val vocab = ParakeetVocabulary.fromJson(json)

        assertEquals("hello world!", vocab.decode(listOf(1, 2, 3)))
        assertEquals("hello", vocab.decode(listOf(1)))
        assertTrue(vocab.decode(emptyList()).isEmpty())
    }

    @Test
    fun decodeSkipsUnknownIds() {
        val vocab = ParakeetVocabulary.fromJson(json)

        // 999 has no piece (including the blank token, which is absent by design).
        assertEquals("hello", vocab.decode(listOf(999, 1, 999)))
    }

    @Test
    fun expectedCountIsThePinnedVocabularyEntryCount() {
        // vocabSize (1026) already counts the two specials: ids 0..1023 are word
        // pieces and 1024/1025 are <EOU>/<EOB>, so the pinned vocab.json has 1026
        // entries.
        assertEquals(1026, ParakeetVocabulary.expectedCount(ParakeetEouConfig.pinned()))
    }

    @Test
    fun fromJsonRejectsNonIntegerKeysAndNonStringValues() {
        assertThrows { ParakeetVocabulary.fromJson("""{"a": "b"}""") }
        assertThrows { ParakeetVocabulary.fromJson("""{"1": 2}""") }
        assertThrows { ParakeetVocabulary.fromJson("{}") }
        assertThrows { ParakeetVocabulary.fromJson("[]") }
    }

    private fun assertThrows(block: () -> Unit) {
        val threw =
            try {
                block()
                false
            } catch (expected: IllegalArgumentException) {
                true
            }
        assertTrue("expected IllegalArgumentException", threw)
    }
}
