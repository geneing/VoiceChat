package com.voicechat.agent.voice

import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.stt.SttMode
import java.util.Locale

/** The validated on-device STT runtime the voice session should use. */
data class VoiceRuntimeSelection(
    /** The selected STT mode, or `null` to prefer the first available mode. */
    val sttMode: SttMode? = null,
    /** A resolved STT choice, or `null` to resolve availability at session start. */
    val stt: VoiceSttSelection? = null,
    /** STT locale; defaults to the engine's default locale. */
    val locale: Locale = SttEngines.DEFAULT_LOCALE,
) {
    init {
        require(locale.toLanguageTag().isNotBlank()) { "locale must have a non-blank language tag" }
    }

    /** True when the resolved selection actually names a ready engine. */
    val hasReadyStt: Boolean get() = stt is VoiceSttSelection.Ready

    companion object {
        /** The default: no mode preference and the engine default locale. */
        fun default(): VoiceRuntimeSelection = VoiceRuntimeSelection()
    }
}
