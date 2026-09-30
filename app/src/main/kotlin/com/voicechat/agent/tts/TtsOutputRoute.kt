package com.voicechat.agent.tts

/**
 * Privacy-safe kind of output route TTS audio is currently playing through.
 *
 * Only this *kind* is recorded in diagnostics. The platform's output device
 * product name is deliberately not carried here: a paired headset name can
 * contain user-identifying text, and diagnostics must stay content-free
 * (`docs/privacy-and-security.md`). The enum lives here rather than reusing the
 * M07 capture `AudioRouteType`, which is input-oriented.
 */
enum class TtsOutputRoute {
    BUILTIN_SPEAKER,
    BUILTIN_EARPIECE,
    WIRED_HEADSET,
    WIRED_HEADPHONES,
    BLUETOOTH_SCO,
    BLUETOOTH_A2DP,
    BLE_HEADSET,
    USB_DEVICE,
    USB_HEADSET,
    HDMI,
    TELEPHONY,
    UNKNOWN,
    ;

    /** Stable diagnostic label; never includes the device's user-visible name. */
    val label: String get() = name
}
