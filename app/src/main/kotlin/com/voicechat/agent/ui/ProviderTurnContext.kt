package com.voicechat.agent.ui

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderLanguageModelFactory
import com.voicechat.agent.settings.VoiceSettings

/**
 * The provider identity and adapter a single conversation turn runs with (M23).
 *
 * It is resolved from the **persisted M22 selection** at send time, so changing
 * the settings selection changes which adapter and which
 * [ProviderModelSelection] the turn uses (R-0103) — the UI never passes a fixed
 * provider. [configured] is false only when nothing is selected (or the selected
 * provider has no adapter), in which case [languageModel] is the honest
 * not-configured placeholder that fails with `LLM_NOT_CONFIGURED` rather than
 * faking a reply (R-0012).
 */
data class ActiveProviderTurn(
    val selection: ProviderModelSelection,
    val reasoning: ReasoningLevel?,
    val languageModel: LanguageModel,
    val configured: Boolean,
)

/**
 * Turns the current [VoiceSettings] into the adapter and identity for one turn.
 *
 * A pure, platform-free function so the selection→adapter wiring is a JVM test
 * with no device, network, or credential. The conversation id is passed as the
 * provider session hint, so a provider that documents a session header gets a
 * conversation-scoped (never secret) value (R-0132).
 */
object ProviderTurnResolver {
    fun resolve(
        settings: VoiceSettings,
        conversationId: ConversationId,
        registry: ProviderCapabilityRegistry,
        factory: ProviderLanguageModelFactory,
    ): ActiveProviderTurn {
        val selection = settings.llmSelection ?: return unconfigured()
        // No adapter for this provider: keep the explicit not-configured state.
        val model =
            factory.create(
                selection = selection,
                sessionHint = conversationId.value,
                configuredServerUrl = settings.llmServerUrl,
            ) ?: return unconfigured()
        return ActiveProviderTurn(
            selection = selection,
            reasoning = settings.reasoningLevel,
            languageModel = model,
            configured = true,
        )
    }

    private fun unconfigured(): ActiveProviderTurn {
        val selection = ConversationDefaults.selection
        return ActiveProviderTurn(
            selection = selection,
            reasoning = null,
            languageModel = NotConfiguredLanguageModel(providerId = selection.providerId),
            configured = false,
        )
    }
}
