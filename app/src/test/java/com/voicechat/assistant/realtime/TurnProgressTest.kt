package com.voicechat.assistant.realtime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnProgressTest {

    @Test
    fun `the stalled turns from the device log are promises`() {
        // Verbatim from the reproduced stall: the model ended the response here.
        assertTrue(
            TurnProgress.promisesAction(
                "I'll take care of it and check out the latest on local Llama Reddit.",
            ),
        )
        assertTrue(
            TurnProgress.promisesAction(
                "I'll handle this and look into the latest on machine learning Reddit.",
            ),
        )
        assertTrue(TurnProgress.promisesAction("I can look for recent AI research papers."))
        assertTrue(
            TurnProgress.promisesAction(
                "I'll pull the latest detailed info on Meta's Ember model.",
            ),
        )
        assertTrue(TurnProgress.promisesAction("Let me pull the details on Meta's new model."))
    }

    @Test
    fun `a finished thought that ends on a lookup is still a promise`() {
        // 2026-11-12 10:54:56: answered, then ended on "I need to check".
        assertTrue(
            TurnProgress.promisesAction(
                "It sounds plausible because it's a common critique of newer Lewis and Clark " +
                    "histories, but I need to check actual reviews for This Vast Enterprise.",
            ),
        )
        assertTrue(
            TurnProgress.promisesAction(
                "The first chapter covers the expedition. I'll check the reviews and come back.",
            ),
        )
    }

    @Test
    fun `advice to the user is not a promise`() {
        assertFalse(TurnProgress.promisesAction("You'll need to check the release notes."))
        assertFalse(TurnProgress.promisesAction("Readers should search for the paper themselves."))
    }

    @Test
    fun `real answers are not promises`() {
        assertFalse(
            TurnProgress.promisesAction(
                "The biggest buzz is around OpenAI hitting ten billion dollars in quarterly " +
                    "revenue and TSMC investing up to a billion in Anthropic.",
            ),
        )
        assertFalse(TurnProgress.promisesAction("I'm still here."))
        assertFalse(TurnProgress.promisesAction("Take care."))
    }

    @Test
    fun `a clarification question is not a promise`() {
        assertFalse(
            TurnProgress.promisesAction(
                "I can help you find new papers. Would you like me to search for recent ones?",
            ),
        )
    }

    @Test
    fun `a promise without an action verb is ignored`() {
        assertFalse(TurnProgress.promisesAction("I'll keep the pace up and stay concise."))
        assertFalse(TurnProgress.promisesAction("I'll be here if you need anything else."))
    }

    @Test
    fun `long or empty text never counts`() {
        assertFalse(TurnProgress.promisesAction(""))
        val long = "I'll check that. " + "Detail. ".repeat(40)
        assertFalse(TurnProgress.promisesAction(long))
    }
}
