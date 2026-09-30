package com.voicechat.agent.voice

import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.stt.SttMode
import java.util.Locale

/**
 * The validated on-device runtime the voice loop should use for one session
 * (M26, R-0181).
 *
 * M24's assembly picked the first runtime-ready STT engine for the locale and
 * constructed TTS from the locale alone, so a persisted STT mode or voice choice
 * was not authoritative. This value type is the resolution seam: the app
 * boundary maps the persisted `VoiceSettings` into it once per session, and the
 * platform session honors it or reports the selected option unavailable — it
 * never silently substitutes another mode or voice.
 *
 * [ttsVoiceId] is carried for wiring; a device with no embedded voice still
 * surfaces the typed `TTS_NO_ON_DEVICE_VOICE`/initialization state (R-0180).
 */
data class VoiceRuntimeSelection(
    /** The selected STT mode, or `null` to prefer the first available mode. */
    val sttMode: SttMode? = null,
    /** STT/TTS locale; defaults to the single-engine default. */
    val locale: Locale = SttEngines.DEFAULT_LOCALE,
    /** The selected embedded TTS voice id, or `null` for the locale default. */
    val ttsVoiceId: String? = null,
) {
    init {
        require(locale.toLanguageTag().isNotBlank()) { "locale must have a non-blank language tag" }
    }

    companion object {
        /** The honest default: no mode/voice preference, the engine default locale. */
        fun default(): VoiceRuntimeSelection = VoiceRuntimeSelection()
    }
}
