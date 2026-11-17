package com.voicechat.assistant.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRankerTest {

    private val now = 1_800_000_000_000L

    private fun fact(
        id: String,
        text: String,
        subject: String = id,
        kind: MemoryKind = MemoryKind.NOTE,
        pinned: Boolean = false,
        updatedAt: Long = now,
        useCount: Int = 0,
    ) = MemoryFact(
        id = id,
        kind = kind,
        subject = subject,
        text = text,
        keywords = "",
        confidence = 0.8f,
        pinned = pinned,
        createdAt = updatedAt,
        updatedAt = updatedAt,
        lastUsedAt = updatedAt,
        useCount = useCount,
    )

    @Test
    fun `a question about breakfast finds the breakfast fact`() {
        val facts = listOf(
            fact("1", "The user likes oats for breakfast."),
            fact("2", "The user lives in Berlin."),
        )

        val ranked = MemoryRanker.rank(facts, "what do i eat for breakfast?", now)

        assertEquals(listOf("1"), ranked.map { it.id })
    }

    @Test
    fun `an unrelated question recalls nothing`() {
        val facts = listOf(fact("1", "The user lives in Berlin."))
        assertTrue(MemoryRanker.rank(facts, "what is the capital of France?", now).isEmpty())
    }

    @Test
    fun `small talk recalls nothing even with facts stored`() {
        val facts = listOf(fact("1", "The user likes espresso."))
        assertTrue(MemoryRanker.rank(facts, "okay thanks, that's all", now).isEmpty())
    }

    @Test
    fun `the best match comes first and the limit is honoured`() {
        val facts = listOf(
            fact("1", "The user likes espresso."),
            fact("2", "The user likes espresso and oat milk."),
            fact("3", "The user likes coffee at work."),
        )

        val ranked = MemoryRanker.rank(facts, "espresso", now, limit = 1)

        assertEquals(1, ranked.size)
        assertTrue(ranked.single().text.contains("espresso"))
    }

    @Test
    fun `a pinned fact outranks an equally relevant one`() {
        val facts = listOf(
            fact("1", "The user likes oat milk."),
            fact("2", "The user likes oat milk.", subject = "pinned", pinned = true),
        )

        assertEquals("2", MemoryRanker.rank(facts, "oat milk", now).first().id)
    }

    @Test
    fun `the session block leads with pinned facts`() {
        val facts = listOf(
            fact("1", "The user likes espresso.", updatedAt = now),
            fact("2", "The user's name is Anna.", pinned = true, updatedAt = now - 90 * 86_400_000L),
        )

        assertEquals(listOf("2", "1"), MemoryRanker.sessionFacts(facts, now).map { it.id })
    }

    @Test
    fun `the session block is capped`() {
        val facts = (1..30).map { fact(it.toString(), "The user likes thing $it.") }
        assertEquals(MemoryRanker.SESSION_LIMIT, MemoryRanker.sessionFacts(facts, now).size)
    }

    @Test
    fun `a script written without spaces still matches`() {
        val facts = listOf(fact("1", "用户喜欢咖啡。"))

        assertTrue(MemoryRanker.tokens("咖啡").isNotEmpty())
        assertEquals(listOf("1"), MemoryRanker.rank(facts, "我喝咖啡", now).map { it.id })
    }

    @Test
    fun `an empty query recalls nothing`() {
        val facts = listOf(fact("1", "The user likes espresso."))
        assertTrue(MemoryRanker.rank(facts, "   ", now).isEmpty())
        assertTrue(MemoryRanker.rank(emptyList(), "espresso", now).isEmpty())
    }
}
