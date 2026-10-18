package com.voicechat.agent.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The app engine choice's own contract: a stable id per choice, the documented
 * default, and an unknown id resolving to "not a choice this build knows" so the
 * store can fall back rather than carry a value it cannot honour.
 */
class TtsEngineChoiceTest {
    @Test
    fun everyChoiceHasAStableDistinctId() {
        val ids = TtsEngineChoice.entries.map { it.id }

        assertEquals(ids.size, ids.toSet().size)
        assertEquals(
            listOf("kokoro-distilled", "platform"),
            ids,
        )
    }

    @Test
    fun theDefaultIsTheDistilledEngine() {
        // The product's voice is the distilled student and its graph ships in the
        // APK, so a fresh install speaks with it rather than with the platform.
        assertEquals(TtsEngineChoice.DISTILLED_KOKORO, TtsEngineChoice.DEFAULT)
    }

    @Test
    fun fromIdResolvesEachKnownChoiceAndRejectsAnythingElse() {
        TtsEngineChoice.entries.forEach { choice ->
            assertEquals(choice, TtsEngineChoice.fromId(choice.id))
        }

        assertNull(TtsEngineChoice.fromId("something-else"))
        assertNull(TtsEngineChoice.fromId(""))
        assertNull(TtsEngineChoice.fromId(null))
        // Case matters: the id is a persisted key, not user input.
        assertNull(TtsEngineChoice.fromId("PLATFORM"))
    }
}
