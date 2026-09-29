package com.voicechat.agent.replay

import com.voicechat.agent.domain.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The in-repo generator is the deterministic stand-in for harness TTS. */
class SyntheticSpeechGeneratorTest {
    private val format = AudioFormat.MONO_16_KHZ

    @Test
    fun generationIsSeedDeterministic() {
        val first = DeterministicSpeechGenerator(11L).synthesize("hello | again", format)
        val second = DeterministicSpeechGenerator(11L).synthesize("hello | again", format)
        val differentSeed = DeterministicSpeechGenerator(12L).synthesize("hello | again", format)

        assertArrayEquals(first.samples, second.samples)
        assertEquals(first.segments, second.segments)
        assertTrue(!first.samples.contentEquals(differentSeed.samples))
    }

    @Test
    fun thePauseMarkerProducesALabeledPauseSegment() {
        val speech = DeterministicSpeechGenerator(1L).synthesize("hi | yo", format)

        val pause = speech.segments.single { it.kind == SyntheticSegmentKind.PAUSE }
        assertEquals(400 * 16000 / 1000, pause.endSample - pause.startSample)
        assertTrue(speech.segments.any { it.kind == SyntheticSegmentKind.VOICED })
    }

    @Test
    fun voicedSegmentsCarryAudibleEnergy() {
        val speech = DeterministicSpeechGenerator(2L).synthesize("ab", format)

        assertTrue(speech.samples.any { kotlin.math.abs(it.toInt()) > 100 })
    }
}
