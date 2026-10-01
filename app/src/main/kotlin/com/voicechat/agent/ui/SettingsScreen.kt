package com.voicechat.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.voicechat.agent.R
import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelCatalogState
import com.voicechat.agent.domain.ConnectionState
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.ProviderCapabilities
import com.voicechat.agent.settings.AuthorizationUiState
import com.voicechat.agent.settings.SelectableOption
import com.voicechat.agent.settings.SettingsActions
import com.voicechat.agent.settings.SettingsNotice
import com.voicechat.agent.settings.SettingsUiState
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.tts.TtsVoice

/**
 * Test tags for the settings surface, so UI tests locate controls by a stable
 * tag rather than copy that may change.
 */
object SettingsTestTags {
    const val SCREEN = "settings-screen"
    const val BACK = "settings-back"
    const val NOTICE = "settings-notice"
    const val DISMISS_NOTICE = "settings-dismiss-notice"
    const val REFRESH = "settings-refresh"

    const val CREDENTIAL_FIELD = "settings-credential-field"
    const val CREDENTIAL_SAVE = "settings-credential-save"
    const val CREDENTIAL_REMOVE = "settings-credential-remove"
    const val CREDENTIAL_STATUS = "settings-credential-status"
    const val DESTINATION_FIELD = "settings-destination-field"
    const val DESTINATION_ERROR = "settings-destination-error"
    const val CONNECTION_STATE = "settings-connection-state"
    const val SIGN_IN = "settings-sign-in"
    const val TTS_NO_VOICE = "settings-tts-no-voice"
    const val SMART_TURN_TOGGLE = "settings-smart-turn-toggle"
    const val SMART_TURN_REASON = "settings-smart-turn-reason"

    /** Radio option for an STT mode. */
    fun sttOption(mode: String): String = "settings-stt-$mode"

    /** Disabled reason for an STT mode. */
    fun sttReason(mode: String): String = "settings-stt-reason-$mode"

    /** Radio option for a provider. */
    fun providerOption(id: String): String = "settings-provider-$id"

    /** Radio option for a model. */
    fun modelOption(id: String): String = "settings-model-$id"

    /** Disabled reason for a model. */
    fun modelReason(id: String): String = "settings-model-reason-$id"

    /** The catalog-level notice shown when no model is listed (M27, R-0102). */
    const val MODEL_CATALOG_NOTICE: String = "settings-model-catalog-notice"

    /** Radio option for an auth method. */
    fun authOption(method: String): String = "settings-auth-$method"

    /** Radio option for a reasoning level. */
    fun reasoningOption(level: String): String = "settings-reasoning-$level"

    /** Radio option for a TTS voice. */
    fun ttsOption(id: String): String = "settings-tts-voice-$id"
}

/**
 * The M22 settings surface.
 *
 * It renders only the options the state holder produced: supported options are
 * selectable, known-but-unavailable options are disabled with a reason, and an
 * unsupported capability is simply absent. The remote destination and transfer
 * notice are shown before any text leaves the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    actions: SettingsActions,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize().testTag(SettingsTestTags.SCREEN),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag(SettingsTestTags.BACK)) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = actions::onRefresh, modifier = Modifier.testTag(SettingsTestTags.REFRESH)) {
                        Text("Recheck device")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            state.notice?.let { notice ->
                SettingsNoticeBanner(notice = notice, onDismiss = actions::onDismissNotice)
            }
            if (state.isLoading) {
                CircularProgressIndicator()
                return@Column
            }

            SttSection(state = state, actions = actions)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            LlmSection(state = state, actions = actions)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            TtsSection(state = state, actions = actions)
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            SmartTurnSection(state = state, actions = actions)
        }
    }
}

@Composable
private fun SttSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsHeading("On-device speech-to-text")
    Text(
        text = "Engine: ML Kit GenAI Speech Recognition (one on-device engine; no cloud fallback).",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.stt.options.isEmpty()) {
        Text("No STT engine status is available on this device.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    state.stt.options.forEach { option ->
        OptionRow(
            label = option.label,
            selected = option.isSelected,
            enabled = option.isAvailable,
            tag = SettingsTestTags.sttOption(option.value.mode.name),
            onSelect = { actions.onSelectSttMode(option.value.mode) },
        )
        option.unavailableReason?.let { reason ->
            DisabledReason(text = reason, tag = SettingsTestTags.sttReason(option.value.mode.name))
        }
    }
}

@Composable
private fun LlmSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    val llm = state.llm
    SettingsHeading("Language model")

    Text("Provider", style = MaterialTheme.typography.labelLarge)
    llm.providers.forEach { option ->
        OptionRow(
            label = option.label,
            selected = option.isSelected,
            enabled = option.isAvailable,
            tag = SettingsTestTags.providerOption(option.value.providerId.value),
            onSelect = { actions.onSelectLlmProvider(option.value.providerId) },
        )
    }

    val provider = llm.selectedProviderId
    if (provider == null) {
        Text("Choose a provider to configure a model and connection.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    Text("Model", style = MaterialTheme.typography.labelLarge)
    if (llm.models.isEmpty()) {
        ModelCatalogNotice(
            state = llm.modelCatalogState,
            providerName = llm.providerDisplayName,
            onRefresh = actions::onRefresh,
        )
    } else {
        llm.models.forEach { option: SelectableOption<ModelAvailability> ->
            OptionRow(
                label = option.label,
                selected = option.isSelected,
                enabled = option.isAvailable,
                tag = SettingsTestTags.modelOption(option.value.model.id.value),
                onSelect = { actions.onSelectLlmModel(option.value.model.id) },
            )
            option.unavailableReason?.let { reason ->
                DisabledReason(text = reason, tag = SettingsTestTags.modelReason(option.value.model.id.value))
            }
        }
    }

    Text("Authentication", style = MaterialTheme.typography.labelLarge)
    llm.authMethods.forEach { option ->
        OptionRow(
            label = option.label,
            selected = option.isSelected,
            enabled = option.isAvailable,
            tag = SettingsTestTags.authOption(option.value.name),
            onSelect = { actions.onSelectAuthMethod(option.value) },
        )
        if (option.value == AuthMethod.OAUTH_PKCE && option.isSelected) {
            Button(
                onClick = actions::onBeginProviderSignIn,
                modifier = Modifier.testTag(SettingsTestTags.SIGN_IN),
            ) {
                Text("Sign in with browser")
            }
            AuthorizationState(
                authFlow = llm.authFlow,
            )
        }
    }

    CredentialControls(
        status = llm.credentialStatus,
        providerName = llm.providerDisplayName ?: provider.value,
        onSave = actions::onSaveCredential,
        onRemove = actions::onRemoveCredential,
    )

    ConnectionLine(state = llm.connection)

    if (llm.needsConfiguredDestination) {
        OutlinedTextField(
            value = llm.destinationDraft,
            onValueChange = actions::onDestinationChanged,
            label = { Text("Server address (https for non-local hosts)") },
            isError = llm.destinationError != null,
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.DESTINATION_FIELD),
        )
        llm.destinationError?.let { error ->
            Text(
                text = error,
                modifier = Modifier.testTag(SettingsTestTags.DESTINATION_ERROR).semantics { error(error) },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    Text("Reasoning level", style = MaterialTheme.typography.labelLarge)
    llm.reasoning.forEach { option ->
        OptionRow(
            label = option.label,
            selected = option.value == llm.selectedReasoning,
            enabled = option.isAvailable,
            tag = SettingsTestTags.reasoningOption(option.value.name),
            onSelect = { actions.onSelectReasoningLevel(option.value) },
        )
    }
}

@Composable
private fun CredentialControls(
    status: com.voicechat.agent.credentials.CredentialStatus,
    providerName: String,
    onSave: (String) -> Unit,
    onRemove: () -> Unit,
) {
    var draft by remember { mutableStateOf("") }
    Text("Credential", style = MaterialTheme.typography.labelLarge)
    Text(
        text =
            when (status) {
                is com.voicechat.agent.credentials.CredentialStatus.Stored -> {
                    "A ${status.kind.name.lowercase().replace('_', ' ')} is stored for $providerName."
                }

                is com.voicechat.agent.credentials.CredentialStatus.NotStored -> {
                    "No credential is stored for $providerName."
                }
            },
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(SettingsTestTags.CREDENTIAL_STATUS),
    )
    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        label = { Text("API key") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.CREDENTIAL_FIELD),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(draft)
                draft = ""
            },
            enabled = draft.isNotBlank(),
            modifier = Modifier.testTag(SettingsTestTags.CREDENTIAL_SAVE),
        ) {
            Text(if (status is com.voicechat.agent.credentials.CredentialStatus.Stored) "Replace" else "Save")
        }
        OutlinedButton(
            onClick = onRemove,
            enabled = status is com.voicechat.agent.credentials.CredentialStatus.Stored,
            modifier = Modifier.testTag(SettingsTestTags.CREDENTIAL_REMOVE),
        ) {
            Text("Remove")
        }
    }
}

@Composable
private fun ConnectionLine(state: ConnectionState) {
    val text =
        when (state) {
            is ConnectionState.Connected -> "Connected"
            is ConnectionState.Connecting -> "Connecting…"
            is ConnectionState.Disconnected -> "Ready to connect"
            is ConnectionState.Unavailable -> state.detail ?: "Unavailable"
            is ConnectionState.Failed -> "Connection failed (${state.error.code.name})"
        }
    Text(
        text = "Connection: $text",
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag(SettingsTestTags.CONNECTION_STATE),
    )
}

@Composable
private fun TtsSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsHeading("Text-to-speech")
    Text("On-device voices only; a network voice is never used.", style = MaterialTheme.typography.bodySmall)
    if (state.tts.noOnDeviceVoice) {
        Text(
            text = "No on-device voice is installed for this locale; the app stays text-only.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(SettingsTestTags.TTS_NO_VOICE),
        )
        return
    }
    state.tts.options.forEach { option: SelectableOption<TtsVoice> ->
        OptionRow(
            label = option.label,
            selected = option.isSelected,
            enabled = option.isAvailable,
            tag = SettingsTestTags.ttsOption(option.value.id),
            onSelect = { actions.onSelectTtsVoice(option.value.id) },
        )
    }
}

@Composable
private fun SmartTurnSection(
    state: SettingsUiState,
    actions: SettingsActions,
) {
    SettingsHeading("Turn detection")
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Smart Turn (semantic end-of-turn)",
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
        )
        Switch(
            checked = state.smartTurn.enabled,
            onCheckedChange = actions::onSetSmartTurnEnabled,
            enabled = state.smartTurn.selectable,
            modifier = Modifier.testTag(SettingsTestTags.SMART_TURN_TOGGLE),
        )
    }
    state.smartTurn.unavailableReason?.let { reason ->
        DisabledReason(text = reason, tag = SettingsTestTags.SMART_TURN_REASON)
    }
}

@Composable
private fun SettingsHeading(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun OptionRow(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    tag: String,
    onSelect: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, enabled = enabled, onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled, modifier = Modifier.testTag(tag))
        Spacer(modifier = Modifier.width(8.dp))
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DisabledReason(
    text: String,
    tag: String,
) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 48.dp).testTag(tag),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
}

/**
 * The catalog-level state shown when the provider lists no selectable model
 * (M27, R-0102). It names the reason and, for an empty/failed/unavailable
 * catalog, offers a device recheck instead of presenting a provider as
 * configured. There is no live provider `/models` fetch yet, so the action
 * explicitly rechecks on-device capabilities rather than implying it can
 * recover a provider catalog the app cannot query (CODE_REVIEW P2, R-0160).
 */
@Composable
private fun ModelCatalogNotice(
    state: ModelCatalogState,
    providerName: String?,
    onRefresh: () -> Unit,
) {
    val name = providerName ?: "this provider"
    val message =
        when (state) {
            is ModelCatalogState.Loading -> {
                "Loading the $name model catalog…"
            }

            is ModelCatalogState.Empty -> {
                "No models are available for $name. ${state.reason}"
            }

            is ModelCatalogState.Unavailable -> {
                "No model for $name is selectable yet. ${state.models.firstOrNull()?.let {
                    (it as? ModelAvailability.Unavailable)
                        ?.error
                        ?.detail
                } ?: ""}".trim()
            }

            is ModelCatalogState.Failed -> {
                "The $name model catalog could not be read. ${state.error.detail ?: state.error.code.name}"
            }

            is ModelCatalogState.Stale -> {
                "Showing cached $name models; recheck the device to revalidate."
            }

            is ModelCatalogState.Available -> {
                "No model is selectable."
            }
        }
    Text(
        text = message,
        modifier = Modifier.testTag(SettingsTestTags.MODEL_CATALOG_NOTICE),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (state !is ModelCatalogState.Loading && state !is ModelCatalogState.Available) {
        OutlinedButton(
            onClick = onRefresh,
            modifier = Modifier.padding(top = 4.dp),
        ) {
            Text("Recheck capabilities")
        }
        Text(
            text = "The live provider model list is not connected yet; this only rechecks what this device currently reports.",
            modifier = Modifier.padding(start = 0.dp, top = 4.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AuthorizationState(authFlow: AuthorizationUiState) {
    val text =
        when (authFlow) {
            is AuthorizationUiState.Idle -> "Not started."
            is AuthorizationUiState.AwaitingAuthorization -> "Waiting for authorization at ${authFlow.authorizationUri}."
            is AuthorizationUiState.Completed -> authFlow.message
            is AuthorizationUiState.Rejected -> "Sign-in unavailable (${authFlow.reason.name.lowercase().replace('_', ' ')})."
        }
    Text(text = text, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SettingsNoticeBanner(
    notice: SettingsNotice,
    onDismiss: () -> Unit,
) {
    val message =
        when (notice) {
            is SettingsNotice.Info -> notice.infoText()
            is SettingsNotice.Failure -> notice.message
        }
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = message, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(SettingsTestTags.DISMISS_NOTICE)) {
                Text("Dismiss")
            }
        }
    }
}

private fun SettingsNotice.Info.infoText(): String =
    when (kind) {
        SettingsNotice.Info.InfoKind.CREDENTIAL_STORED -> "Credential saved."
        SettingsNotice.Info.InfoKind.CREDENTIAL_REMOVED -> "Credential removed."
        SettingsNotice.Info.InfoKind.INVALID_SELECTION_CLEARED -> "A saved selection is no longer supported and was cleared."
        SettingsNotice.Info.InfoKind.SIGN_IN_STARTED -> "Authorization started."
    }
