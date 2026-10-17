package com.voicechat.agent.voice

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Test

/** The rolling pre-roll that keeps a turn's opening audio (see [CapturePreRoll]). */
class CapturePreRollTest {
    @Test
    fun itKeepsTheMostRecentFramesUpToItsCapacity() {
        val preRoll = CapturePreRoll(capacity = 3)

        (1..5).forEach { preRoll.record(frame(it.toLong())) }

        assertEquals(3, preRoll.size)
        assertEquals(listOf(3L, 4L, 5L), preRoll.snapshot().map { it.capturedAtNanos })
    }

    @Test
    fun clearDropsEverythingSoTheNextTurnDoesNotInheritATail() {
        val preRoll = CapturePreRoll(capacity = 4)
        (1..4).forEach { preRoll.record(frame(it.toLong())) }

        preRoll.clear()

        assertEquals(0, preRoll.size)
        assertEquals(emptyList<AudioFrame>(), preRoll.snapshot())
    }

    @Test(expected = IllegalArgumentException::class)
    fun aNonPositiveCapacityIsRefused() {
        CapturePreRoll(capacity = 0)
    }

    private fun frame(index: Long): AudioFrame =
        AudioFrame(format = AudioFormat.MONO_16_KHZ, samples = ShortArray(4), capturedAtNanos = index)
}
