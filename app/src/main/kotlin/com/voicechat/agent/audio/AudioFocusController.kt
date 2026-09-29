package com.voicechat.agent.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle of the transient audio focus a capture session holds. */
enum class AudioFocusState {
    /** No focus is held (initial state, or after [AudioFocusController.abandon]). */
    RELEASED,
    ACQUIRED,
    LOSS_TRANSIENT,
    LOST,
}

/**
 * Small boundary around audio focus for one capture session.
 *
 * A capture session requests transient focus so an eventual TTS playback can
 * duck or pause instead of competing with the microphone. Losing focus is
 * recorded in diagnostics; capture is not stopped by it, because a recorder can
 * still be delivering usable audio and the barge-in policy belongs to M24.
 */
interface AudioFocusController {
    /** Requests transient focus; returns true when it was granted. */
    fun acquire(): Boolean

    /** Abandons focus and returns to [AudioFocusState.RELEASED]. Idempotent. */
    fun abandon()

    /** Observable focus state, for diagnostics and tests. */
    fun state(): StateFlow<AudioFocusState>
}

/** [AudioFocusController] backed by platform `AudioManager` focus. */
class AndroidAudioFocusController(
    context: Context,
) : AudioFocusController {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)
    private val state = MutableStateFlow(AudioFocusState.RELEASED)
    private val request: AudioFocusRequest? =
        audioManager?.let {
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setOnAudioFocusChangeListener { change ->
                    AppLog.d { "capture: audio focus change=${change.toFocusState()}" }
                    state.value = change.toFocusState()
                }.build()
        }

    override fun acquire(): Boolean {
        val manager = audioManager ?: return false
        val focusRequest = request ?: return false
        val granted = manager.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        state.value = if (granted) AudioFocusState.ACQUIRED else AudioFocusState.RELEASED
        AppLog.d { "capture: audio focus acquire granted=$granted" }
        return granted
    }

    override fun abandon() {
        val manager = audioManager ?: return
        val focusRequest = request ?: return
        runCatching { manager.abandonAudioFocusRequest(focusRequest) }
        state.value = AudioFocusState.RELEASED
        AppLog.d { "capture: audio focus abandoned" }
    }

    override fun state(): StateFlow<AudioFocusState> = state.asStateFlow()
}

/** Maps a platform focus-change code to the app's focus state. */
internal fun Int.toFocusState(): AudioFocusState =
    when (this) {
        AudioManager.AUDIOFOCUS_GAIN -> AudioFocusState.ACQUIRED
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> AudioFocusState.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> AudioFocusState.LOSS_TRANSIENT
        AudioManager.AUDIOFOCUS_LOSS -> AudioFocusState.LOST
        else -> AudioFocusState.RELEASED
    }
