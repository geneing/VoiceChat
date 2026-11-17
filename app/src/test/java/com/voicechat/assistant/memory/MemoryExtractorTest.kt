package com.voicechat.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryExtractorTest {

    private fun candidate(text: String, kind: MemoryKind): MemoryCandidate? =
        MemoryExtractor.extract(text).firstOrNull { it.kind == kind }

    @Test
    fun `a spoken sentence with two facts yields both`() {
        val facts = MemoryExtractor.extract("my name is anna and i live in berlin")

        assertEquals(
            "The user's name is Anna.",
            facts.first { it.kind == MemoryKind.IDENTITY }.text,
        )
        assertEquals(
            "The user lives in Berlin.",
            facts.first { it.kind == MemoryKind.PLACE }.text,
        )
    }

    @Test
    fun `an allergy and a diet are constraints, not chatter`() {
        assertEquals(
            "The user is allergic to peanuts.",
            candidate("i'm allergic to peanuts", MemoryKind.PREFERENCE)?.text,
        )
        assertEquals(
            "The user is vegan.",
            candidate("i'm vegan", MemoryKind.PREFERENCE)?.text,
        )
    }

    @Test
    fun `likes and dislikes in one breath stay separate facts`() {
        val facts = MemoryExtractor.extract("i love espresso but i hate long meetings")

        assertEquals(1, facts.count { it.text == "The user likes espresso." })
        assertEquals(1, facts.count { it.text == "The user dislikes long meetings." })
        assertTrue(facts.first { it.text.contains("likes") }.subject.startsWith("likes:"))
        assertTrue(facts.first { it.text.contains("dislikes") }.subject.startsWith("dislikes:"))
    }

    @Test
    fun `a relation and a name are one fact keyed by the relation`() {
        val fact = candidate("my wife's name is anna", MemoryKind.PERSON)

        assertEquals("The user's wife is called Anna.", fact?.text)
        assertEquals("wife", fact?.subject)
    }

    @Test
    fun `an explicit request is pinned`() {
        val facts = MemoryExtractor.extract("remember that the heating engineer comes on tuesday")
        val note = facts.first { it.kind == MemoryKind.NOTE }

        assertTrue(note.pinned)
        assertTrue(note.text.contains("heating engineer"))
        assertTrue(facts.none { !it.pinned })
    }

    @Test
    fun `a reminder to do something is not remembered as a fact`() {
        assertTrue(MemoryExtractor.extract("remember to buy milk").none { it.kind == MemoryKind.NOTE })
    }

    @Test
    fun `a pronoun object says nothing durable`() {
        assertTrue(MemoryExtractor.extract("i like that idea").isEmpty())
        assertTrue(MemoryExtractor.extract("i like this").isEmpty())
    }

    @Test
    fun `a credential is never stored even when it sounds like a fact`() {
        assertTrue(MemoryExtractor.extract("remember that my password is hunter2").isEmpty())
        assertTrue(
            MemoryExtractor.extract("remember that my api key is sk-abc123def456").isEmpty(),
        )
    }

    @Test
    fun `repeating the same fact keeps one candidate`() {
        val facts = MemoryExtractor.extract("i love espresso. i really love espresso.")
        assertEquals(1, facts.count { it.text == "The user likes espresso." })
    }

    @Test
    fun `a routine is kept as one readable line`() {
        val fact = candidate("i always take the 7 train to work", MemoryKind.ROUTINE)

        assertEquals("The user's routine includes: take the 7 train to work.", fact?.text)
    }

    @Test
    fun `trailing punctuation never leaks into the stored fact`() {
        val fact = MemoryExtractor.extract("i live in berlin.").first { it.kind == MemoryKind.PLACE }
        assertFalse(fact.text.endsWith(".."))
        assertEquals("The user lives in Berlin.", fact.text)
    }

    @Test
    fun `small talk yields nothing`() {
        assertTrue(MemoryExtractor.extract("hey, how are you doing today?").isEmpty())
        assertTrue(MemoryExtractor.extract("what's the weather like").isEmpty())
    }
}
