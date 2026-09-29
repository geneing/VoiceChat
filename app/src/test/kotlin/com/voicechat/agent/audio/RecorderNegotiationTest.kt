package com.voicechat.agent.audio

import android.media.AudioManager
import android.media.MediaRecorder
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import android.media.AudioFormat as AndroidAudioFormat

/** Format negotiation and mapping tests that run on the JVM with fake providers. */
class RecorderNegotiationTest {
    @Test
    fun anUnsupportedFormatFailsWithATypedError() {
        val thrown =
            assertThrows(VoiceAgentException::class.java) {
                negotiateBufferSizeBytes(16_000, AndroidAudioFormat.CHANNEL_IN_MONO, 320) { _, _, _ -> -2 }
            }

        assertEquals(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, thrown.error.code)
    }

    @Test
    fun theBufferIsAtLeastSeveralFramesWide() {
        val bytes = negotiateBufferSizeBytes(16_000, AndroidAudioFormat.CHANNEL_IN_MONO, 320) { _, _, _ -> 640 }

        assertEquals(320 * 2 * 4, bytes)
    }

    @Test
    fun aLargePlatformMinimumBufferIsHonored() {
        val bytes = negotiateBufferSizeBytes(16_000, AndroidAudioFormat.CHANNEL_IN_MONO, 320) { _, _, _ -> 8_192 }

        assertEquals(8_192, bytes)
    }

    @Test
    fun aNonMonoChannelCountFailsWithATypedError() {
        val thrown = assertThrows(VoiceAgentException::class.java) { inputChannelMask(2) }

        assertEquals(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, thrown.error.code)
    }

    @Test
    fun sourceAndFocusNamesArePrivacySafe() {
        assertEquals("VOICE_RECOGNITION", audioSourceName(MediaRecorder.AudioSource.VOICE_RECOGNITION))
        assertEquals("MIC", audioSourceName(MediaRecorder.AudioSource.MIC))
        assertEquals("SOURCE_1234", audioSourceName(1234))
    }

    @Test
    fun focusChangesMapToFocusStates() {
        assertEquals(AudioFocusState.ACQUIRED, AudioManager.AUDIOFOCUS_GAIN.toFocusState())
        assertEquals(AudioFocusState.LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT.toFocusState())
        assertEquals(AudioFocusState.LOST, AudioManager.AUDIOFOCUS_LOSS.toFocusState())
    }
}
