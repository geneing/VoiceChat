package com.voicechat.agent.settings

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.LlmCapabilityReconciler
import com.voicechat.agent.providers.ModelCapabilityCatalog
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.tts.OnDeviceVoiceSelector
import com.voicechat.agent.tts.TtsVoice

/**
 * Whether a selectable option can be chosen right now.
 *
 * Unavailable is first-class and always carries a reason so the UI can disable
 * the entry with an explanation instead of hiding it silently. Unsupported
 * capabilities are a different thing and are simply never produced (for example
 * an auth method the provider does not document), so they cannot be shown by
 * accident.
 */
sealed interface OptionState {
    /** The option is supported and usable on this device now. */
    data object Available : OptionState

    /** Supported in principle but not usable now; [reason] is safe to show. */
    data class Unavailable(
        val reason: String,
    ) : OptionState
}

/** One selectable entry with its availability, used by every settings section. */
data class SelectableOption<T>(
    val value: T,
    val label: String,
    val state: OptionState,
    val selected: Boolean = false,
) {
    /** True when the option may be chosen. */
    val isAvailable: Boolean get() = state is OptionState.Available

    /** The reason an unavailable option cannot be chosen, or `null`. */
    val unavailableReason: String? get() = (state as? OptionState.Unavailable)?.reason

    /** True when the option is the current selection. */
    val isSelected: Boolean get() = selected
}

/**
 * Builds the capability-aware option lists the settings surface renders (M22).
 *
 * Two rules are enforced mechanically:
 * - an **unsupported** capability is never produced at all (an undocumented auth
 *   method, a reasoning level outside the reconciled provider+model set, a
 *   network-required TTS voice), so it cannot be shown;
 * - a **known but currently unavailable** entry is produced with
 *   [OptionState.Unavailable] and a reason, so the user sees why it is disabled.
 */
object SettingsOptions {
    /** The STT modes the runtime reports; unavailable modes are disabled with a reason. */
    fun sttModes(
        availabilities: List<SttAvailability>,
        selected: com.voicechat.agent.stt.SttMode?,
    ): List<SelectableOption<SttEngine>> =
        availabilities.map { availability ->
            SelectableOption(
                value = availability.engine,
                label = availability.engine.modelDisplayName,
                state = availability.toOptionState(),
                selected = availability.engine.mode == selected,
            )
        }

    /** Every known provider; all are selectable (a provider is capabilities, not a resource). */
    fun providers(
        registry: ProviderCapabilityRegistry,
        selected: ProviderId?,
    ): List<SelectableOption<ProviderCapabilities>> =
        registry.all().map { provider ->
            SelectableOption(
                value = provider,
                label = provider.displayName,
                state = OptionState.Available,
                selected = provider.providerId == selected,
            )
        }

    /** The provider's models and their current availability. */
    fun models(
        availabilities: List<ModelAvailability>,
        selected: ModelId?,
    ): List<SelectableOption<ModelAvailability>> =
        availabilities.map { availability ->
            SelectableOption(
                value = availability,
                label = availability.model.displayName,
                state = availability.toOptionState(),
                selected = availability.model.id == selected,
            )
        }

    /**
     * The auth methods the provider **documents**.
     *
     * The list is exactly `provider.availableAuthMethods`, so an undocumented
     * method (or any QR method, which does not exist in the enum) is never
     * produced. There is no “disabled” entry because the capability is either
     * documented or absent.
     */
    fun authMethods(
        provider: ProviderCapabilities,
        selected: AuthMethod?,
    ): List<SelectableOption<AuthMethod>> =
        provider.availableAuthMethods
            .sortedBy { it.ordinal }
            .map { method ->
                SelectableOption(
                    value = method,
                    label = method.label(),
                    state = OptionState.Available,
                    selected = method == selected,
                )
            }

    /**
     * The reasoning levels supported by **both** the provider and the selected
     * model (the M13 reconciliation), plus always-available “default”.
     *
     * An unsupported level is absent from the list, so it can never be shown or
     * sent (R-0065). With no model selected only the default is offered.
     */
    fun reasoningLevels(
        provider: ProviderCapabilities,
        model: com.voicechat.agent.providers.ModelCapabilities?,
        selected: ReasoningLevel?,
    ): List<SelectableOption<ReasoningLevel>> {
        val effective = LlmCapabilityReconciler.effective(provider, model)
        val supported = effective.reasoningLevels - ReasoningLevel.NONE
        val offered = listOf(ReasoningLevel.NONE) + ReasoningLevel.entries.filter { it in supported }
        return offered.map { level ->
            SelectableOption(
                value = level,
                label = level.label(),
                state = OptionState.Available,
                selected = (selected ?: ReasoningLevel.NONE) == level,
            )
        }
    }

    /** True when the provider+model exposes any level beyond the always-available default. */
    fun hasReasoningChoices(
        provider: ProviderCapabilities,
        model: com.voicechat.agent.providers.ModelCapabilities?,
    ): Boolean = LlmCapabilityReconciler.effective(provider, model).reasoningLevels.any { it != ReasoningLevel.NONE }

    /**
     * The installed **on-device** voices only, restricted to the supported
     * locales.
     *
     * A network-required voice is unsupported (`docs/decisions.md` §2.2), so it is
     * absent rather than shown-as-disabled; when the list is empty the UI reports
     * the explicit “no on-device voice” state. A voice outside
     * [OnDeviceVoiceSelector.SUPPORTED_LANGUAGE_TAGS] is likewise absent: the app
     * only offers a voice whose language it can actually speak.
     */
    fun ttsVoices(
        voices: List<TtsVoice>,
        selectedId: String?,
    ): List<SelectableOption<TtsVoice>> =
        OnDeviceVoiceSelector.supportedVoices(voices).map { voice ->
            SelectableOption(
                value = voice,
                label = "${voice.displayName} (${voice.locale.toLanguageTag()})",
                state = OptionState.Available,
                selected = voice.id == selectedId,
            )
        }

    /** Maps a model availability to its option state; a download is actionable but not selected. */
    private fun ModelAvailability.toOptionState(): OptionState =
        when (this) {
            is ModelAvailability.Ready -> OptionState.Available
            is ModelAvailability.DownloadRequired -> OptionState.Unavailable("Model download required")
            is ModelAvailability.Unavailable -> OptionState.Unavailable(error.detail ?: "Unavailable on this device")
        }

    /** Maps an STT availability to its option state. */
    private fun SttAvailability.toOptionState(): OptionState =
        when (this) {
            is SttAvailability.Ready -> OptionState.Available
            is SttAvailability.DownloadRequired -> OptionState.Unavailable("Model download required")
            is SttAvailability.Downloading -> OptionState.Unavailable("Model is downloading")
            is SttAvailability.Unavailable -> OptionState.Unavailable(error.detail ?: "Unavailable on this device")
        }

    private fun AuthMethod.label(): String =
        when (this) {
            AuthMethod.API_KEY -> "API key"
            AuthMethod.OAUTH_PKCE -> "Sign in with browser (PKCE)"
        }

    private fun ReasoningLevel.label(): String =
        when (this) {
            ReasoningLevel.NONE -> "Provider default"
            ReasoningLevel.MINIMAL -> "Minimal"
            ReasoningLevel.LOW -> "Low"
            ReasoningLevel.MEDIUM -> "Medium"
            ReasoningLevel.HIGH -> "High"
            ReasoningLevel.XHIGH -> "Extra high"
            ReasoningLevel.MAX -> "Max"
        }
}

/** The model capabilities for the selected provider/model, or `null` when unknown. */
fun ModelCapabilityCatalog.capabilitiesFor(
    providerId: ProviderId?,
    modelId: ModelId?,
): com.voicechat.agent.providers.ModelCapabilities? {
    if (providerId == null || modelId == null) return null
    return modelCapabilities(providerId, modelId)
}
