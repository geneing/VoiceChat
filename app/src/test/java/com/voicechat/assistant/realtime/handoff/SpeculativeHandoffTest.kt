package com.voicechat.assistant.realtime.handoff

import com.voicechat.assistant.data.model.TokenUsage
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeculativeHandoffTest {

    private fun answer(): Result<ReasoningResult> = Result.success(
        ReasoningResult(
            model = "qwen3.8-flash",
            answer = "42",
            summary = "42",
            reasoning = "step",
            usage = TokenUsage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
            reasoningTokens = 3,
            elapsedMs = 900,
        ),
    )

    @Test
    fun `only one warm call runs at a time`() {
        val handoff = SpeculativeHandoff()
        assertTrue(handoff.start("explain the trade-offs of JWT and sessions") != null)
        assertTrue(handoff.running)
        assertNull(handoff.start("a second question entirely"))
    }

    @Test
    fun `a blank transcript is not warmed`() {
        val handoff = SpeculativeHandoff()
        assertNull(handoff.start("   "))
        assertFalse(handoff.running)
    }

    @Test
    fun `the same task reuses the warm call`() {
        val handoff = SpeculativeHandoff()
        val deferred = handoff.start("compare JWT and session cookies for auth")
        assertTrue(handoff.take("compare JWT and session cookies for auth") is
            SpeculativeHandoff.WarmTake.Reuse)
        assertFalse(handoff.running)
        assertTrue(deferred?.isActive == true)
    }

    @Test
    fun `a restated task still reuses the warm call`() {
        val handoff = SpeculativeHandoff()
        handoff.start("compare JWT and session cookies for auth")
        val take = handoff.take("compare JWT and session cookies for authentication trade-offs")
        assertTrue(take is SpeculativeHandoff.WarmTake.Reuse)
    }

    @Test
    fun `a different task drops the warm call`() {
        val handoff = SpeculativeHandoff()
        val deferred = handoff.start("compare JWT and session cookies for auth")
        val take = handoff.take("what is the weather in Paris tomorrow")
        assertTrue(take is SpeculativeHandoff.WarmTake.Mismatch)
        assertTrue(deferred?.isCancelled == true)
        assertFalse(handoff.running)
    }

    @Test
    fun `taking without a warm call reports none`() {
        assertTrue(SpeculativeHandoff().take("anything") is SpeculativeHandoff.WarmTake.None)
    }

    @Test
    fun `cancel clears the slot once`() {
        val handoff = SpeculativeHandoff()
        val deferred: CompletableDeferred<Result<ReasoningResult>>? =
            handoff.start("derive the closed form of the sum")
        handoff.cancel()
        assertTrue(deferred?.isCancelled == true)
        assertFalse(handoff.running)
        handoff.cancel()
        assertFalse(handoff.running)
    }

    @Test
    fun `a completed warm call is handed over intact`() {
        val handoff = SpeculativeHandoff()
        val deferred = handoff.start("prove the square root of two is irrational")!!
        deferred.complete(answer())
        val take = handoff.take("prove the square root of two is irrational")
        assertTrue(take is SpeculativeHandoff.WarmTake.Reuse)
        assertEquals(
            "42",
            (take as SpeculativeHandoff.WarmTake.Reuse).deferred.getCompleted().getOrNull()?.answer,
        )
    }

    @Test
    fun `overlap ignores filler words`() {
        val score = SpeculativeHandoff.overlap(
            "can you please explain the trade-offs between a hash map and a tree",
            "explain trade-offs between hash map and tree",
        )
        assertTrue(score >= SpeculativeHandoff.MIN_OVERLAP)
    }

    @Test
    fun `unrelated texts do not overlap`() {
        assertTrue(
            SpeculativeHandoff.overlap(
                "design a database schema for invoices",
                "what is the capital of France",
            ) < SpeculativeHandoff.MIN_OVERLAP,
        )
    }
}
