package com.voicechat.agent.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelCatalogState
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.CredentialStatus
import com.voicechat.agent.credentials.CredentialStore
import com.voicechat.agent.credentials.CredentialStoreOutcome
import com.voicechat.agent.domain.ConnectionState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.EndpointSource
import com.voicechat.agent.providers.EndpointValidation
import com.voicechat.agent.providers.ModelCapabilityCatalog
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderConnectionState
import com.voicechat.agent.providers.ProviderEndpointPolicy
import com.voicechat.agent.providers.ServerDestinationValidator
import com.voicechat.agent.settings.AuthorizationOutcome
import com.voicechat.agent.settings.AuthorizationRejection
import com.voicechat.agent.settings.AuthorizationUiState
import com.voicechat.agent.settings.EmptyModelCapabilityCatalog
import com.voicechat.agent.settings.LlmSettingsSection
import com.voicechat.agent.settings.ProviderAuthFlow
import com.voicechat.agent.settings.SettingsActions
import com.voicechat.agent.settings.SettingsCapabilities
import com.voicechat.agent.settings.SettingsCapabilityProvider
import com.voicechat.agent.settings.SettingsNotice
import com.voicechat.agent.settings.SettingsOptions
import com.voicechat.agent.settings.SettingsStore
import com.voicechat.agent.settings.SettingsUiState
import com.voicechat.agent.settings.SettingsValidator
import com.voicechat.agent.settings.SmartTurnSettingsSection
import com.voicechat.agent.settings.SttSettingsSection
import com.voicechat.agent.settings.TtsSettingsSection
import com.voicechat.agent.settings.UnimplementedProviderAuthFlow
import com.voicechat.agent.settings.VoiceSettings
import com.voicechat.agent.stt.SttMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/**
 * Lifecycle-aware state holder for the M22 settings surface.
 *
 * It is the single seam between Compose and the capability catalogs. It:
 * - reads the persisted [VoiceSettings] **after** the first runtime snapshot and
 *   re-validates them against the registry and that snapshot, so an option the
 *   device no longer supports is dropped and re-saved rather than silently
 *   trusted (and a valid option is never cleared just because the snapshot had
 *   not loaded yet);
 * - builds only supported options ([SettingsOptions]) — an undocumented auth
 *   method or an unsupported reasoning level is absent, while a known but
 *   currently unavailable mode/model/voice is disabled with a reason;
 * - stores/replaces/removes the provider credential through the M13
 *   [CredentialStore] and never puts a secret in [SettingsUiState];
 * - exposes the validated destination and the remote-transfer disclosure before
 *   any text leaves the device.
 *
 * Credential values never appear in the UI state, log lines, or persisted
 * settings; only the redacted [CredentialStatus] does.
 */
class SettingsViewModel(
    private val store: SettingsStore,
    private val registry: ProviderCapabilityRegistry,
    private val credentials: CredentialStore,
    private val capabilityProvider: SettingsCapabilityProvider,
    private val modelCatalog: ModelCapabilityCatalog = EmptyModelCapabilityCatalog,
    private val authFlow: ProviderAuthFlow = UnimplementedProviderAuthFlow,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    scope: CoroutineScope? = null,
) : ViewModel(),
    SettingsActions {
    private val externalScope = scope
    private val _uiState = MutableStateFlow(SettingsUiState())

    /** Observable, immutable UI state. */
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    private var current: VoiceSettings = VoiceSettings.EMPTY
    private var capabilities: SettingsCapabilities = SettingsCapabilities.EMPTY
    private var credentialStatus: CredentialStatus = CredentialStatus.NotStored
    private var notice: SettingsNotice? = null
    private var destinationDraft: String = ""
    private var destinationError: String? = null
    private var authFlowState: AuthorizationUiState = AuthorizationUiState.Idle
    private var closed: Boolean = false

    private val startupJob: Job =
        coroutineScope().launch(dispatcher) {
            capabilities = readCapabilities()
            store
                .observe()
                .catch { failure ->
                    AppLog.w(failure) { "settings: observe failed" }
                    notice = SettingsNotice.Failure(ErrorCode.PERSISTENCE_FAILED, "Settings could not be read.")
                    rebuild()
                }.collect { stored -> install(stored, persistCorrections = true) }
        }

    private fun coroutineScope(): CoroutineScope = externalScope ?: viewModelScope

    // region SettingsActions

    override fun onSelectSttMode(mode: SttMode) {
        update(current.copy(sttMode = mode))
    }

    override fun onSelectLlmProvider(providerId: ProviderId) {
        val provider = registry.capabilities(providerId)
        AppLog.d { "settings: select provider id=${providerId.value} known=${provider != null}" }
        // Changing the provider clears the provider-specific sub-selections; the
        // validator would also drop them, but clearing here keeps the intent clear.
        val configurable = provider?.transport?.configurable == true
        update(
            current.copy(
                llmProviderId = providerId,
                llmModelId = null,
                llmAuthMethod = null,
                reasoningLevel = null,
                llmServerUrl = if (configurable) current.llmServerUrl else null,
            ),
            reloadCredential = true,
        )
        destinationDraft = if (configurable) current.llmServerUrl.orEmpty() else ""
        destinationError = null
        authFlowState = AuthorizationUiState.Idle
        rebuild()
    }

    override fun onSelectLlmModel(modelId: ModelId) {
        update(current.copy(llmModelId = modelId, reasoningLevel = null))
    }

    override fun onSelectAuthMethod(method: AuthMethod) {
        update(current.copy(llmAuthMethod = method))
    }

    override fun onSelectReasoningLevel(level: ReasoningLevel) {
        update(current.copy(reasoningLevel = if (level == ReasoningLevel.NONE) null else level))
    }

    override fun onSelectTtsVoice(voiceId: String) {
        update(current.copy(ttsVoiceId = voiceId))
    }

    override fun onSetSmartTurnEnabled(enabled: Boolean) {
        update(current.copy(smartTurnEnabled = enabled))
    }

    override fun onDestinationChanged(text: String) {
        destinationDraft = text
        val provider = current.llmProviderId?.let { registry.capabilities(it) } ?: return
        if (provider.transport.configurable != true) return
        when (val validated = ServerDestinationValidator.validate(text, EndpointSource.USER_ENTERED)) {
            is EndpointValidation.Valid -> {
                destinationError = null
                // Persist the trimmed, validated text; the disclosure is derived from it.
                update(current.copy(llmServerUrl = text.trim()))
            }

            is EndpointValidation.Invalid -> {
                // Do not persist an invalid destination; keep the draft and explain.
                destinationError = validated.error.detail ?: "The server address is not valid."
                rebuild()
            }
        }
    }

    override fun onSaveCredential(secret: String) {
        val provider = current.llmProviderId?.let { registry.capabilities(it) }
        if (provider == null) {
            notice = SettingsNotice.Failure(ErrorCode.CREDENTIAL_STORAGE_FAILED, "Choose a provider first.")
            rebuild()
            return
        }
        if (secret.isBlank()) {
            notice = SettingsNotice.Failure(ErrorCode.CREDENTIAL_STORAGE_FAILED, "Enter a credential value.")
            rebuild()
            return
        }
        val kind = credentialKind(provider, current.llmAuthMethod)
        coroutineScope().launch(dispatcher) {
            val outcome =
                try {
                    credentials.store(Credential(providerId = provider.providerId, kind = kind, secret = secret))
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    AppLog.w(failure) { "settings: credential store failed" }
                    CredentialStoreOutcome.Failed(VoiceAgentError(ErrorCode.CREDENTIAL_STORAGE_FAILED))
                }
            applyCredentialOutcome(outcome, stored = true)
        }
    }

    override fun onRemoveCredential() {
        val providerId = current.llmProviderId ?: return
        coroutineScope().launch(dispatcher) {
            val outcome =
                try {
                    credentials.remove(providerId)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    AppLog.w(failure) { "settings: credential remove failed" }
                    CredentialStoreOutcome.Failed(VoiceAgentError(ErrorCode.CREDENTIAL_STORAGE_FAILED))
                }
            applyCredentialOutcome(outcome, stored = false)
        }
    }

    override fun onBeginProviderSignIn() {
        val provider = current.llmProviderId?.let { registry.capabilities(it) } ?: return
        val method = current.llmAuthMethod ?: provider.availableAuthMethods.firstOrNull() ?: return
        coroutineScope().launch(dispatcher) {
            val outcome =
                try {
                    authFlow.begin(provider, method)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (failure: Throwable) {
                    AppLog.w(failure) { "settings: auth flow failed" }
                    AuthorizationOutcome.Rejected(AuthorizationRejection.NOT_IMPLEMENTED)
                }
            authFlowState = outcome.toUiState()
            notice =
                if (outcome is AuthorizationOutcome.AwaitingAuthorization) {
                    SettingsNotice.Info(SettingsNotice.Info.InfoKind.SIGN_IN_STARTED)
                } else {
                    null
                }
            rebuild()
        }
    }

    override fun onRefresh() {
        coroutineScope().launch(dispatcher) {
            capabilities = readCapabilities()
            val validation = SettingsValidator.validate(current, registry, capabilities, modelCatalog)
            current = validation.settings
            if (!validation.isValid) {
                notice = SettingsNotice.Info(SettingsNotice.Info.InfoKind.INVALID_SELECTION_CLEARED)
                runCatching { store.save(validation.settings) }
            }
            credentialStatus = readCredentialStatus(current.llmProviderId)
            rebuild()
        }
    }

    override fun onDismissNotice() {
        notice = null
        rebuild()
    }

    // endregion

    /** Loads a stored value, corrects invalid selections, and re-saves if needed. */
    private suspend fun install(
        stored: VoiceSettings,
        persistCorrections: Boolean,
    ) {
        if (closed) return
        val validation = SettingsValidator.validate(stored, registry, capabilities, modelCatalog)
        current = validation.settings
        if (destinationDraft.isBlank()) {
            destinationDraft = validation.settings.llmServerUrl.orEmpty()
        }
        if (!validation.isValid) {
            notice = SettingsNotice.Info(SettingsNotice.Info.InfoKind.INVALID_SELECTION_CLEARED)
        }
        if (persistCorrections && validation.settings != stored) {
            runCatching { store.save(validation.settings) }
        }
        credentialStatus = readCredentialStatus(validation.settings.llmProviderId)
        rebuild()
    }

    /** Validates [candidate], persists the sanitized result, and refreshes state. */
    private fun update(
        candidate: VoiceSettings,
        reloadCredential: Boolean = false,
    ) {
        val validation = SettingsValidator.validate(candidate, registry, capabilities, modelCatalog)
        current = validation.settings
        if (!validation.isValid) {
            notice = SettingsNotice.Info(SettingsNotice.Info.InfoKind.INVALID_SELECTION_CLEARED)
        }
        coroutineScope().launch(dispatcher) {
            runCatching { store.save(current) }
            if (reloadCredential) {
                credentialStatus = readCredentialStatus(current.llmProviderId)
            }
            rebuild()
        }
    }

    private suspend fun readCapabilities(): SettingsCapabilities =
        try {
            capabilityProvider.snapshot()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AppLog.w(failure) { "settings: capability snapshot failed" }
            SettingsCapabilities.EMPTY
        }

    private suspend fun readCredentialStatus(providerId: ProviderId?): CredentialStatus {
        if (providerId == null) return CredentialStatus.NotStored
        return try {
            credentials.status(providerId)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AppLog.w(failure) { "settings: credential status failed" }
            CredentialStatus.NotStored
        }
    }

    private fun applyCredentialOutcome(
        outcome: CredentialStoreOutcome,
        stored: Boolean,
    ) {
        notice =
            when (outcome) {
                is CredentialStoreOutcome.Success -> {
                    SettingsNotice.Info(
                        if (stored) {
                            SettingsNotice.Info.InfoKind.CREDENTIAL_STORED
                        } else {
                            SettingsNotice.Info.InfoKind.CREDENTIAL_REMOVED
                        },
                    )
                }

                is CredentialStoreOutcome.NotStored -> {
                    SettingsNotice.Info(SettingsNotice.Info.InfoKind.CREDENTIAL_REMOVED)
                }

                is CredentialStoreOutcome.Failed -> {
                    SettingsNotice.Failure(
                        outcome.error.code,
                        outcome.error.detail ?: "The credential could not be saved.",
                    )
                }
            }
        coroutineScope().launch(dispatcher) {
            credentialStatus = readCredentialStatus(current.llmProviderId)
            rebuild()
        }
    }

    /** Recomputes the immutable UI state from the current fields. */
    private fun rebuild() {
        val provider = current.llmProviderId?.let { registry.capabilities(it) }
        val modelCapabilities = readModelCapabilities(current)
        val endpoint = provider?.let { ProviderEndpointPolicy.destinationFor(it, current.llmServerUrl) }
        val disclosure = (endpoint as? EndpointValidation.Valid)?.destination?.disclosure()

        val stt =
            SttSettingsSection(
                options = SettingsOptions.sttModes(capabilities.sttAvailability, current.sttMode),
                selectedMode = current.sttMode,
                localeLanguageTag = current.sttLocaleLanguageTag ?: VoiceSettings.DEFAULT_LANGUAGE_TAG,
            )

        val providerModels =
            provider
                ?.let { selected -> capabilities.models.filter { it.model.providerId == selected.providerId } }
                ?: emptyList()
        val catalogState =
            if (provider == null) {
                ModelCatalogState.Empty("Choose a provider to list its models.")
            } else {
                ModelCatalogState.of(
                    models = providerModels,
                    emptyReason = "No models are wired for this provider yet. Recheck the device, or choose another provider.",
                )
            }

        val llm =
            LlmSettingsSection(
                providers = SettingsOptions.providers(registry, current.llmProviderId),
                selectedProviderId = current.llmProviderId,
                providerDisplayName = provider?.displayName,
                models = SettingsOptions.models(providerModels, current.llmModelId),
                modelCatalogState = catalogState,
                selectedModelId = current.llmModelId,
                authMethods = provider?.let { SettingsOptions.authMethods(it, current.llmAuthMethod) } ?: emptyList(),
                selectedAuthMethod = current.llmAuthMethod,
                reasoning = provider?.let { SettingsOptions.reasoningLevels(it, modelCapabilities, current.reasoningLevel) } ?: emptyList(),
                selectedReasoning = current.effectiveReasoningLevel,
                connection = provider?.let { ProviderConnectionState.derive(it, credentialStatus) } ?: ConnectionState.Disconnected,
                credentialStatus = credentialStatus,
                destinationDisclosure = disclosure,
                remoteTransfer = provider != null,
                toolExecutionOnServer = provider?.toolExecutionOnServer == true,
                retentionNotice = provider?.dataRetentionNote,
                destinationDraft = destinationDraft,
                destinationError = destinationError,
                needsConfiguredDestination = provider?.transport?.configurable == true,
                authFlow = authFlowState,
            )

        val tts =
            TtsSettingsSection(
                options = SettingsOptions.ttsVoices(capabilities.ttsVoices, current.ttsVoiceId),
                selectedVoiceId = current.ttsVoiceId,
            )

        val smartTurn = SmartTurnSettingsSection(enabled = current.smartTurnEnabled, state = capabilities.smartTurn)

        _uiState.value =
            SettingsUiState(
                isLoading = false,
                stt = stt,
                llm = llm,
                tts = tts,
                smartTurn = smartTurn,
                notice = notice,
            )
    }

    private fun readModelCapabilities(settings: VoiceSettings) =
        if (settings.llmProviderId != null && settings.llmModelId != null) {
            modelCatalog.modelCapabilities(settings.llmProviderId, settings.llmModelId)
        } else {
            null
        }

    /** Cancels in-flight work; called by [onCleared] and by tests. Idempotent. */
    fun shutdown() {
        closed = true
        startupJob.cancel()
    }

    override fun onCleared() {
        shutdown()
    }

    private fun credentialKind(
        provider: ProviderCapabilities,
        method: AuthMethod?,
    ): CredentialKind =
        when (method ?: provider.auth.credentialKind.toAuthMethod()) {
            AuthMethod.OAUTH_PKCE -> CredentialKind.OAUTH_ACCESS_TOKEN
            AuthMethod.API_KEY -> CredentialKind.API_KEY
        }

    private fun CredentialKind.toAuthMethod(): AuthMethod =
        when (this) {
            CredentialKind.API_KEY -> AuthMethod.API_KEY
            CredentialKind.OAUTH_ACCESS_TOKEN -> AuthMethod.OAUTH_PKCE
        }

    private fun AuthorizationOutcome.toUiState(): AuthorizationUiState =
        when (this) {
            is AuthorizationOutcome.AwaitingAuthorization -> {
                AuthorizationUiState.AwaitingAuthorization(
                    session.authorizationUri,
                    session.expiresAtEpochMillis,
                )
            }

            is AuthorizationOutcome.Completed -> {
                AuthorizationUiState.Completed("Authorized; finish connecting in a later step.")
            }

            is AuthorizationOutcome.Rejected -> {
                AuthorizationUiState.Rejected(reason)
            }
        }
}

/** Builds the production [SettingsViewModel] for the app's Compose tree. */
fun settingsViewModelFactory(
    store: SettingsStore,
    registry: ProviderCapabilityRegistry,
    credentials: CredentialStore,
    capabilityProvider: SettingsCapabilityProvider,
    modelCatalog: ModelCapabilityCatalog = EmptyModelCapabilityCatalog,
    authFlow: ProviderAuthFlow = UnimplementedProviderAuthFlow,
): ViewModelProvider.Factory =
    viewModelFactory {
        initializer {
            SettingsViewModel(
                store = store,
                registry = registry,
                credentials = credentials,
                capabilityProvider = capabilityProvider,
                modelCatalog = modelCatalog,
                authFlow = authFlow,
            )
        }
    }
