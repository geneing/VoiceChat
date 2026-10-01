package com.voicechat.agent.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.stt.SttMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private object SettingsKeys {
    val STT_MODE = stringPreferencesKey("stt.mode")
    val STT_LOCALE = stringPreferencesKey("stt.locale")
    val LLM_PROVIDER = stringPreferencesKey("llm.provider")
    val LLM_MODEL = stringPreferencesKey("llm.model")
    val LLM_AUTH = stringPreferencesKey("llm.auth")
    val REASONING = stringPreferencesKey("llm.reasoning")
    val LLM_SERVER_URL = stringPreferencesKey("llm.serverUrl")
    val LLM_LOCAL_MODEL = stringPreferencesKey("llm.localModel")
    val TTS_VOICE = stringPreferencesKey("tts.voice")
    val SMART_TURN = booleanPreferencesKey("smartTurn.enabled")
}

/**
 * DataStore (Preferences)-backed [SettingsStore] — the M00-selected settings
 * persistence (`docs/decisions.md` §1).
 *
 * It stores a small set of typed, non-secret string fields. **No credential is
 * ever written here**; the API-key/OAuth value lives only in the M13
 * Keystore-backed `CredentialStore`. The generic [DataStore] is injected so the
 * same logic is exercised by a JVM/Robolectric test over a temp file.
 */
class PreferencesSettingsStore(
    private val dataStore: DataStore<Preferences>,
) : SettingsStore {
    override fun observe(): Flow<VoiceSettings> = dataStore.data.map { it.toVoiceSettings() }

    override suspend fun save(settings: VoiceSettings) {
        dataStore.edit { preferences ->
            preferences.putOrRemove(SettingsKeys.STT_MODE, settings.sttMode?.name)
            preferences.putOrRemove(SettingsKeys.STT_LOCALE, settings.sttLocaleLanguageTag)
            preferences.putOrRemove(SettingsKeys.LLM_PROVIDER, settings.llmProviderId?.value)
            preferences.putOrRemove(SettingsKeys.LLM_MODEL, settings.llmModelId?.value)
            preferences.putOrRemove(SettingsKeys.LLM_AUTH, settings.llmAuthMethod?.name)
            preferences.putOrRemove(SettingsKeys.REASONING, settings.reasoningLevel?.name)
            preferences.putOrRemove(SettingsKeys.LLM_SERVER_URL, settings.llmServerUrl)
            preferences.putOrRemove(SettingsKeys.LLM_LOCAL_MODEL, settings.llmLocalModelId?.value)
            preferences.putOrRemove(SettingsKeys.TTS_VOICE, settings.ttsVoiceId)
            preferences[SettingsKeys.SMART_TURN] = settings.smartTurnEnabled
        }
    }

    companion object {
        /** The app-private DataStore name for the settings preferences file. */
        const val PREFERENCES_NAME: String = "voicechat-settings"

        /** Creates the app's settings store over its app-private DataStore file. */
        fun create(context: Context): SettingsStore = PreferencesSettingsStore(context.settingsDataStore)
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = PreferencesSettingsStore.PREFERENCES_NAME)

private fun MutablePreferences.putOrRemove(
    key: Preferences.Key<String>,
    value: String?,
) {
    if (value.isNullOrBlank()) remove(key) else set(key, value)
}

private fun Preferences.toVoiceSettings(): VoiceSettings {
    // First run: the record is completely pristine (no key of any kind has ever
    // been written), so seed the app's documented default provider/model. A
    // record that was explicitly cleared keeps its cleared value: clearing still
    // writes the smart-turn flag, so the record is no longer pristine.
    val pristine =
        this[SettingsKeys.STT_MODE] == null &&
            this[SettingsKeys.STT_LOCALE] == null &&
            this[SettingsKeys.LLM_PROVIDER] == null &&
            this[SettingsKeys.LLM_MODEL] == null &&
            this[SettingsKeys.LLM_AUTH] == null &&
            this[SettingsKeys.REASONING] == null &&
            this[SettingsKeys.LLM_SERVER_URL] == null &&
            this[SettingsKeys.LLM_LOCAL_MODEL] == null &&
            this[SettingsKeys.TTS_VOICE] == null &&
            this[SettingsKeys.SMART_TURN] == null
    return if (pristine) VoiceSettings.firstRunDefaults() else toStoredVoiceSettings()
}

private fun Preferences.toStoredVoiceSettings(): VoiceSettings =
    VoiceSettings(
        sttMode = enumOrNull<SttMode>(this[SettingsKeys.STT_MODE]),
        sttLocaleLanguageTag = this[SettingsKeys.STT_LOCALE],
        llmProviderId = this[SettingsKeys.LLM_PROVIDER]?.let(::ProviderId),
        llmModelId = this[SettingsKeys.LLM_MODEL]?.let(::ModelId),
        llmAuthMethod = enumOrNull<AuthMethod>(this[SettingsKeys.LLM_AUTH]),
        reasoningLevel = enumOrNull<ReasoningLevel>(this[SettingsKeys.REASONING]),
        llmServerUrl = this[SettingsKeys.LLM_SERVER_URL],
        ttsVoiceId = this[SettingsKeys.TTS_VOICE],
        smartTurnEnabled = this[SettingsKeys.SMART_TURN] ?: true,
        llmLocalModelId = this[SettingsKeys.LLM_LOCAL_MODEL]?.let(::ModelId),
    )

private inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? =
    name?.let { candidate -> enumValues<T>().firstOrNull { it.name == candidate } }
