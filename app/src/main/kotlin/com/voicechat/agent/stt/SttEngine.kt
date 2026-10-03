package com.voicechat.agent.stt

import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.ModelId
import java.util.Locale

/**
 * The on-device STT engine a mode belongs to.
 *
 * Two engine *families* ship today. They are genuinely different processes with
 * different identities, so the family — not merely the mode — decides the
 * reported [EngineId]:
 * - [ML_KIT_GENAI] is ML Kit GenAI Speech Recognition (`docs/decisions.md`
 *   §2.1); its two modes share one engine id.
 * - [PARAKEET_EOU] is the Parakeet-EOU 120M INT8 ONNX streaming recognizer
 *   (`docs/parakeet-eou-streaming.md`); it has its own engine id and an
 *   app-managed model set.
 */
enum class SttEngineFamily {
    ML_KIT_GENAI,
    PARAKEET_EOU,
}

/**
 * Recognition mode of one on-device STT engine.
 *
 * ML Kit GenAI Speech Recognition exposes two modes on the same API
 * (`docs/decisions.md` §2.1):
 * - [ADVANCED] uses the on-device GenAI model; it is the preferred mode but is
 *   only available on Pixel 10 / Pixel 11 today.
 * - [BASIC] uses the traditional on-device recognizer and is generally
 *   available from API 31.
 *
 * [PARAKEET_EOU] is the single mode of the separate [SttEngineFamily.PARAKEET_EOU]
 * engine: an app-managed ONNX model set, not an ML Kit mode. Mode is
 * configuration of one engine family, not a fallback to a different provider.
 */
enum class SttMode {
    BASIC,
    ADVANCED,
    PARAKEET_EOU,
}

/**
 * Configuration of one on-device STT engine for one recognition session.
 *
 * The [engineId] is constant within an [SttEngineFamily] so the app reports one
 * engine identity per family; the [modelId] distinguishes modes/models that are
 * gated independently. [locale] is passed to the recognizer and stamped onto
 * every [com.voicechat.agent.domain.Transcript] as its language tag.
 *
 * This type is deliberately platform-free: the ML Kit and ONNX Runtime types
 * live only in their adapters (`MlKitSpeechToText`, `ParakeetEouSpeechToText`),
 * never here.
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
            SttMode.BASIC, SttMode.ADVANCED -> SttEngineFamily.ML_KIT_GENAI
            SttMode.PARAKEET_EOU -> SttEngineFamily.PARAKEET_EOU
        }

    /** Stable identity of the engine family; the same for every mode within it. */
    val engineId: EngineId =
        when (family) {
            SttEngineFamily.ML_KIT_GENAI -> MLKIT_ENGINE_ID
            SttEngineFamily.PARAKEET_EOU -> PARAKEET_EOU_ENGINE_ID
        }

    /** Identity of the mode-specific model, for availability and diagnostics. */
    val modelId: ModelId =
        when (mode) {
            SttMode.BASIC -> ModelId("mlkit-speech-basic")
            SttMode.ADVANCED -> ModelId("mlkit-speech-advanced")
            SttMode.PARAKEET_EOU -> ModelId("parakeet-eou-120m-onnx-int8")
        }

    /** Human-readable mode name for a settings screen. */
    val displayName: String =
        when (mode) {
            SttMode.BASIC -> "ML Kit Speech Recognition (Basic)"
            SttMode.ADVANCED -> "ML Kit Speech Recognition (Advanced)"
            SttMode.PARAKEET_EOU -> "Parakeet EOU (ONNX)"
        }

    /**
     * The name of the recognizer model a settings dropdown shows, without the
     * vendor engine prefix. The engine is named once by the section heading, so
     * the selectable entries are the models themselves.
     */
    val modelDisplayName: String =
        when (mode) {
            SttMode.BASIC -> BASIC_MODEL_DISPLAY_NAME
            SttMode.ADVANCED -> ADVANCED_MODEL_DISPLAY_NAME
            SttMode.PARAKEET_EOU -> PARAKEET_EOU_MODEL_DISPLAY_NAME
        }

    companion object {
        /** The ML Kit GenAI Speech Recognition engine id. */
        val MLKIT_ENGINE_ID: EngineId = EngineId("mlkit-genai-speech-recognition")

        /** The Parakeet-EOU ONNX streaming engine id. */
        val PARAKEET_EOU_ENGINE_ID: EngineId = EngineId("parakeet-eou-onnx")

        /** Backwards-compatible alias for the ML Kit engine id. */
        val ENGINE_ID: EngineId get() = MLKIT_ENGINE_ID

        /** The Basic-mode recognizer model, named without the vendor prefix. */
        const val BASIC_MODEL_DISPLAY_NAME: String = "Speech Recognizer (Basic)"

        /** The Advanced (GenAI) recognizer model, named without the vendor prefix. */
        const val ADVANCED_MODEL_DISPLAY_NAME: String = "Speech Recognizer (Advanced GenAI)"

        /** The Parakeet-EOU recognizer model, named without the engine prefix. */
        const val PARAKEET_EOU_MODEL_DISPLAY_NAME: String = "Parakeet-EOU 120M (INT8)"
    }
}

/**
 * The catalog of selectable STT configurations.
 *
 * [catalog] lists the preferred mode first so [preferred] can pick the best
 * *available* mode without the caller hard-coding a policy, and it never reports
 * a mode as ready: the caller must pass availability that came from the runtime
 * check. Parakeet-EOU is listed after the ML Kit modes so an available ML Kit
 * mode stays the default, while an explicit Parakeet choice is still honoured.
 */
object SttEngines {
    /** Default locale for the scaffold until settings expose a language choice. */
    val DEFAULT_LOCALE: Locale = Locale.US

    /** Selectable modes, preferred first. */
    fun catalog(locale: Locale = DEFAULT_LOCALE): List<SttEngine> =
        listOf(
            SttEngine(SttMode.ADVANCED, locale),
            SttEngine(SttMode.BASIC, locale),
            SttEngine(SttMode.PARAKEET_EOU, locale),
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
     * ([SttMode.ADVANCED] preferred) for a user who has not chosen.
     */
    fun select(
        availabilities: List<SttAvailability>,
        mode: SttMode?,
    ): SttEngine? =
        availabilities
            .firstOrNull { it is SttAvailability.Ready && (mode == null || it.engine.mode == mode) }
            ?.engine
}
