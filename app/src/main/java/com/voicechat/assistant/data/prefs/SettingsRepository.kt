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
import com.voicechat.assistant.core.PromptCatalog
import com.voicechat.assistant.core.RealtimeDefaults
import com.voicechat.assistant.data.model.NewsSearchMode
import com.voicechat.assistant.data.model.SearchProvider
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.data.model.UsageTotals
import com.voicechat.assistant.realtime.handoff.HandoffConfig
import com.voicechat.assistant.realtime.handoff.ReasoningModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("voicechat_settings")

data class AppSettings(
    val transport: TransportKind = TransportKind.WEBSOCKET,
    val model: String = RealtimeDefaults.OMNI_MODEL,
    val voice: String = "Tina",
    val instructions: String = PromptCatalog.defaultPrompt,
    val workspaceId: String = RealtimeDefaults.defaultWorkspaceId,
    val host: String = RealtimeDefaults.defaultHost,
    val apiKeyOverride: String = "",
    val aoqTokenUrl: String = "",
    val turnDetection: String = "semantic_vad",
    /**
     * Server VAD trigger. Lower is more sensitive: the user reported having to
     * speak up and repeat themselves, so the default sits below the old 0.5.
     */
    val vadThreshold: Float = 0.35f,
    val silenceMs: Int = 600,
    val onDeviceVad: Boolean = true,
    val vadSensitivityDb: Float = 9f,
    val inputTranscription: Boolean = true,
    val toolsEnabled: Boolean = true,
    val nativeSearch: Boolean = true,
    val searchSources: Boolean = true,
    val searchProvider: SearchProvider = SearchProvider.EXA,
    /** Where the news digest gets its facts from. */
    val newsSearch: NewsSearchMode = NewsSearchMode.APP,
    val languageHint: String = "",
    val priceInputPerMillion: Double = 0.0,
    val priceOutputPerMillion: Double = 0.0,
    /** Hand a hard turn to a stronger reasoning model instead of answering shallowly. */
    val handoffEnabled: Boolean = true,
    val handoffModel: String = ReasoningModels.DEFAULT_ID,
    /** Pre-reason hard typed questions deterministically, before the turn is sent. */
    val handoffHeuristic: Boolean = false,
    /** Warm a reasoning call from a hard voice transcript while the model speaks. */
    val handoffSpeculativeVoice: Boolean = true,
    val handoffThinkingBudget: Int = ReasoningModels.DEFAULT_THINKING_BUDGET,
    val handoffTimeoutMs: Int = HandoffConfig.DEFAULT_TIMEOUT_MS,
    val handoffContextTurns: Int = HandoffConfig.DEFAULT_CONTEXT_TURNS,
    val handoffPriceInputPerMillion: Double = 0.0,
    val handoffPriceOutputPerMillion: Double = 0.0,
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

    private companion object {
        /** Values that earlier builds wrote into preferences as defaults. */
        const val LEGACY_VAD_THRESHOLD = 0.5f
    }

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
            searchProvider = prefs[Keys.searchProvider]?.let { stored ->
                SearchProvider.entries.firstOrNull { it.name == stored }
            } ?: defaults.searchProvider,
            newsSearch = prefs[Keys.newsSearch]?.let { stored ->
                NewsSearchMode.entries.firstOrNull { it.name == stored }
            } ?: defaults.newsSearch,
            languageHint = prefs[Keys.languageHint] ?: defaults.languageHint,
            priceInputPerMillion = prefs[Keys.priceIn] ?: defaults.priceInputPerMillion,
            priceOutputPerMillion = prefs[Keys.priceOut] ?: defaults.priceOutputPerMillion,
            handoffEnabled = prefs[Keys.handoffEnabled] ?: defaults.handoffEnabled,
            handoffModel = ReasoningModels.resolve(
                prefs[Keys.handoffModel] ?: defaults.handoffModel,
            ),
            handoffHeuristic = prefs[Keys.handoffHeuristic] ?: defaults.handoffHeuristic,
            handoffSpeculativeVoice = prefs[Keys.handoffSpeculativeVoice]
                ?: defaults.handoffSpeculativeVoice,
            handoffThinkingBudget = prefs[Keys.handoffThinkingBudget]
                ?: defaults.handoffThinkingBudget,
            handoffTimeoutMs = prefs[Keys.handoffTimeoutMs] ?: defaults.handoffTimeoutMs,
            handoffContextTurns = prefs[Keys.handoffContextTurns] ?: defaults.handoffContextTurns,
            handoffPriceInputPerMillion = prefs[Keys.handoffPriceIn]
                ?: defaults.handoffPriceInputPerMillion,
            handoffPriceOutputPerMillion = prefs[Keys.handoffPriceOut]
                ?: defaults.handoffPriceOutputPerMillion,
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

    /**
     * Installs that stored their debug categories before the `HANDOFF` category
     * existed would silently drop handoff logging, so add it once.
     */
    suspend fun applyHandoffLogMigration() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.handoffLogMigrated] == true) return@edit
            val stored = prefs[Keys.debugCategories]
            if (stored != null) {
                prefs[Keys.debugCategories] = stored + LogCategory.HANDOFF.name
            }
            prefs[Keys.handoffLogMigrated] = true
        }
    }

    /**
     * Moves an install off the previous VAD trigger default. Only a value that
     * still equals the old default is replaced, so a threshold the user tuned
     * is left alone. The prompt is owned by [applyPromptMigration] now.
     *
     * The key was bumped after the first pass compared floats exactly: a slider
     * had stored the "0.5" default as 0.50000006, which is why [near] compares
     * with a tolerance instead.
     */
    suspend fun applyConversationDefaultsMigration() {
        context.dataStore.edit { prefs ->
            if (prefs[Keys.conversationDefaultsV3] == true) return@edit
            val threshold = prefs[Keys.vadThreshold]
            if (threshold == null || near(threshold.toDouble(), LEGACY_VAD_THRESHOLD.toDouble())) {
                prefs[Keys.vadThreshold] = AppSettings().vadThreshold
            }
            prefs[Keys.conversationDefaultsV3] = true
        }
    }

    /**
     * Takes the default prompt from `assets/prompts.json` whenever the shipped
     * file carries a version this install has not applied yet (a fresh install
     * has none). A stored prompt that is still one of the file's known
     * defaults is replaced, so new wording reaches the install; a prompt the
     * user wrote is kept.
     */
    suspend fun applyPromptMigration() {
        val catalog = PromptCatalog.current
        if (catalog.version <= 0) return
        context.dataStore.edit { prefs ->
            if ((prefs[Keys.promptVersion] ?: 0) >= catalog.version) return@edit
            val stored = prefs[Keys.instructions]
            if (stored.isNullOrBlank() ||
                catalog.knownDefaults.any { sameText(it, stored) }
            ) {
                prefs[Keys.instructions] = catalog.defaultPrompt
            }
            prefs[Keys.promptVersion] = catalog.version
        }
    }

    /** Slider-committed floats carry rounding noise, so compare with a tolerance. */
    private fun near(a: Double, b: Double): Boolean = kotlin.math.abs(a - b) < 0.001

    /** Whitespace-insensitive comparison for prompt text. */
    private fun sameText(a: String, b: String): Boolean =
        a.trim().replace(Regex("\\s+"), " ") == b.trim().replace(Regex("\\s+"), " ")

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
        prefs[Keys.searchProvider] = s.searchProvider.name
        prefs[Keys.newsSearch] = s.newsSearch.name
        prefs[Keys.languageHint] = s.languageHint
        prefs[Keys.priceIn] = s.priceInputPerMillion
        prefs[Keys.priceOut] = s.priceOutputPerMillion
        prefs[Keys.handoffEnabled] = s.handoffEnabled
        prefs[Keys.handoffModel] = s.handoffModel
        prefs[Keys.handoffHeuristic] = s.handoffHeuristic
        prefs[Keys.handoffSpeculativeVoice] = s.handoffSpeculativeVoice
        prefs[Keys.handoffThinkingBudget] = s.handoffThinkingBudget
        prefs[Keys.handoffTimeoutMs] = s.handoffTimeoutMs
        prefs[Keys.handoffContextTurns] = s.handoffContextTurns
        prefs[Keys.handoffPriceIn] = s.handoffPriceInputPerMillion
        prefs[Keys.handoffPriceOut] = s.handoffPriceOutputPerMillion
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
        val handoffLogMigrated = booleanPreferencesKey("handoff_log_migrated")
        val conversationDefaultsV3 = booleanPreferencesKey("conversation_defaults_v3")
        val promptVersion = intPreferencesKey("prompt_catalog_version")
        val searchSources = booleanPreferencesKey("search_sources")
        val searchProvider = stringPreferencesKey("search_provider")
        val newsSearch = stringPreferencesKey("news_search")
        val languageHint = stringPreferencesKey("language_hint")
        val priceIn = doublePreferencesKey("price_input_per_million")
        val priceOut = doublePreferencesKey("price_output_per_million")
        val handoffEnabled = booleanPreferencesKey("handoff_enabled")
        val handoffModel = stringPreferencesKey("handoff_model")
        val handoffHeuristic = booleanPreferencesKey("handoff_heuristic")
        val handoffSpeculativeVoice = booleanPreferencesKey("handoff_speculative_voice")
        val handoffThinkingBudget = intPreferencesKey("handoff_thinking_budget")
        val handoffTimeoutMs = intPreferencesKey("handoff_timeout_ms")
        val handoffContextTurns = intPreferencesKey("handoff_context_turns")
        val handoffPriceIn = doublePreferencesKey("handoff_price_input_per_million")
        val handoffPriceOut = doublePreferencesKey("handoff_price_output_per_million")
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
