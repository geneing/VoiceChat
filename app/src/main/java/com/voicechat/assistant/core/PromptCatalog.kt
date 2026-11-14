package com.voicechat.assistant.core

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.IOException

/** One entry of the prompt preset picker; [prompt] is text applied to the settings. */
data class PromptPreset(val label: String, val prompt: String)

@Serializable
private data class PromptPresetSpec(val label: String, val prompt: String = "")

/**
 * `assets/prompts.json`. A preset without `prompt` applies [PromptFile.default].
 */
@Serializable
private data class PromptFile(
    val version: Int = 0,
    val default: String = "",
    val presets: List<PromptPresetSpec> = emptyList(),
    val knownDefaults: List<String> = emptyList(),
)

/**
 * The system prompt and the preset picker text, shipped as
 * `assets/prompts.json` instead of strings compiled into the app, so the
 * wording is edited in one data file. [attach] reads it once in
 * `VoiceChatApp.onCreate`, before any screen asks for a prompt.
 *
 * Bump `version` in the file whenever `default` changes:
 * `SettingsRepository.applyPromptMigration` replaces a stored prompt that is
 * still one of [knownDefaults], and leaves a prompt the user wrote alone.
 */
object PromptCatalog {

    const val ASSET_NAME = "prompts.json"

    /**
     * Only used when the asset is missing or invalid, so a broken file leaves
     * the app with a usable prompt instead of an empty one.
     */
    private const val FALLBACK_DEFAULT = "You are VoiceChat, a helpful assistant."

    private val json = Json { ignoreUnknownKeys = true }

    data class Catalog(
        val version: Int,
        val defaultPrompt: String,
        val presets: List<PromptPreset>,
        val knownDefaults: List<String>,
    )

    @Volatile
    private var catalog = Catalog(
        version = 0,
        defaultPrompt = FALLBACK_DEFAULT,
        presets = emptyList(),
        knownDefaults = emptyList(),
    )

    val current: Catalog get() = catalog
    val version: Int get() = catalog.version
    val defaultPrompt: String get() = catalog.defaultPrompt
    val presets: List<PromptPreset> get() = catalog.presets
    val knownDefaults: List<String> get() = catalog.knownDefaults

    fun attach(context: Context) {
        val text = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        } catch (e: IOException) {
            AppLog.error(LogCategory.SESSION, "Prompt catalog unreadable", e.message)
            return
        }
        val parsed = parse(text)
        if (parsed == null) {
            AppLog.error(LogCategory.SESSION, "Prompt catalog invalid; keeping the fallback prompt")
            return
        }
        catalog = parsed
        AppLog.info(
            LogCategory.SESSION,
            "Prompt catalog v${parsed.version} loaded (${parsed.presets.size} presets)",
        )
    }

    /** Pure parse step, so the shipped file is testable without Android. */
    fun parse(text: String): Catalog? {
        val file = try {
            json.decodeFromString<PromptFile>(text)
        } catch (e: IllegalArgumentException) {
            // Covers SerializationException for malformed JSON and shape errors.
            return null
        }
        val default = file.default.trim()
        if (default.isEmpty()) return null
        val presets = file.presets.mapNotNull { spec ->
            val label = spec.label.trim()
            if (label.isEmpty()) {
                null
            } else {
                PromptPreset(label, spec.prompt.trim().ifEmpty { default })
            }
        }
        return Catalog(
            version = file.version,
            defaultPrompt = default,
            presets = presets,
            knownDefaults = file.knownDefaults.map { it.trim() }.filter { it.isNotEmpty() },
        )
    }
}
