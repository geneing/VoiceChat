package com.voicechat.assistant.realtime.handoff

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandoffRouterTest {

    /** Typed users opt in to deterministic pre-reasoning; the defaults are off. */
    private val config = HandoffConfig(forceForTypedTurns = true)

    @Test
    fun `maths and derivations escalate`() {
        assertTrue(
            HandoffRouter.shouldEscalate(
                "Can you prove that the square root of 2 is irrational, step by step?",
                config,
            ),
        )
    }

    @Test
    fun `code questions escalate`() {
        assertTrue(
            HandoffRouter.shouldEscalate(
                "Why does this crash?\n```python\nprint(total / count)\n```",
                config,
            ),
        )
    }

    @Test
    fun `design and trade-off questions escalate`() {
        assertTrue(
            HandoffRouter.shouldEscalate(
                "Design a REST API for a todo app with auth, and explain the " +
                    "trade-offs between JWT and sessions.",
                config,
            ),
        )
    }

    @Test
    fun `attached documents escalate a short request`() {
        assertTrue(
            HandoffRouter.shouldEscalate(
                "Summarise the attached contract and list the risks.",
                config,
                documentAttached = true,
            ),
        )
    }

    @Test
    fun `small talk and current facts stay with the voice model`() {
        assertFalse(HandoffRouter.shouldEscalate("What's the weather like today?", config))
        assertFalse(HandoffRouter.shouldEscalate("hello there", config))
        assertFalse(HandoffRouter.shouldEscalate("Tell me a joke about cats", config))
        assertFalse(HandoffRouter.shouldEscalate("What time is it in Berlin right now?", config))
    }

    @Test
    fun `length alone is not enough`() {
        val long = "just chatting about the weekend plans and the family lunch ".repeat(5)
        assertTrue(long.length > 220)
        assertFalse(HandoffRouter.shouldEscalate(long, config))
    }

    @Test
    fun `the heuristic can be switched off`() {
        assertFalse(
            HandoffRouter.shouldEscalate(
                "Can you prove that the square root of 2 is irrational, step by step?",
                config.copy(forceForTypedTurns = false),
            ),
        )
    }

    @Test
    fun `no handoff config never escalates`() {
        assertFalse(
            HandoffRouter.shouldEscalate(
                "Can you prove that the square root of 2 is irrational, step by step?",
                null,
            ),
        )
    }

    @Test
    fun `a short hard question escalates and is pre-warmed`() {
        val short = "Can you prove that the square root of 2 is irrational, step by step?"
        assertTrue(HandoffRouter.shouldEscalate(short, config))
        assertTrue(HandoffRouter.shouldSpeculate(short, config))
    }

    @Test
    fun `a clearly hard voice transcript is pre-warmed`() {
        val hard = (
            "I need to work out how to derive the closed form for the recurrence we talked " +
                "about yesterday, then compute the expected number of comparisons for the " +
                "algorithm and show every step so I can follow the derivation at the board. "
            ).repeat(2)
        assertTrue(hard.length >= 400)
        assertTrue(HandoffRouter.shouldSpeculate(hard, config))
    }

    @Test
    fun `speculation is off when switched off or unconfigured`() {
        val hard = (
            "I need to work out how to derive the closed form for the recurrence we talked " +
                "about yesterday, then compute the expected number of comparisons for the " +
                "algorithm and show every step so I can follow the derivation at the board. "
            ).repeat(2)
        assertFalse(HandoffRouter.shouldSpeculate(hard, config.copy(speculativeVoice = false)))
        assertFalse(HandoffRouter.shouldSpeculate(hard, null))
        assertFalse(HandoffRouter.shouldSpeculate("what is the weather like", config))
    }
}
