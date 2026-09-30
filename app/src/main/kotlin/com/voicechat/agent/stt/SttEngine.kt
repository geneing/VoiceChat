package com.voicechat.agent.stt

import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.ModelId
import java.util.Locale

/**
 * Recognition mode of the single supported on-device STT engine.
 *
 * ML Kit GenAI Speech Recognition exposes two modes on the same API
 * (`docs/decisions.md` §2.1):
 * - [ADVANCED] uses the on-device GenAI model; it is the preferred mode but is
 *   only available on Pixel 10 / Pixel 11 today.
 * - [BASIC] uses the traditional on-device recognizer and is generally
 *   available from API 31.
 *
 * Mode is configuration of one engine, not a second engine: there is no
 * fallback to a different provider.
 */
enum class SttMode {
    BASIC,
    ADVANCED,
}

/**
 * Configuration of the single on-device STT engine for one recognition session.
 *
 * The [engineId] is constant across modes so the app reports one engine; the
 * [modelId] distinguishes the Basic and Advanced modes that `checkStatus()`
 * gates independently. [locale] is passed to the recognizer and stamped onto
 * every [com.voicechat.agent.domain.Transcript] as its language tag.
 *
 * This type is deliberately platform-free: the ML Kit types live only in the
 * adapter (`MlKitSpeechToText`), never here.
 */
data class SttEngine(
    val mode: SttMode,
    val locale: Locale,
) {
    init {
        require(locale.toLanguageTag().isNotBlank()) { "locale must have a non-blank language tag" }
    }

    /** Stable identity of the engine; the same for every mode. */
    val engineId: EngineId = ENGINE_ID

    /** Identity of the mode-specific model, for availability and diagnostics. */
    val modelId: ModelId =
        when (mode) {
            SttMode.BASIC -> ModelId("mlkit-speech-basic")
            SttMode.ADVANCED -> ModelId("mlkit-speech-advanced")
        }

    /** Human-readable mode name for a settings screen. */
    val displayName: String =
        when (mode) {
            SttMode.BASIC -> "ML Kit Speech Recognition (Basic)"
            SttMode.ADVANCED -> "ML Kit Speech Recognition (Advanced)"
        }

    companion object {
        /** The one on-device STT engine this app ships. */
        val ENGINE_ID: EngineId = EngineId("mlkit-genai-speech-recognition")
    }
}

/**
 * The catalog of selectable STT configurations.
 *
 * There is exactly one engine with two modes. [catalog] lists the preferred
 * mode first so [preferred] can pick the best *available* mode without the
 * caller hard-coding a policy, and it never reports a mode as ready: the
 * caller must pass availability that came from the runtime check.
 */
object SttEngines {
    /** Default locale for the scaffold until settings expose a language choice. */
    val DEFAULT_LOCALE: Locale = Locale.US

    /** Selectable modes, preferred first. */
    fun catalog(locale: Locale = DEFAULT_LOCALE): List<SttEngine> =
        listOf(
            SttEngine(SttMode.ADVANCED, locale),
            SttEngine(SttMode.BASIC, locale),
        )

    /**
     * The first [SttAvailability.Ready] engine in [availabilities], or `null`
     * when none is ready. Order comes from the caller (use [catalog]), so
     * Advanced is preferred when it is actually available.
     */
    fun preferred(availabilities: List<SttAvailability>): SttEngine? = availabilities.firstOrNull { it is SttAvailability.Ready }?.engine

    /**
     * The first ready engine matching [mode], or `null`.
     *
     * When [mode] is set, **only** that mode is considered, so a persisted choice
     * that is unavailable is reported as unavailable rather than silently
     * substituted with the other mode (R-0181). `null` keeps the catalog order
     * ([ADVANCED][SttMode.ADVANCED] preferred) for a user who has not chosen.
     */
    fun select(
        availabilities: List<SttAvailability>,
        mode: SttMode?,
    ): SttEngine? =
        availabilities
            .firstOrNull { it is SttAvailability.Ready && (mode == null || it.engine.mode == mode) }
            ?.engine
}
