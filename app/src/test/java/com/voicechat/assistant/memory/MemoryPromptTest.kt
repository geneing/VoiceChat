package com.voicechat.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPromptTest {

    private fun fact(
        id: String,
        text: String,
        kind: MemoryKind = MemoryKind.NOTE,
        pinned: Boolean = false,
    ) = MemoryFact(
        id = id,
        kind = kind,
        subject = id,
        text = text,
        keywords = "",
        confidence = 0.8f,
        pinned = pinned,
        createdAt = 0L,
        updatedAt = 0L,
        lastUsedAt = 0L,
        useCount = 0,
    )

    @Test
    fun `an empty store produces no block at all`() {
        assertEquals("", MemoryPrompt.sessionBlock(emptyList()))
        assertEquals("", MemoryPrompt.turnBlock(emptyList()))
        assertEquals("hello", MemoryPrompt.withTurnContext("hello", ""))
    }

    @Test
    fun `the session block lists facts as background, not as instructions`() {
        val block = MemoryPrompt.sessionBlock(
            listOf(
                fact("1", "The user's name is Anna.", MemoryKind.IDENTITY, pinned = true),
                fact("2", "The user lives in Berlin.", MemoryKind.PLACE),
            ),
        )

        assertTrue(block.startsWith("What you remember about the user"))
        assertTrue(block.contains("- The user's name is Anna."))
        assertTrue(block.contains("- The user lives in Berlin."))
        assertTrue(block.contains("never read it out"))
    }

    @Test
    fun `the session block stays inside its character budget`() {
        val facts = (1..20).map { fact("$it", "The user likes a rather long thing number $it for breakfast.") }
        val block = MemoryPrompt.sessionBlock(facts)

        assertTrue(block.length < MemoryPrompt.SESSION_MAX_CHARS + 400)
        assertTrue(block.contains("more facts are stored"))
    }

    @Test
    fun `the turn block stops at its own smaller budget`() {
        val long = "the user likes a long list of things for breakfast and lunch"
        val facts = (1..10).map { fact("$it", "$long $it.") }
        val block = MemoryPrompt.turnBlock(facts)

        assertTrue(block.length <= MemoryPrompt.TURN_MAX_CHARS + 8)
        assertTrue(block.lines().size <= 5)
        assertTrue(block.lines().size < facts.size)
    }

    @Test
    fun `a typed turn keeps the user text first and marks the memory block`() {
        val turn = MemoryPrompt.withTurnContext(
            "what should i cook tonight?",
            "- The user cannot eat peanuts.",
        )

        assertTrue(turn.startsWith("what should i cook tonight?"))
        assertTrue(turn.contains("[Device memory"))
        assertTrue(turn.contains("cannot eat peanuts"))
        assertTrue(turn.contains("do not read it out"))
    }

    @Test
    fun `a store that holds a secret redacts it before the prompt`() {
        val block = MemoryPrompt.sessionBlock(
            listOf(fact("1", "The user's key is sk-abcdefgh12345678.")),
        )

        assertFalse(block.contains("sk-abcdefgh12345678"))
        assertTrue(block.contains("[redacted]"))
    }

    @Test
    fun `a recall with no match says so instead of pretending`() {
        val result = MemoryPrompt.recallResult(emptyList(), "my usual coffee order")

        assertTrue(result.contains("\"count\":0"))
        assertTrue(result.contains("No stored fact matches"))
    }

    @Test
    fun `a recall result carries the facts as json`() {
        val result = MemoryPrompt.recallResult(
            listOf(fact("1", "The user likes espresso.", MemoryKind.PREFERENCE)),
            "coffee",
        )

        assertTrue(result.contains("\"ok\":true"))
        assertTrue(result.contains("\"kind\":\"preference\""))
        assertTrue(result.contains("The user likes espresso."))
    }

    @Test
    fun `remember, forget and errors are json the model can read`() {
        val remembered = MemoryPrompt.rememberResult(
            MemoryCandidate(MemoryKind.NOTE, "x", "The user likes oat milk.", pinned = true),
        )
        assertTrue(remembered.contains("\"ok\":true"))
        assertTrue(remembered.contains("\"pinned\":true"))

        val forgotten = MemoryPrompt.forgetResult(fact("1", "The user likes espresso."))
        assertTrue(forgotten.contains("\"forgotten\":\"The user likes espresso.\""))

        val error = MemoryPrompt.error("Memory is unavailable.")
        assertTrue(error.contains("\"ok\":false"))
        assertTrue(error.contains("unavailable"))
    }
}
