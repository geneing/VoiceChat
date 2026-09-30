package com.voicechat.agent.voice

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel

/**
 * The provider identity and adapter one voice turn runs with.
 *
 * It mirrors the M23 `ActiveProviderTurn` seam but stays platform-free: the
 * coordinator never names a provider, it just asks the app for the adapter the
 * persisted M22 selection resolves to at that moment (R-0103). A provider that
 * fails or is unavailable is the adapter's own honest, typed failure — the
 * coordinator never silently falls back.
 */
data class VoiceTurnProvider(
    val selection: ProviderModelSelection,
    val reasoning: ReasoningLevel?,
    val languageModel: LanguageModel,
)

/**
 * Supplies the provider for the next voice turn.
 *
 * It is called once per committed utterance, so a selection changed in Settings
 * affects the next voice turn without restarting the session. Implementations
 * must be cheap and must not suspend; they read already-resolved state.
 */
fun interface VoiceTurnProviderSource {
    /** @return the adapter and identity for the next turn of [conversation]. */
    fun providerFor(conversation: Conversation): VoiceTurnProvider
}
