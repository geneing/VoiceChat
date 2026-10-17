package com.voicechat.agent.tts

/**
 * One installed text-to-speech **engine** (the speech API implementation),
 * described without any platform type.
 *
 * Mirrors the parts of `android.speech.tts.TextToSpeech.EngineInfo` the app
 * needs, so the settings engine list can be built and tested on the JVM. The
 * platform enumerates engines with `TextToSpeech.getEngines()`; the app never
 * assumes which engines a device has, it lists what the platform reports.
 *
 * [id] is the engine's package name (`TextToSpeech.EngineInfo.name`), which is
 * what `TextToSpeech(context, listener, engine)` takes. A `null` persisted
 * engine id means "let the platform choose its default engine", so this type
 * does not model a synthetic default entry: [isSystemDefault] flags which of the
 * reported engines the platform currently treats as the default.
 */
data class TtsEngineInfo(
    val id: String,
    val displayName: String,
    val isSystemDefault: Boolean = false,
) {
    init {
        require(id.isNotBlank()) { "engine id must not be blank" }
    }
}
