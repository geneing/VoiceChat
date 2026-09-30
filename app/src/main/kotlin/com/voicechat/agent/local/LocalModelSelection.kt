package com.voicechat.agent.local

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.settings.VoiceSettings

/**
 * Which backend a settings record selects for the language-model turn (M20).
 *
 * The two are deliberately exclusive: the app always knows whether a turn is
 * on-device or remote, and no code path turns one into the other.
 */
sealed interface LlmBackend {
    /** Nothing is selected. */
    data object None : LlmBackend

    /** An on-device model was chosen. */
    data class OnDevice(
        val modelId: ModelId,
    ) : LlmBackend

    /** A remote provider/model was chosen. */
    data class Remote(
        val providerId: com.voicechat.agent.domain.ProviderId,
        val modelId: ModelId,
    ) : LlmBackend
}

/**
 * Settings transitions that keep the local/remote choice explicit (M20).
 *
 * Selecting a local model clears the remote provider/model/auth/reasoning fields
 * (the destination address is kept, since it belongs to the remote provider and
 * carries no secret), and clearing the local selection restores the remote path.
 * This is the mechanical form of "do not silently fall back between them".
 */
object LocalModelSelection {
    /** A copy that selects [modelId] on-device and clears any remote selection. */
    fun selectLocal(
        settings: VoiceSettings,
        modelId: ModelId,
    ): VoiceSettings =
        settings.copy(
            llmLocalModelId = modelId,
            llmProviderId = null,
            llmModelId = null,
            llmAuthMethod = null,
            reasoningLevel = null,
        )

    /** A copy that clears the on-device selection, leaving any remote selection. */
    fun clearLocal(settings: VoiceSettings): VoiceSettings = settings.copy(llmLocalModelId = null)

    /** The backend [settings] currently selects. */
    fun backendOf(settings: VoiceSettings): LlmBackend {
        val local = settings.llmLocalModelId
        if (local != null) return LlmBackend.OnDevice(local)
        val remote = settings.llmSelection ?: return LlmBackend.None
        return LlmBackend.Remote(providerId = remote.providerId, modelId = remote.modelId)
    }
}
