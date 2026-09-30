package com.voicechat.agent.tts

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Reads the current output route kind for TTS playback diagnostics.
 *
 * Only the route *kind* is returned; the platform's user-visible device name is
 * never carried. This stays in the Android layer because `AudioDeviceInfo` is a
 * platform type; the rest of the tts package is platform-free.
 */
internal object AndroidTtsOutputRoute {
    /** The first known output route kind, or [TtsOutputRoute.UNKNOWN]. */
    fun current(context: Context): TtsOutputRoute {
        val manager = context.getSystemService(AudioManager::class.java) ?: return TtsOutputRoute.UNKNOWN
        val devices =
            runCatching { manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS) }
                .getOrDefault(emptyArray())
        return devices
            .map { it.toTtsOutputRoute() }
            .firstOrNull { it != TtsOutputRoute.UNKNOWN }
            ?: TtsOutputRoute.UNKNOWN
    }
}

/** Maps a platform audio device type to the privacy-safe [TtsOutputRoute]. */
internal fun AudioDeviceInfo.toTtsOutputRoute(): TtsOutputRoute =
    when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> TtsOutputRoute.BUILTIN_SPEAKER
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> TtsOutputRoute.BUILTIN_EARPIECE
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> TtsOutputRoute.WIRED_HEADSET
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> TtsOutputRoute.WIRED_HEADPHONES
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> TtsOutputRoute.BLUETOOTH_SCO
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> TtsOutputRoute.BLUETOOTH_A2DP
        AudioDeviceInfo.TYPE_BLE_HEADSET -> TtsOutputRoute.BLE_HEADSET
        AudioDeviceInfo.TYPE_USB_DEVICE -> TtsOutputRoute.USB_DEVICE
        AudioDeviceInfo.TYPE_USB_HEADSET -> TtsOutputRoute.USB_HEADSET
        AudioDeviceInfo.TYPE_HDMI -> TtsOutputRoute.HDMI
        AudioDeviceInfo.TYPE_TELEPHONY -> TtsOutputRoute.TELEPHONY
        else -> TtsOutputRoute.UNKNOWN
    }
