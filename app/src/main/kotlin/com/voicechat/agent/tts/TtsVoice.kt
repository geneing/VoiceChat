package com.voicechat.agent.tts

import com.voicechat.agent.domain.VoiceAgentError
import java.util.Locale

/**
 * One installed text-to-speech voice, described without any platform type.
 *
 * Mirrors the parts of `android.speech.tts.Voice` the app needs so voice
 * discovery and the on-device-only policy can be unit tested on the JVM.
 * [requiresNetwork] is the security-relevant field: `docs/decisions.md` §2.2
 * requires the app to use only voices the platform reports as embedded
 * (non-network); a network-required voice must never be selected silently.
 */
data class TtsVoice(
    val id: String,
    val displayName: String,
    val locale: Locale,
    val requiresNetwork: Boolean,
    val quality: Int = UNKNOWN_RANK,
    val latency: Int = UNKNOWN_RANK,
) {
    init {
        require(id.isNotBlank()) { "voice id must not be blank" }
    }

    /** True when the voice synthesizes on-device with no network connection. */
    val isOnDevice: Boolean get() = !requiresNetwork

    companion object {
        /** Platform "unknown" value for [quality]/[latency] (`Voice` reports `-1`). */
        const val UNKNOWN_RANK: Int = -1
    }
}

/**
 * Availability of on-device text-to-speech on this device.
 *
 * [NoOnDeviceVoice] is a first-class state, not an error fallback: when the
 * requested locale has only network-required voices (or none), the app reports
 * that explicitly and continues text-only rather than using a network voice
 * (`docs/decisions.md` §2.2).
 */
sealed interface TtsEngineAvailability {
    /** An embedded voice is installed and was selected. */
    data class Ready(
        val voice: TtsVoice,
    ) : TtsEngineAvailability

    /** No embedded voice for [locale]; text-only until one is installed. */
    data class NoOnDeviceVoice(
        val locale: Locale,
    ) : TtsEngineAvailability

    /**
     * The persisted voice selection ([voiceId]) is not an installed embedded
     * voice, so it cannot be used. Reported explicitly rather than silently
     * falling back to a different voice (R-0181, CODE_REVIEW P2).
     */
    data class SelectedVoiceUnavailable(
        val voiceId: String,
        val locale: Locale,
    ) : TtsEngineAvailability

    /** The engine could not be initialized; [error] is safe to show. */
    data class Unavailable(
        val error: VoiceAgentError,
    ) : TtsEngineAvailability
}

/**
 * Pure policy for choosing an embedded voice.
 *
 * "On-device" is decided only by [TtsVoice.requiresNetwork], never inferred from
 * a name or locale. [select] prefers an exact locale match, then the higher
 * quality, then the lower latency, and returns `null` when no embedded voice
 * matches, so the caller must surface the explicit "no on-device voice" state.
 */
object OnDeviceVoiceSelector {
    /** Installed voices that do not require a network connection. */
    fun onDeviceVoices(voices: List<TtsVoice>): List<TtsVoice> = voices.filter { it.isOnDevice }

    /** The best embedded voice for [locale], or `null` when none is installed. */
    fun select(
        voices: List<TtsVoice>,
        locale: Locale,
    ): TtsVoice? =
        onDeviceVoices(voices)
            .filter { it.locale.language == locale.language }
            .sortedWith(
                compareByDescending<TtsVoice> { it.locale == locale }
                    .thenByDescending { it.quality }
                    .thenBy { it.latency },
            ).firstOrNull()

    /**
     * The installed embedded voice with exactly [voiceId], or `null` when it is
     * not installed or requires a network connection. A selected voice is never
     * silently substituted (R-0181).
     */
    fun selectPreferred(
        voices: List<TtsVoice>,
        voiceId: String,
    ): TtsVoice? = onDeviceVoices(voices).firstOrNull { it.id == voiceId }
}
