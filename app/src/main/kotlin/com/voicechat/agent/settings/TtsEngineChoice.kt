package com.voicechat.agent.settings

/**
 * Which **app** engine speaks an assistant reply (M28).
 *
 * This is deliberately separate from [VoiceSettings.ttsEngineId], which selects a
 * platform *speech API* and only means anything for [PLATFORM]. Here the choice is
 * between the two engines the app itself can drive:
 *
 * - [DISTILLED_KOKORO] — the pinned distilled Kokoro student, run through ONNX
 *   Runtime on-device. It is the app's default because it is the voice the
 *   product was built around (`docs/decisions.md` §2.4), and its graph ships in
 *   the APK, so a fresh install speaks with no download.
 * - [PLATFORM] — the retained `android.speech.tts` engine
 *   (`docs/decisions.md` §2.2). It is offered as a visible alternative, not as a
 *   silent fallback: it starts audio sooner (26 ms vs 650 ms p50 on the Pixel 10)
 *   at the cost of a much less natural voice.
 *
 * A choice is only kept when the capability snapshot reports that engine
 * available, so the app never claims to use one it cannot (`SettingsValidator`).
 */
enum class TtsEngineChoice(
    /** Stable id, and the value persisted in settings. */
    val id: String,
    /** Human-readable name for the settings picker. */
    val label: String,
) {
    DISTILLED_KOKORO(id = "kokoro-distilled", label = "Kokoro (on-device, distilled)"),
    PLATFORM(id = "platform", label = "System speech engine"),

    ;

    companion object {
        /**
         * The engine the app uses when nothing else is stored.
         *
         * The distilled voice is the product's voice, so a fresh install speaks
         * with it rather than falling back to whatever the platform offers.
         */
        val DEFAULT: TtsEngineChoice = DISTILLED_KOKORO

        /** The choice with [id], or `null` when the id is not one this build knows. */
        fun fromId(id: String?): TtsEngineChoice? = entries.firstOrNull { it.id == id }
    }
}

/**
 * One engine's availability in the settings picker.
 *
 * [available] is read from the device and the installed model, never assumed;
 * [reason] explains an unavailable engine so the picker can say why instead of
 * offering a choice that would fail.
 */
data class TtsEngineOption(
    val choice: TtsEngineChoice,
    val available: Boolean,
    val reason: String? = null,
)
