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
    /** The platform's naturalness rank (`Voice.getQuality()`); higher is more natural. */
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

    /**
     * The persisted speech-engine selection ([engineId]) is not an engine the
     * platform reports, so it cannot be used. Reported explicitly rather than
     * letting the platform quietly fall back to its default engine — the app
     * never silently substitutes a different speech API than the one chosen.
     */
    data class SelectedEngineUnavailable(
        val engineId: String,
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
 * a name or locale. [mostNatural] (and its alias [select]) prefers an exact
 * locale match, then the higher quality — the platform's naturalness rank — then
 * the lower latency, and returns `null` when no embedded voice matches, so the
 * caller must surface the explicit "no on-device voice" state.
 */
object OnDeviceVoiceSelector {
    /**
     * The voice locales the app offers, matching the languages the on-device STT
     * engine can recognize. A voice outside this set is never listed: the app
     * only speaks a language it can also hear.
     */
    val SUPPORTED_LANGUAGE_TAGS: Set<String> =
        linkedSetOf(
            "en-US",
            "en-GB",
            "en-AU",
            "es-US",
            "es-ES",
        )

    /** True when [locale] is one of the locales the app offers a voice for. */
    fun isSupportedLocale(locale: Locale): Boolean = normalize(locale) in SUPPORTED_LANGUAGE_TAGS

    /**
     * The embedded voices the app offers: on-device (`!requiresNetwork`) **and**
     * in a supported locale. This is the list the settings dropdown renders.
     */
    fun supportedVoices(voices: List<TtsVoice>): List<TtsVoice> = onDeviceVoices(voices).filter { isSupportedLocale(it.locale) }

    /** Installed voices that do not require a network connection. */
    fun onDeviceVoices(voices: List<TtsVoice>): List<TtsVoice> = voices.filter { it.isOnDevice }

    /**
     * The most natural embedded voice for [locale], or `null` when none is
     * installed.
     *
     * **Naturalness is the platform's own quality rank.** A TTS engine reports
     * `Voice.getQuality()` on a `QUALITY_VERY_LOW`..`QUALITY_VERY_HIGH` scale,
     * which is the only naturalness signal `android.speech.tts` exposes; the app
     * prefers the highest quality rather than picking by name or by the order the
     * platform happens to return. Lower latency breaks a quality tie, because a
     * more responsive voice is preferable when two voices sound equally natural.
     *
     * An exact locale match still ranks above quality (a higher-quality en-GB
     * voice is never chosen for an en-US request), and a network-required voice
     * is never a candidate. This is the app's default when the user has not
     * picked a voice ([select]); it never substitutes a network voice.
     */
    fun mostNatural(
        voices: List<TtsVoice>,
        locale: Locale,
    ): TtsVoice? =
        supportedVoices(voices)
            .filter { it.locale.language == locale.language }
            .sortedWith(naturalnessOrder(locale))
            .firstOrNull()

    /**
     * The embedded voice the app uses for [locale] when no voice is chosen. An
     * alias of [mostNatural], kept as the seam the engine and tests already use.
     */
    fun select(
        voices: List<TtsVoice>,
        locale: Locale,
    ): TtsVoice? = mostNatural(voices, locale)

    /** Exact locale first, then the most natural (highest quality), then fastest. */
    private fun naturalnessOrder(locale: Locale): Comparator<TtsVoice> =
        compareByDescending<TtsVoice> { it.locale == locale }
            .thenByDescending { it.quality }
            .thenBy { it.latency }

    /**
     * The installed embedded voice with exactly [voiceId], or `null` when it is
     * not installed, requires a network connection, or is outside the supported
     * locales. A selected voice is never silently substituted (R-0181).
     */
    fun selectPreferred(
        voices: List<TtsVoice>,
        voiceId: String,
    ): TtsVoice? = supportedVoices(voices).firstOrNull { it.id == voiceId }

    /** The BCP-47 tag for [locale], normalizing a region-less tag to a supported one. */
    private fun normalize(locale: Locale): String {
        val tag = locale.toLanguageTag()
        if (tag in SUPPORTED_LANGUAGE_TAGS) return tag
        // A voice reported as `en`/`es` without a region still maps to a supported
        // language; prefer the US variant, matching the STT default.
        val fallback = "$tag-US"
        return if (fallback in SUPPORTED_LANGUAGE_TAGS) fallback else tag
    }
}
