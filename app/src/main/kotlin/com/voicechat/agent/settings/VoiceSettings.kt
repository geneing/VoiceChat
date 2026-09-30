package com.voicechat.agent.settings

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.stt.SttMode
import java.util.Locale

/**
 * The user's validated, capability-aware configuration (M22).
 *
 * Every field is optional or has an honest default: **nothing** is pre-selected
 * that the runtime and registry have not reported as supported, and the model
 * carries only what the app decided — never a provider SDK or platform type.
 *
 * **No secret is ever a field here.** The LLM credential is stored only in the
 * M13 `CredentialStore`; settings persist the *choice* of how to authenticate
 * ([llmAuthMethod]), not the credential value. This type is persisted through
 * DataStore (Preferences), so it stays a small, flat, non-sensitive record.
 */
data class VoiceSettings(
    /** The selected on-device STT mode, or `null` when none is validated. */
    val sttMode: SttMode? = null,
    /** BCP-47 language tag for STT/TTS; a string so the record stays flat. */
    val sttLocaleLanguageTag: String? = null,
    /** The selected remote provider, or `null` until one is chosen. */
    val llmProviderId: ProviderId? = null,
    /** The selected model for [llmProviderId], or `null`. */
    val llmModelId: ModelId? = null,
    /** How the app authenticates; must be one of the provider's documented methods. */
    val llmAuthMethod: AuthMethod? = null,
    /** The requested reasoning level, or `null` for the provider default. */
    val reasoningLevel: ReasoningLevel? = null,
    /**
     * A user/admin-configured server address for a configurable provider
     * (Hermes). `null` for fixed providers and until a valid address is entered.
     * Validated before it is stored, so it is never an arbitrary destination.
     */
    val llmServerUrl: String? = null,
    /** The selected embedded TTS voice id, or `null`. */
    val ttsVoiceId: String? = null,
    /** Smart Turn is opt-in and default-off until M25 evidence (decisions §3.3). */
    val smartTurnEnabled: Boolean = false,
) {
    /** The provider/model pair, or `null` when incomplete. */
    val llmSelection: ProviderModelSelection?
        get() {
            val provider = llmProviderId ?: return null
            val model = llmModelId ?: return null
            return ProviderModelSelection(providerId = provider, modelId = model)
        }

    /** The reasoning level a request should use; absent means [ReasoningLevel.NONE]. */
    val effectiveReasoningLevel: ReasoningLevel
        get() = reasoningLevel ?: ReasoningLevel.NONE

    /** The STT/TTS locale, falling back to the single-engine default. */
    fun locale(): Locale = Locale.forLanguageTag(sttLocaleLanguageTag ?: DEFAULT_LANGUAGE_TAG)

    companion object {
        /** Default BCP-47 tag matching `SttEngines.DEFAULT_LOCALE`. */
        val DEFAULT_LANGUAGE_TAG: String = SttEngines.DEFAULT_LOCALE.toLanguageTag()

        /** Nothing selected; the only honest starting point. */
        val EMPTY: VoiceSettings = VoiceSettings(sttLocaleLanguageTag = DEFAULT_LANGUAGE_TAG)
    }
}
