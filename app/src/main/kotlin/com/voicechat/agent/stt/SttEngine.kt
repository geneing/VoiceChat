package com.voicechat.agent.stt

import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.ModelId
import java.util.Locale

/**
 * The on-device STT engine a mode belongs to.
 *
 * Three engine *families* ship today. They are genuinely different processes
 * with different identities, so the family — not merely the mode — decides the
 * reported [EngineId]:
 * - [ANDROID_SPEECH] is the platform `android.speech.SpeechRecognizer` API,
 *   used through `createOnDeviceSpeechRecognizer` when the device offers an
 *   on-device recognizer (`docs/android-speech-recognition.md`). It is the
 *   default engine.
 * - [ML_KIT_GENAI] is ML Kit GenAI Speech Recognition (`docs/decisions.md`
 *   §2.1); its two modes share one engine id.
 */
enum class SttEngineFamily {
    ANDROID_SPEECH,
    ML_KIT_GENAI,
}

/**
 * Recognition mode of one on-device STT engine.
 *
 * [ANDROID_SPEECH] is the single mode of the platform `SpeechRecognizer` family:
 * recognition is requested on-device (`createOnDeviceSpeechRecognizer`, or
 * `EXTRA_PREFER_OFFLINE` when only the generic recognizer is available), and no
 * network recognizer is ever selected on the user's behalf.
 *
 * ML Kit GenAI Speech Recognition exposes two modes on the same API
 * (`docs/decisions.md` §2.1):
 * - [ADVANCED] uses the on-device GenAI model; it is the preferred ML Kit mode
 *   but is only available on Pixel 10 / Pixel 11 today.
 * - [BASIC] uses the traditional on-device recognizer and is generally
 *   available from API 31.
 *
 * Mode is configuration of one engine family, not a fallback to a different
 * provider.
 */
enum class SttMode {
    ANDROID_SPEECH,
    BASIC,
    ADVANCED,
}

/**
 * Configuration of one on-device STT engine for one recognition session.
 *
 * The [engineId] is constant within an [SttEngineFamily] so the app reports one
 * engine identity per family; the [modelId] distinguishes modes/models that are
 * gated independently. [locale] is passed to the recognizer and stamped onto
 * every [com.voicechat.agent.domain.Transcript] as its language tag.
 *
 * This type is deliberately platform-free: the platform `SpeechRecognizer` and
 * ML Kit types live only in their adapters
 * (`AndroidSpeechRecognizerSpeechToText`, `MlKitSpeechToText`), never here.
 */
data class SttEngine(
    val mode: SttMode,
    val locale: Locale,
) {
    init {
        require(locale.toLanguageTag().isNotBlank()) { "locale must have a non-blank language tag" }
    }

    /** The engine family [mode] belongs to. */
    val family: SttEngineFamily =
        when (mode) {
            SttMode.ANDROID_SPEECH -> SttEngineFamily.ANDROID_SPEECH
            SttMode.BASIC, SttMode.ADVANCED -> SttEngineFamily.ML_KIT_GENAI
        }

    /** Stable identity of the engine family; the same for every mode within it. */
    val engineId: EngineId =
        when (family) {
            SttEngineFamily.ANDROID_SPEECH -> ANDROID_SPEECH_ENGINE_ID
            SttEngineFamily.ML_KIT_GENAI -> MLKIT_ENGINE_ID
        }

    /** Identity of the mode-specific model, for availability and diagnostics. */
    val modelId: ModelId =
        when (mode) {
            SttMode.ANDROID_SPEECH -> ModelId("android-speech-on-device")
            SttMode.BASIC -> ModelId("mlkit-speech-basic")
            SttMode.ADVANCED -> ModelId("mlkit-speech-advanced")
        }

    /** Human-readable mode name for a settings screen. */
    val displayName: String =
        when (mode) {
            SttMode.ANDROID_SPEECH -> "Android Speech Recognizer"
            SttMode.BASIC -> "ML Kit Speech Recognition (Basic)"
            SttMode.ADVANCED -> "ML Kit Speech Recognition (Advanced)"
        }

    /**
     * The name of the recognizer model a settings dropdown shows, without the
     * vendor engine prefix. The engine is named once by the section heading, so
     * the selectable entries are the models themselves.
     */
    val modelDisplayName: String =
        when (mode) {
            SttMode.ANDROID_SPEECH -> ANDROID_SPEECH_MODEL_DISPLAY_NAME
            SttMode.BASIC -> BASIC_MODEL_DISPLAY_NAME
            SttMode.ADVANCED -> ADVANCED_MODEL_DISPLAY_NAME
        }

    companion object {
        /** The platform `SpeechRecognizer` engine id. */
        val ANDROID_SPEECH_ENGINE_ID: EngineId = EngineId("android-speech-recognizer")

        /** The ML Kit GenAI Speech Recognition engine id. */
        val MLKIT_ENGINE_ID: EngineId = EngineId("mlkit-genai-speech-recognition")

        /** Backwards-compatible alias for the ML Kit engine id. */
        val ENGINE_ID: EngineId get() = MLKIT_ENGINE_ID

        /** The platform recognizer model, named without the vendor prefix. */
        const val ANDROID_SPEECH_MODEL_DISPLAY_NAME: String = "Speech Recognizer (on-device)"

        /** The Basic-mode recognizer model, named without the vendor prefix. */
        const val BASIC_MODEL_DISPLAY_NAME: String = "Speech Recognizer (Basic)"

        /** The Advanced (GenAI) recognizer model, named without the vendor prefix. */
        const val ADVANCED_MODEL_DISPLAY_NAME: String = "Speech Recognizer (Advanced GenAI)"
    }
}

/**
 * The catalog of selectable STT configurations.
 *
 * [catalog] lists the preferred mode first so [preferred] can pick the best
 * *available* mode without the caller hard-coding a policy, and it never reports
 * a mode as ready: the caller must pass availability that came from the runtime
 * check. The platform `SpeechRecognizer` is listed first so it is the default
 * whenever the device reports an on-device recognizer; the ML Kit modes follow
 * as the fallback and an explicit ML Kit choice is still honoured.
 */
object SttEngines {
    /** Default locale for the scaffold until settings expose a language choice. */
    val DEFAULT_LOCALE: Locale = Locale.US

    /** Selectable modes, preferred first: the platform recognizer is the default. */
    fun catalog(locale: Locale = DEFAULT_LOCALE): List<SttEngine> = listOf(SttEngine(SttMode.ANDROID_SPEECH, locale)) + mlKitCatalog(locale)

    /**
     * The ML Kit GenAI modes only, preferred first.
     *
     * `MlKitSttStatus.check` may only be called for these: the platform
     * recognizer is a separate [SttEngineFamily.ANDROID_SPEECH] whose
     * availability comes from `SpeechRecognizer`, not the ML Kit feature status.
     */
    fun mlKitCatalog(locale: Locale = DEFAULT_LOCALE): List<SttEngine> =
        listOf(
            SttEngine(SttMode.ADVANCED, locale),
            SttEngine(SttMode.BASIC, locale),
        )

    /**
     * The first [SttAvailability.Ready] engine in [availabilities], or `null`
     * when none is ready. Order comes from the caller (use [catalog]), so the
     * platform recognizer is preferred when it is actually available, then
     * Advanced.
     */
    fun preferred(availabilities: List<SttAvailability>): SttEngine? = availabilities.firstOrNull { it is SttAvailability.Ready }?.engine

    /**
     * The first ready engine matching [mode], or `null`.
     *
     * When [mode] is set, **only** that mode is considered, so a persisted choice
     * that is unavailable is reported as unavailable rather than silently
     * substituted with the other mode (R-0181). `null` keeps the catalog order
     * ([SttMode.ANDROID_SPEECH] preferred) for a user who has not chosen.
     */
    fun select(
        availabilities: List<SttAvailability>,
        mode: SttMode?,
    ): SttEngine? =
        availabilities
            .firstOrNull { it is SttAvailability.Ready && (mode == null || it.engine.mode == mode) }
            ?.engine
}
