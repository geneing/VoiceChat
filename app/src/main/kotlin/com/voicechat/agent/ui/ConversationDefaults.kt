package com.voicechat.agent.ui

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmFailureReason
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * The honest default until a provider is configured (M13–M19).
 *
 * M06 delivers the UI and the manual-text turn path, not a real provider. Wiring
 * this model makes the app's failure explicit instead of faking a reply: every
 * request completes with [ErrorCode.LLM_NOT_CONFIGURED], which the dialog shows
 * as a recoverable error state. M23 replaces it with a real adapter without
 * changing the UI or the turn path.
 *
 * It declares **no** capabilities, so the app never claims streaming, usage, or
 * reasoning support before a provider exists — the same honesty rule the
 * per-provider capability matrix applies to real adapters.
 */
class NotConfiguredLanguageModel(
    override val providerId: ProviderId = ConversationDefaults.selection.providerId,
    override val capabilities: LlmCapabilities =
        LlmCapabilities(
            streaming = false,
            usageReporting = false,
            reasoningLevels = emptySet(),
        ),
) : LanguageModel {
    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flowOf(
            LlmStreamEvent.Failed(
                error = VoiceAgentError(ErrorCode.LLM_NOT_CONFIGURED),
                partialText = "",
                reason = LlmFailureReason.NOT_CONFIGURED,
            ),
        )

    override suspend fun close() = Unit
}

/** Provider/model identity the app uses before settings are implemented. */
object ConversationDefaults {
    /** Placeholder provider identity; never presented as a real provider. */
    val selection: ProviderModelSelection =
        ProviderModelSelection(
            providerId = ProviderId("unconfigured"),
            modelId = ModelId("unconfigured"),
        )

    /** @return the [LanguageModel] the app runs with before a provider exists. */
    fun languageModel(): LanguageModel = NotConfiguredLanguageModel()
}
