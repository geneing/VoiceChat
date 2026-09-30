package com.voicechat.agent.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Durable storage for the user's [VoiceSettings] (M22).
 *
 * Implementations persist **only** validated, non-secret selections. The store
 * itself does not validate: the state holder validates before every [save], so a
 * caller can never persist an unsupported provider/model/reasoning/voice. There
 * is no API here that accepts a credential.
 *
 * **Ownership and lifecycle.** [observe] is a cold flow: it emits the current
 * value and re-emits after any save (including one made by a different
 * instance over the same durable store, which is what a process restart looks
 * like). [save] replaces the whole record and is safe to call from any
 * coroutine; implementations do their I/O off the main thread.
 */
interface SettingsStore {
    /** Emits the stored settings and re-emits after every change. */
    fun observe(): Flow<VoiceSettings>

    /** Persists [settings], replacing the previous record. */
    suspend fun save(settings: VoiceSettings)
}

/**
 * A process-local [SettingsStore] for tests and JVM tooling.
 *
 * It is **not** durable, so it must never back a shipped build; the Android app
 * uses the DataStore-backed store.
 */
class InMemorySettingsStore(
    initial: VoiceSettings = VoiceSettings.EMPTY,
) : SettingsStore {
    private val state = MutableStateFlow(initial)

    override fun observe(): Flow<VoiceSettings> = state.asStateFlow()

    override suspend fun save(settings: VoiceSettings) {
        state.value = settings
    }
}
