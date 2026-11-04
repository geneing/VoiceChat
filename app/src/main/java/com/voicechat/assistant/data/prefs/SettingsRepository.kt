package com.voicechat.assistant.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.core.RealtimeDefaults
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.data.model.UsageTotals
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("voicechat_settings")

data class AppSettings(
    val transport: TransportKind = TransportKind.WEBSOCKET,
    val model: String = RealtimeDefaults.OMNI_MODEL,
    val voice: String = "Tina",
    val instructions: String = RealtimeDefaults.DEFAULT_INSTRUCTIONS,
    val workspaceId: String = RealtimeDefaults.defaultWorkspaceId,
    val host: String = RealtimeDefaults.defaultHost,
    val apiKeyOverride: String = "",
    val aoqTokenUrl: String = "",
    val turnDetection: String = "semantic_vad",
    val vadThreshold: Float = 0.5f,
    val silenceMs: Int = 600,
    val onDeviceVad: Boolean = true,
    val vadSensitivityDb: Float = 9f,
    val inputTranscription: Boolean = true,
    val toolsEnabled: Boolean = true,
    val nativeSearch: Boolean = true,
    val searchSources: Boolean = true,
    val languageHint: String = "",
    val priceInputPerMillion: Double = 0.0,
    val priceOutputPerMillion: Double = 0.0,
    val debugCategories: Set<LogCategory> = LogCategory.entries.toSet(),
    val protocolDump: Boolean = true,
    val fileLogging: Boolean = false,
    val showMetricsInChat: Boolean = true,
) {
    val apiKey: String get() = apiKeyOverride.ifBlank { RealtimeDefaults.defaultApiKey }
    val hasApiKey: Boolean get() = apiKey.isNotBlank()
    val modelInfo get() = RealtimeDefaults.models.firstOrNull { it.id == model }
        ?: RealtimeDefaults.models.first()
}

class SettingsRepository(private val context: Context) {

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs -> toSettings(prefs) }

    private fun toSettings(prefs: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            transport = prefs[Keys.transport]?.let { name ->
                TransportKind.entries.firstOrNull { it.name == name }
            } ?: defaults.transport,
            model = prefs[Keys.model] ?: defaults.model,
            voice = prefs[Keys.voice] ?: defaults.voice,
            instructions = prefs[Keys.instructions] ?: defaults.instructions,
            workspaceId = prefs[Keys.workspaceId] ?: defaults.workspaceId,
            host = prefs[Keys.host] ?: defaults.host,
            apiKeyOverride = prefs[Keys.apiKey] ?: defaults.apiKeyOverride,
            aoqTokenUrl = prefs[Keys.aoqTokenUrl] ?: defaults.aoqTokenUrl,
            turnDetection = prefs[Keys.turnDetection] ?: defaults.turnDetection,
            vadThreshold = prefs[Keys.vadThreshold] ?: defaults.vadThreshold,
            silenceMs = prefs[Keys.silenceMs] ?: defaults.silenceMs,
            onDeviceVad = prefs[Keys.onDeviceVad] ?: defaults.onDeviceVad,
            vadSensitivityDb = prefs[Keys.vadSensitivityDb] ?: defaults.vadSensitivityDb,
            inputTranscription = prefs[Keys.inputTranscription] ?: defaults.inputTranscription,
            toolsEnabled = prefs[Keys.toolsEnabled] ?: defaults.toolsEnabled,
            nativeSearch = prefs[Keys.nativeSearch] ?: defaults.nativeSearch,
            searchSources = prefs[Keys.searchSources] ?: defaults.searchSources,
            languageHint = prefs[Keys.languageHint] ?: defaults.languageHint,
            priceInputPerMillion = prefs[Keys.priceIn] ?: defaults.priceInputPerMillion,
            priceOutputPerMillion = prefs[Keys.priceOut] ?: defaults.priceOutputPerMillion,
            debugCategories = prefs[Keys.debugCategories]
                ?.mapNotNull { name -> LogCategory.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: defaults.debugCategories,
            protocolDump = prefs[Keys.protocolDump] ?: defaults.protocolDump,
            fileLogging = prefs[Keys.fileLogging] ?: defaults.fileLogging,
            showMetricsInChat = prefs[Keys.showMetrics] ?: defaults.showMetricsInChat,
        )
    }

    val usageTotals: Flow<UsageTotals> = context.dataStore.data.map { prefs ->
        UsageTotals(
            inputTokens = prefs[Keys.usageInput] ?: 0,
            outputTokens = prefs[Keys.usageOutput] ?: 0,
            costUsd = prefs[Keys.usageCost] ?: 0.0,
            billingStartMs = prefs[Keys.billingStart] ?: 0L,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    /**
     * Server-side search is the default now. Turn it on once for installs that
     * still hold the old default; the client tools keep working alongside it.
     */
    suspend fun applySearchDefaultMigration() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.searchDefaultMigrated] == true) return@edit
            prefs[Keys.nativeSearch] = true
            prefs[Keys.searchDefaultMigrated] = true
        }
    }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        // Read inside the edit so a concurrent one-time migration (or another
        // writer) is never overwritten by a stale snapshot.
        context.dataStore.edit { prefs -> writeAll(prefs, transform(toSettings(prefs))) }
    }

    suspend fun addUsage(inputTokens: Int, outputTokens: Int, costUsd: Double) {
        context.dataStore.edit { prefs ->
            prefs[Keys.usageInput] = (prefs[Keys.usageInput] ?: 0) + inputTokens
            prefs[Keys.usageOutput] = (prefs[Keys.usageOutput] ?: 0) + outputTokens
            prefs[Keys.usageCost] = (prefs[Keys.usageCost] ?: 0.0) + costUsd
            if ((prefs[Keys.billingStart] ?: 0L) == 0L) {
                prefs[Keys.billingStart] = System.currentTimeMillis()
            }
        }
    }

    suspend fun resetUsage() {
        context.dataStore.edit { prefs ->
            prefs[Keys.usageInput] = 0
            prefs[Keys.usageOutput] = 0
            prefs[Keys.usageCost] = 0.0
            prefs[Keys.billingStart] = System.currentTimeMillis()
        }
    }

    private fun writeAll(prefs: androidx.datastore.preferences.core.MutablePreferences, s: AppSettings) {
        prefs[Keys.transport] = s.transport.name
        prefs[Keys.model] = s.model
        prefs[Keys.voice] = s.voice
        prefs[Keys.instructions] = s.instructions
        prefs[Keys.workspaceId] = s.workspaceId
        prefs[Keys.host] = s.host
        prefs[Keys.apiKey] = s.apiKeyOverride
        prefs[Keys.aoqTokenUrl] = s.aoqTokenUrl
        prefs[Keys.turnDetection] = s.turnDetection
        prefs[Keys.vadThreshold] = s.vadThreshold
        prefs[Keys.silenceMs] = s.silenceMs
        prefs[Keys.onDeviceVad] = s.onDeviceVad
        prefs[Keys.vadSensitivityDb] = s.vadSensitivityDb
        prefs[Keys.inputTranscription] = s.inputTranscription
        prefs[Keys.toolsEnabled] = s.toolsEnabled
        prefs[Keys.nativeSearch] = s.nativeSearch
        prefs[Keys.searchSources] = s.searchSources
        prefs[Keys.languageHint] = s.languageHint
        prefs[Keys.priceIn] = s.priceInputPerMillion
        prefs[Keys.priceOut] = s.priceOutputPerMillion
        prefs[Keys.debugCategories] = s.debugCategories.map { it.name }.toSet()
        prefs[Keys.protocolDump] = s.protocolDump
        prefs[Keys.fileLogging] = s.fileLogging
        prefs[Keys.showMetrics] = s.showMetricsInChat
    }

    private object Keys {
        val transport = stringPreferencesKey("transport")
        val model = stringPreferencesKey("model")
        val voice = stringPreferencesKey("voice")
        val instructions = stringPreferencesKey("instructions")
        val workspaceId = stringPreferencesKey("workspace_id")
        val host = stringPreferencesKey("host")
        val apiKey = stringPreferencesKey("api_key_override")
        val aoqTokenUrl = stringPreferencesKey("aoq_token_url")
        val turnDetection = stringPreferencesKey("turn_detection")
        val vadThreshold = floatPreferencesKey("vad_threshold")
        val silenceMs = intPreferencesKey("silence_ms")
        val onDeviceVad = booleanPreferencesKey("on_device_vad")
        val vadSensitivityDb = floatPreferencesKey("vad_sensitivity_db")
        val inputTranscription = booleanPreferencesKey("input_transcription")
        val toolsEnabled = booleanPreferencesKey("tools_enabled")
        val nativeSearch = booleanPreferencesKey("native_search")
        val searchDefaultMigrated = booleanPreferencesKey("search_default_migrated")
        val searchSources = booleanPreferencesKey("search_sources")
        val languageHint = stringPreferencesKey("language_hint")
        val priceIn = doublePreferencesKey("price_input_per_million")
        val priceOut = doublePreferencesKey("price_output_per_million")
        val debugCategories = stringSetPreferencesKey("debug_categories")
        val protocolDump = booleanPreferencesKey("protocol_dump")
        val fileLogging = booleanPreferencesKey("file_logging")
        val showMetrics = booleanPreferencesKey("show_metrics")
        val usageInput = longPreferencesKey("usage_input_tokens")
        val usageOutput = longPreferencesKey("usage_output_tokens")
        val usageCost = doublePreferencesKey("usage_cost_usd")
        val billingStart = longPreferencesKey("billing_start_ms")
    }
}
