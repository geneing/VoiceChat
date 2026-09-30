package com.voicechat.agent.settings

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.LlmCapabilityReconciler
import com.voicechat.agent.providers.ModelCapabilityCatalog
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.tts.OnDeviceVoiceSelector

/** Which stored selection was dropped because it is no longer valid. */
enum class InvalidSelection {
    /** The provider id is unknown to the registry. */
    PROVIDER,

    /** The model is absent or not ready for the selected provider. */
    MODEL,

    /** The auth method is not one the provider documents. */
    AUTH_METHOD,

    /** The reasoning level is not exposed by the provider+model. */
    REASONING,

    /** The STT mode is not selectable/ready on this device. */
    STT_MODE,

    /** The TTS voice is not installed as an on-device voice. */
    TTS_VOICE,

    /** Smart Turn is enabled but not available on this device. */
    SMART_TURN,
}

/**
 * A validated settings record plus the selections that had to be dropped.
 *
 * Only [settings] may be trusted or persisted; [invalid] explains what was
 * cleared so the UI can tell the user instead of silently forgetting a choice.
 */
data class SettingsValidation(
    val settings: VoiceSettings,
    val invalid: List<InvalidSelection>,
) {
    /** True when nothing had to be corrected. */
    val isValid: Boolean get() = invalid.isEmpty()
}

/**
 * Sanitizes a [VoiceSettings] against the live catalogs (M22).
 *
 * This is the mechanical form of "persist only validated selections”: a stored
 * provider, model, auth method, reasoning level, STT mode, TTS voice, or Smart
 * Turn flag that the registry/runtime no longer supports is cleared before it is
 * applied or re-saved. It never *adds* a selection — an unknown field stays
 * unknown rather than being defaulted to something unsupported.
 */
object SettingsValidator {
    fun validate(
        settings: VoiceSettings,
        registry: ProviderCapabilityRegistry,
        capabilities: SettingsCapabilities,
        modelCatalog: ModelCapabilityCatalog = EmptyModelCapabilityCatalog,
    ): SettingsValidation {
        val invalid = mutableListOf<InvalidSelection>()
        var result = settings

        // --- Provider / model / auth / reasoning ---------------------------------
        val providerId = settings.llmProviderId
        if (providerId == null) {
            // Nothing to validate until a provider is chosen; drop a stray model.
            if (result.llmModelId != null || result.llmAuthMethod != null || result.reasoningLevel != null) {
                result = result.copy(llmModelId = null, llmAuthMethod = null, reasoningLevel = null)
                invalid += InvalidSelection.MODEL
            }
        } else {
            val provider = registry.capabilities(providerId)
            if (provider == null) {
                result = result.copy(llmProviderId = null, llmModelId = null, llmAuthMethod = null, reasoningLevel = null)
                invalid += InvalidSelection.PROVIDER
            } else {
                val authMethod = result.llmAuthMethod
                if (authMethod != null && authMethod !in provider.availableAuthMethods) {
                    result = result.copy(llmAuthMethod = null)
                    invalid += InvalidSelection.AUTH_METHOD
                }

                val modelId = result.llmModelId
                if (modelId == null) {
                    if (result.reasoningLevel != null) {
                        result = result.copy(reasoningLevel = null)
                        invalid += InvalidSelection.REASONING
                    }
                } else {
                    val availability =
                        capabilities.models.firstOrNull {
                            it.model.providerId == providerId && it.model.id == modelId
                        }
                    if (availability !is ModelAvailability.Ready) {
                        result = result.copy(llmModelId = null, reasoningLevel = null)
                        invalid += InvalidSelection.MODEL
                    } else {
                        val model = modelCatalog.modelCapabilities(providerId, modelId)
                        val effective = LlmCapabilityReconciler.effective(provider, model)
                        val level = result.reasoningLevel
                        if (level != null && level != ReasoningLevel.NONE && level !in effective.reasoningLevels) {
                            result = result.copy(reasoningLevel = null)
                            invalid += InvalidSelection.REASONING
                        }
                    }
                }
            }
        }

        // --- STT ----------------------------------------------------------------
        val sttMode = result.sttMode
        if (sttMode != null) {
            val ready = capabilities.sttAvailability.any { it is SttAvailability.Ready && it.engine.mode == sttMode }
            if (!ready) {
                result = result.copy(sttMode = null)
                invalid += InvalidSelection.STT_MODE
            }
        }

        // --- TTS ----------------------------------------------------------------
        val voiceId = result.ttsVoiceId
        if (voiceId != null) {
            val installed =
                OnDeviceVoiceSelector
                    .onDeviceVoices(capabilities.ttsVoices)
                    .any { it.id == voiceId }
            if (!installed) {
                result = result.copy(ttsVoiceId = null)
                invalid += InvalidSelection.TTS_VOICE
            }
        }

        // --- Smart Turn ---------------------------------------------------------
        if (result.smartTurnEnabled && capabilities.smartTurn !is SmartTurnState.Available) {
            result = result.copy(smartTurnEnabled = false)
            invalid += InvalidSelection.SMART_TURN
        }

        return SettingsValidation(settings = result, invalid = invalid)
    }
}

/**
 * The catalog used before a provider's `/models` surface is wired (M14+).
 *
 * It claims **no** per-model reasoning level, so the reconciler offers only the
 * default and an unsupported reasoning option is hidden rather than assumed.
 */
object EmptyModelCapabilityCatalog : ModelCapabilityCatalog {
    override fun modelCapabilities(
        providerId: com.voicechat.agent.domain.ProviderId,
        modelId: com.voicechat.agent.domain.ModelId,
    ): com.voicechat.agent.providers.ModelCapabilities? = null
}

/** Convenience for the app: a settings copy with no provider chosen. */
internal fun VoiceSettings.withoutLlmSelection(): VoiceSettings = copy(llmModelId = null, llmAuthMethod = null, reasoningLevel = null)

/** All documented auth methods across the registry; used by tests to prove none is a QR method. */
fun ProviderCapabilityRegistry.allAuthMethods(): Set<AuthMethod> = all().flatMap { it.availableAuthMethods }.toSet()
