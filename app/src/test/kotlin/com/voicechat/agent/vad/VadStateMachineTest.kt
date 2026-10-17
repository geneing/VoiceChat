package com.voicechat.agent.vad

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The playback-aware onset bar: while the assistant is audible, onset requires
 * [VadConfig.playbackOnsetRmsThreshold] so its residual audio cannot start a turn.
 */
class VadStateMachineTest {
    private val config =
        VadConfig(
            onsetRmsThreshold = 0.01f,
            playbackOnsetRmsThreshold = 0.05f,
            hangoverRmsThreshold = 0.005f,
        )

    @Test
    fun aLevelBelowThePlaybackBarDoesNotStartSpeechWhilePlaying() {
        // 0.02 clears the idle onset threshold (0.01) but not the playback bar (0.05).
        assertEquals(SpeechActivity.SPEECH_STARTED, firstActivity(playing = false, rms = 0.02f))
        assertTrue(
            "the assistant's residual audio must not start a turn",
            activities(playing = true, rms = 0.02f).isEmpty(),
        )
    }

    @Test
    fun aLevelAboveThePlaybackBarStillStartsSpeechWhilePlaying() {
        // The user's own voice is louder at the mic than the residual echo, so it
        // still barges in.
        assertEquals(SpeechActivity.SPEECH_STARTED, firstActivity(playing = true, rms = 0.08f))
    }

    private fun firstActivity(
        playing: Boolean,
        rms: Float,
    ): SpeechActivity? = activities(playing, rms).firstOrNull()

    private fun activities(
        playing: Boolean,
        rms: Float,
    ): List<SpeechActivity> {
        val machine = VadStateMachine(config, playbackActive = { playing })
        val frame = frame(rms)
        // Onset needs `onsetFrames` (3) consecutive qualifying frames.
        return (1..3).flatMap { machine.process(frame) }.map { it.activity }
    }

    private fun frame(rms: Float): AudioFrame =
        AudioFrame(
            format = AudioFormat.MONO_16_KHZ,
            // A constant frame has the requested RMS and no zero crossings.
            samples = ShortArray(320) { (rms * 32768f).toInt().toShort() },
            capturedAtNanos = 0L,
        )
}
