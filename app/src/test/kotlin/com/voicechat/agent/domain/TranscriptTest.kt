package com.voicechat.agent.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    @Test
    fun revisionMustNotBeNegative() {
        assertThrows(IllegalArgumentException::class.java) { TranscriptRevision(-1) }
    }

    @Test
    fun nextRevisionIsMonotonic() {
        assertEquals(TranscriptRevision(1), TranscriptRevision(0).next())
        assertEquals("3", TranscriptRevision(3).toString())
    }

    @Test
    fun interimAndFinalFactoriesDistinguishTheCommitState() {
        assertFalse(Transcript.interim("hello").isFinal)
        assertTrue(Transcript.final("hello").isFinal)
    }

    @Test
    fun confidenceMustBeAProbability() {
        assertThrows(IllegalArgumentException::class.java) {
            Transcript(text = "hello", revision = TranscriptRevision(0), isFinal = false, confidence = 1.5f)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Transcript(text = "hello", revision = TranscriptRevision(0), isFinal = false, confidence = -0.1f)
        }
    }

    @Test
    fun blankLanguageTagIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Transcript(text = "hello", revision = TranscriptRevision(0), isFinal = true, languageTag = " ")
        }
    }

    @Test
    fun interimRevisionCanBeSupersededByAHigherRevision() {
        val first = Transcript.interim("hel", TranscriptRevision(0))
        val second = Transcript.interim("hello", first.revision.next())
        val final = Transcript.final("hello world", second.revision.next())

        assertTrue(second.revision.value > first.revision.value)
        assertTrue(final.revision.value > second.revision.value)
        assertTrue(final.isFinal)
    }
}
