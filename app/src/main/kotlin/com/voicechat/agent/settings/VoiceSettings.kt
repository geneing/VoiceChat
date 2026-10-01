package com.voicechat.agent.settings

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.KnownProviders
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
    /** Smart Turn is on by default when the detector is installed (decisions §3.3). */
    val smartTurnEnabled: Boolean = true,
    /**
     * The selected **on-device** local model (M20), or `null` for the remote path.
     * A flat, non-sensitive id: it names an allow-listed local model, never a
     * path or credential. Local and remote selection are mutually exclusive in
     * the turn path so the user's explicit choice is never silently overridden.
     */
    val llmLocalModelId: ModelId? = null,
) {
    /**
     * The provider/model pair, or `null` when incomplete or when an on-device
     * local model is selected (M20): the two backends are mutually exclusive, so
     * a local choice never resolves to a remote provider.
     */
    val llmSelection: ProviderModelSelection?
        get() {
            if (llmLocalModelId != null) return null
            val provider = llmProviderId ?: return null
            val model = llmModelId ?: return null
            return ProviderModelSelection(providerId = provider, modelId = model)
        }

    /** True when the user chose an on-device local model rather than a remote one. */
    val usesLocalModel: Boolean get() = llmLocalModelId != null

    /** The reasoning level a request should use; absent means [ReasoningLevel.NONE]. */
    val effectiveReasoningLevel: ReasoningLevel
        get() = reasoningLevel ?: ReasoningLevel.NONE

    /** The STT/TTS locale, falling back to the single-engine default. */
    fun locale(): Locale = Locale.forLanguageTag(sttLocaleLanguageTag ?: DEFAULT_LANGUAGE_TAG)

    companion object {
        /** Default BCP-47 tag matching `SttEngines.DEFAULT_LOCALE`. */
        val DEFAULT_LANGUAGE_TAG: String = SttEngines.DEFAULT_LOCALE.toLanguageTag()

        /**
         * The provider the app ships as the initial selection. It is a real,
         * documented provider with a free model, so a fresh install opens on a
         * working default that the user can change in Settings. Selecting it
         * persists through the same validation as any other choice and implies no
         * credential: a send without a stored key still fails honestly.
         */
        val DEFAULT_PROVIDER_ID: ProviderId = KnownProviders.OPENCODE_GO

        /** The model paired with [DEFAULT_PROVIDER_ID]: OpenCode Go's free model. */
        val DEFAULT_MODEL_ID: ModelId = ModelId("longcat-2.5-preview-free")

        /**
         * The only honest starting point when nothing is stored: the app's
         * documented default provider/model, the default STT locale, and Smart
         * Turn enabled (validation drops it when the detector is not installed).
         */
        val EMPTY: VoiceSettings =
            VoiceSettings(
                sttLocaleLanguageTag = DEFAULT_LANGUAGE_TAG,
                llmProviderId = DEFAULT_PROVIDER_ID,
                llmModelId = DEFAULT_MODEL_ID,
            )

        /**
         * The first-run defaults applied when the durable store has **nothing**
         * persisted for the provider yet.
         *
         * It is separate from [EMPTY] because [EMPTY] is also the "nothing
         * selected" value a caller may construct deliberately; only the store's
         * read path may seed the documented default, and only when no provider
         * key exists at all. A user who chose to clear the provider keeps it
         * cleared: once a provider key is written (even an empty one is not), the
         * stored value wins.
         */
        fun firstRunDefaults(): VoiceSettings = EMPTY
    }
}
