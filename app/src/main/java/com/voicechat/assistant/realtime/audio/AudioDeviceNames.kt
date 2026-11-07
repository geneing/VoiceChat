package com.voicechat.assistant.realtime.audio

import android.media.AudioDeviceInfo

/** Short device-type label for the log; keeps the raw type number as a fallback. */
internal fun AudioDeviceInfo.typeName(): String = when (type) {
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth sco"
    AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble headset"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "usb headset"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "usb device"
    AudioDeviceInfo.TYPE_HEARING_AID -> "hearing aid"
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired headset"
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> "builtin mic"
    else -> "type $type"
}
