package com.voicechat.agent.audio

/**
 * Kind of input route captured audio is arriving from.
 *
 * Only this *kind* is recorded in diagnostics. The platform's device product
 * name is deliberately not carried here: a paired headset name can contain
 * user-identifying text, and capture diagnostics must stay content-free (see
 * `docs/privacy-and-security.md`).
 */
enum class AudioRouteType {
    BUILTIN_MIC,
    WIRED_HEADSET,
    WIRED_HEADPHONES,
    BLUETOOTH_SCO,
    BLUETOOTH_A2DP,
    BLE_HEADSET,
    USB_DEVICE,
    USB_HEADSET,
    TELEPHONY,
    HDMI,
    UNKNOWN,
    ;

    /** True when audio is travelling over a Bluetooth link. */
    val isBluetooth: Boolean
        get() = this == BLUETOOTH_SCO || this == BLUETOOTH_A2DP || this == BLE_HEADSET
}

/** One privacy-safe description of the current input route. */
data class AudioRoute(
    val type: AudioRouteType,
) {
    /** Stable diagnostic label; never includes the device's user-visible name. */
    val label: String get() = type.name

    companion object {
        /** Used when the platform reports no input device or the query fails. */
        val UNKNOWN: AudioRoute = AudioRoute(AudioRouteType.UNKNOWN)
    }
}
