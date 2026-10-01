package com.voicechat.agent.settings

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelCatalogState
import com.voicechat.agent.credentials.CredentialStatus
import com.voicechat.agent.domain.ConnectionState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.tts.TtsVoice

/** A user-visible, non-sensitive settings notice. */
sealed interface SettingsNotice {
    /** A completed, non-error action. */
    data class Info(
        val kind: InfoKind,
    ) : SettingsNotice {
        enum class InfoKind {
            CREDENTIAL_STORED,
            CREDENTIAL_REMOVED,
            INVALID_SELECTION_CLEARED,
            SIGN_IN_STARTED,
        }
    }

    /** A failure; [message] is a safe, stable explanation (never a secret). */
    data class Failure(
        val code: ErrorCode,
        val message: String,
        val retryable: Boolean = code.defaultRetryable,
    ) : SettingsNotice
}

/** The auth-flow state shown in the LLM section. */
sealed interface AuthorizationUiState {
    data object Idle : AuthorizationUiState

    /** A short-lived, single-use authorization is waiting on the provider. */
    data class AwaitingAuthorization(
        val authorizationUri: String,
        val expiresAtEpochMillis: Long,
    ) : AuthorizationUiState

    /** The provider authorized the attempt; credential exchange is M14+/M23 work. */
    data class Completed(
        val message: String,
    ) : AuthorizationUiState

    /** The attempt was refused; [reason] says why. */
    data class Rejected(
        val reason: AuthorizationRejection,
    ) : AuthorizationUiState
}

/** STT section: the single engine's modes and their runtime availability. */
data class SttSettingsSection(
    val options: List<SelectableOption<SttEngine>> = emptyList(),
    val selectedMode: SttMode? = null,
    val localeLanguageTag: String = VoiceSettings.DEFAULT_LANGUAGE_TAG,
    /**
     * The in-flight download for the currently selected-but-unprovisioned mode,
     * or `null`. The UI shows a determinate progress bar from this while the
     * system-managed model provisions; there is nothing to show once the mode is
     * available.
     */
    val download: SttDownloadProgress? = null,
) {
    /** True when at least one mode is ready now. */
    val hasReadyMode: Boolean get() = options.any { it.isAvailable }

    /** The option for the currently selected mode, or `null`. */
    val selectedOption: SelectableOption<SttEngine>?
        get() = selectedMode?.let { mode -> options.firstOrNull { it.value.mode == mode } }
}

/**
 * Progress of the user-approved STT model download shown in settings (M08).
 *
 * [bytesToDownload] is the expected size, or 0 when the API did not report one;
 * [bytesDownloaded] is what has arrived so far. The UI derives a determinate bar
 * only when [bytesToDownload] is positive, and an indeterminate one otherwise.
 */
data class SttDownloadProgress(
    val bytesDownloaded: Long,
    val bytesToDownload: Long,
) {
    /** The completed fraction in `[0, 1]`, or `null` when the total is unknown. */
    val fraction: Float?
        get() = if (bytesToDownload > 0L) (bytesDownloaded.toDouble() / bytesToDownload.toDouble()).toFloat().coerceIn(0f, 1f) else null
}

/** TTS section: installed embedded voices only. */
data class TtsSettingsSection(
    val options: List<SelectableOption<TtsVoice>> = emptyList(),
    val selectedVoiceId: String? = null,
) {
    /** True when no embedded voice is installed; the app stays text-only. */
    val noOnDeviceVoice: Boolean get() = options.isEmpty()
}

/** LLM section: provider, model, auth, reasoning, connection, and disclosure. */
data class LlmSettingsSection(
    val providers: List<SelectableOption<ProviderCapabilities>> = emptyList(),
    val selectedProviderId: ProviderId? = null,
    val providerDisplayName: String? = null,
    val models: List<SelectableOption<ModelAvailability>> = emptyList(),
    /**
     * The catalog state for [models] (M27, R-0102). [ModelCatalogState.Available]
     * means a model can be selected; [ModelCatalogState.Empty]/[Failed] carry the
     * reason and recovery action the UI shows instead of a falsely "configured"
     * provider.
     */
    val modelCatalogState: ModelCatalogState = ModelCatalogState.INITIAL,
    val selectedModelId: ModelId? = null,
    val authMethods: List<SelectableOption<AuthMethod>> = emptyList(),
    val selectedAuthMethod: AuthMethod? = null,
    val reasoning: List<SelectableOption<ReasoningLevel>> = emptyList(),
    val selectedReasoning: ReasoningLevel = ReasoningLevel.NONE,
    val connection: ConnectionState = ConnectionState.Disconnected,
    val credentialStatus: CredentialStatus = CredentialStatus.NotStored,
    /** The configurable server address, for a provider that has one (Hermes). */
    val destinationDraft: String = "",
    val destinationError: String? = null,
    /** True when the selected provider needs a user-configured destination. */
    val needsConfiguredDestination: Boolean = false,
    val authFlow: AuthorizationUiState = AuthorizationUiState.Idle,
)

/** Smart Turn section: on by default when the detector is installed. */
data class SmartTurnSettingsSection(
    val enabled: Boolean = false,
    val state: SmartTurnState = SmartTurnState.Unavailable("Smart Turn is not available"),
) {
    /** True only when the detector is actually installed and selectable. */
    val selectable: Boolean get() = state is SmartTurnState.Available

    /** The reason the toggle is disabled, or `null` when selectable. */
    val unavailableReason: String?
        get() =
            when (val s = state) {
                is SmartTurnState.Unavailable -> s.reason
                is SmartTurnState.DownloadRequired -> "Smart Turn model download required"
                is SmartTurnState.Available -> null
            }
}

/**
 * Immutable UI state for the settings surface (M22).
 *
 * It contains only app-facing types: option lists already filtered by capability,
 * the current validated selection, connection/credential state, and the
 * remote-transfer disclosure. No credential value, transcript, or provider SDK
 * type ever appears here.
 */
data class SettingsUiState(
    val isLoading: Boolean = true,
    val stt: SttSettingsSection = SttSettingsSection(),
    val llm: LlmSettingsSection = LlmSettingsSection(),
    val tts: TtsSettingsSection = TtsSettingsSection(),
    val smartTurn: SmartTurnSettingsSection = SmartTurnSettingsSection(),
    val notice: SettingsNotice? = null,
)

/**
 * User intents from the settings UI to the state holder.
 *
 * The Compose tree depends on this interface, never on a ViewModel, so the
 * screen stays decoupled. Every selection action must be validated before it is
 * persisted; the actions themselves encode only the user's intent.
 */
interface SettingsActions {
    /** Selects an STT mode (ignored unless it is available). */
    fun onSelectSttMode(mode: SttMode)

    /**
     * Starts the user-approved download of the selected, unprovisioned STT model
     * (M08). A no-op when the mode is already available or already downloading.
     */
    fun onDownloadSttModel()

    /** Selects the remote provider; clears the model/auth/reasoning selections. */
    fun onSelectLlmProvider(providerId: ProviderId)

    /** Selects a model offered by the current provider. */
    fun onSelectLlmModel(modelId: ModelId)

    /** Selects a documented auth method for the current provider. */
    fun onSelectAuthMethod(method: AuthMethod)

    /** Selects a reasoning level supported by the current provider+model. */
    fun onSelectReasoningLevel(level: ReasoningLevel)

    /** Selects an installed embedded TTS voice. */
    fun onSelectTtsVoice(voiceId: String)

    /** Enables or disables Smart Turn (only persisted when it is available). */
    fun onSetSmartTurnEnabled(enabled: Boolean)

    /** Updates the configurable destination draft; validated before it is stored. */
    fun onDestinationChanged(text: String)

    /** Stores or replaces the API key for the current provider. */
    fun onSaveCredential(secret: String)

    /** Removes the current provider's stored credential. */
    fun onRemoveCredential()

    /** Begins the provider's documented browser/device authorization, if any. */
    fun onBeginProviderSignIn()

    /** Re-reads runtime capabilities, credential status, and revalidates. */
    fun onRefresh()

    /** Dismisses the current notice. */
    fun onDismissNotice()
}
