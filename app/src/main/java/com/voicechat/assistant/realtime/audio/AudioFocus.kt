package com.voicechat.assistant.realtime.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory

/** Minimal audio-focus and communication-route handling for voice sessions. */
class AudioFocus(private val context: Context) {

    private var request: AudioFocusRequest? = null

    fun acquire() {
        if (request != null) return
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
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

    /**
     * Put the platform in communication mode so the speaker output is used as
     * the AEC reference and the microphone gets the hardware echo canceller.
     */
    fun enterVoiceMode() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        val externalOutput = hasExternalAudioOutput(manager)
        runCatching {
            manager.mode = AudioManager.MODE_IN_COMMUNICATION
            // Bluetooth/USB headsets keep their route; the loudspeaker is only
            // forced when nothing else is connected.
            manager.isSpeakerphoneOn = !externalOutput
        }.onFailure {
            AppLog.warn(LogCategory.AUDIO, "Could not enter voice audio mode", it.message)
        }
        AppLog.info(LogCategory.AUDIO, "Voice audio mode on", "externalOutput=$externalOutput")
    }

    fun exitVoiceMode() {
        val manager = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (manager.mode == AudioManager.MODE_IN_COMMUNICATION) {
                manager.mode = AudioManager.MODE_NORMAL
            }
            manager.isSpeakerphoneOn = false
        }
        AppLog.info(LogCategory.AUDIO, "Voice audio mode off")
    }

    fun release() {
        val focusRequest = request
        val manager = context.getSystemService(AudioManager::class.java)
        if (focusRequest != null) {
            manager?.abandonAudioFocusRequest(focusRequest)
            request = null
        }
        exitVoiceMode()
    }

    private fun hasExternalAudioOutput(manager: AudioManager): Boolean = runCatching {
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLE_SPEAKER,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_HEARING_AID,
                -> true

                else -> false
            }
        }
    }.getOrDefault(false)
}
