package com.voicechat.assistant.realtime.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory

/** Minimal audio-focus handling for voice sessions. */
class AudioFocus(private val context: Context) {

    private var request: AudioFocusRequest? = null

    fun acquire() {
        if (request != null) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener { change ->
                AppLog.debug(LogCategory.AUDIO, "Audio focus changed: $change")
            }
            .build()
        val result = manager.requestAudioFocus(focusRequest)
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            request = focusRequest
        } else {
            AppLog.warn(LogCategory.AUDIO, "Audio focus not granted ($result)")
        }
    }

    fun release() {
        val focusRequest = request ?: return
        val manager = context.getSystemService(AudioManager::class.java)
        manager?.abandonAudioFocusRequest(focusRequest)
        request = null
    }
}
