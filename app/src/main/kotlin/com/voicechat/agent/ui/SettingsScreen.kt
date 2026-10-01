package com.voicechat.agent.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.voicechat.agent.settings.SelectableOption
import com.voicechat.agent.settings.SettingsActions
import com.voicechat.agent.settings.SettingsNotice
import com.voicechat.agent.settings.SettingsUiState

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

    /** The action that downloads/installs the pinned Smart Turn artifact. */
    const val SMART_TURN_DOWNLOAD = "settings-smart-turn-download"

    /** The progress row shown while the Smart Turn artifact installs. */
    const val SMART_TURN_PROGRESS = "settings-smart-turn-progress"

    /** The selectable dropdown for an STT model. */
    const val STT_DROPDOWN = "settings-stt-dropdown"

    /** The progress bar shown while the selected STT model downloads. */
    const val STT_DOWNLOAD_PROGRESS = "settings-stt-download-progress"

    /** The action that starts the selected STT model download. */
    const val STT_DOWNLOAD_ACTION = "settings-stt-download-action"

    /** The selectable dropdown for a provider. */
    const val PROVIDER_DROPDOWN = "settings-provider-dropdown"

    /** The selectable dropdown for a model. */
    const val MODEL_DROPDOWN = "settings-model-dropdown"

    /** The selectable dropdown for a reasoning level. */
    const val REASONING_DROPDOWN = "settings-reasoning-dropdown"

    /** The selectable dropdown for a TTS voice. */
    const val TTS_DROPDOWN = "settings-tts-voice-dropdown"

    /** The catalog-level notice shown when no model is listed (M27, R-0102). */
    const val MODEL_CATALOG_NOTICE: String = "settings-model-catalog-notice"

    /** Disabled reason for a model entry inside the dropdown menu. */
    fun modelReason(id: String): String = "settings-model-reason-$id"

    /** A disabled entry inside a dropdown menu, tagged by its value. */
    fun menuOption(value: String): String = "settings-menu-option-$value"
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
        text = "One on-device recognizer; no cloud fallback.",
        style = MaterialTheme.typography.bodySmall,
    )
    if (state.stt.options.isEmpty()) {
        Text("No STT engine status is available on this device.", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val selectable = state.stt.options.filter { it.isAvailable }
    if (selectable.isNotEmpty()) {
        OptionDropdown(
            label = "Recognizer model",
            options = selectable,
            selected = selectable.firstOrNull { it.isSelected },
            tag = SettingsTestTags.STT_DROPDOWN,
            optionTag = { SettingsTestTags.menuOption(it.value.modelId.value) },
            onSelect = { actions.onSelectSttMode(it.value.mode) },
        )
    }

    // The selected-but-unprovisioned model: offer the download, then show its
    // progress. An available model shows nothing extra.
    val selected = state.stt.selectedOption
    if (state.stt.download != null) {
        SttDownloadBar(progress = state.stt.download)
    } else if (selected != null && !selected.isAvailable) {
        val reason = selected.unavailableReason.orEmpty()
        Text(
            text = reason,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag(SettingsTestTags.STT_DOWNLOAD_PROGRESS),
        )
        if (reason.contains("download", ignoreCase = true)) {
            Button(
                onClick = actions::onDownloadSttModel,
                modifier = Modifier.testTag(SettingsTestTags.STT_DOWNLOAD_ACTION),
            ) {
                Text("Download speech model")
            }
        }
    }
}

@Composable
private fun SttDownloadBar(progress: com.voicechat.agent.settings.SttDownloadProgress) {
    Column(modifier = Modifier.testTag(SettingsTestTags.STT_DOWNLOAD_PROGRESS)) {
        Text("Downloading speech model…", style = MaterialTheme.typography.bodySmall)
        Spacer(modifier = Modifier.height(6.dp))
        val fraction = progress.fraction
        if (fraction == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        } else {
            LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
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

    OptionDropdown(
        label = "Provider",
        options = llm.providers,
        selected = llm.providers.firstOrNull { it.isSelected },
        tag = SettingsTestTags.PROVIDER_DROPDOWN,
        optionTag = { SettingsTestTags.menuOption(it.value.providerId.value) },
        onSelect = { actions.onSelectLlmProvider(it.value.providerId) },
    )

    val provider = llm.selectedProviderId
    if (provider == null) {
        Text("Choose a provider to configure a model and connection.", style = MaterialTheme.typography.bodyMedium)
        return
    }

    if (llm.models.isEmpty()) {
        ModelCatalogNotice(
            state = llm.modelCatalogState,
            providerName = llm.providerDisplayName,
            onRefresh = actions::onRefresh,
        )
    } else {
        OptionDropdown(
            label = "Model",
            options = llm.models,
            selected = llm.models.firstOrNull { it.isSelected },
            tag = SettingsTestTags.MODEL_DROPDOWN,
            optionTag = { SettingsTestTags.menuOption(it.value.model.id.value) },
            onSelect = { actions.onSelectLlmModel(it.value.model.id) },
        )
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

    if (llm.reasoning.isNotEmpty()) {
        OptionDropdown(
            label = "Reasoning level",
            options = llm.reasoning,
            selected = llm.reasoning.firstOrNull { it.isSelected },
            tag = SettingsTestTags.REASONING_DROPDOWN,
            optionTag = { SettingsTestTags.menuOption(it.value.name) },
            onSelect = { actions.onSelectReasoningLevel(it.value) },
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
    val stored = status is com.voicechat.agent.credentials.CredentialStatus.Stored
    Text("API key", style = MaterialTheme.typography.labelLarge)
    if (stored) {
        Text(
            text = "Stored for $providerName.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.testTag(SettingsTestTags.CREDENTIAL_STATUS),
        )
    } else {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Paste your API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth().testTag(SettingsTestTags.CREDENTIAL_FIELD),
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!stored) {
            Button(
                onClick = {
                    onSave(draft)
                    draft = ""
                },
                enabled = draft.isNotBlank(),
                modifier = Modifier.testTag(SettingsTestTags.CREDENTIAL_SAVE),
            ) {
                Text("Save")
            }
        }
        OutlinedButton(
            onClick = onRemove,
            enabled = stored,
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
            text =
                "No on-device voice is installed for this locale; the app stays text-only. " +
                    "Install one in system settings to enable speech.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(SettingsTestTags.TTS_NO_VOICE),
        )
        return
    }
    OptionDropdown(
        label = "Voice",
        options = state.tts.options,
        selected = state.tts.options.firstOrNull { it.isSelected },
        tag = SettingsTestTags.TTS_DROPDOWN,
        optionTag = { SettingsTestTags.menuOption(it.value.id) },
        onSelect = { actions.onSelectTtsVoice(it.value.id) },
    )
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
    if (state.smartTurn.installing) {
        Column(modifier = Modifier.testTag(SettingsTestTags.SMART_TURN_PROGRESS)) {
            Text("Downloading Smart Turn model…", style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(6.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
    } else if (state.smartTurn.needsDownload) {
        Text(
            text = "The Smart Turn model is not installed on this device.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag(SettingsTestTags.SMART_TURN_REASON),
        )
        Button(
            onClick = actions::onDownloadSmartTurnModel,
            modifier = Modifier.testTag(SettingsTestTags.SMART_TURN_DOWNLOAD),
        ) {
            Text("Download Smart Turn model")
        }
    } else {
        state.smartTurn.unavailableReason?.let { reason ->
            Text(
                text = reason,
                modifier = Modifier.testTag(SettingsTestTags.SMART_TURN_REASON),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
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

/**
 * A labelled, single-choice dropdown built from capability-aware options.
 *
 * A supported option is selectable; a known-but-unavailable option is listed but
 * disabled with its reason, so an unsupported capability is never shown and an
 * unavailable one is explained rather than hidden. The current selection is the
 * field's displayed value.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> OptionDropdown(
    label: String,
    options: List<SelectableOption<T>>,
    selected: SelectableOption<T>?,
    tag: String,
    optionTag: (SelectableOption<T>) -> String,
    onSelect: (SelectableOption<T>) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Spacer(modifier = Modifier.height(4.dp))
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded },
        ) {
            OutlinedTextField(
                value = selected?.label ?: "Not selected",
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor().testTag(tag),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(option.label) },
                        enabled = option.isAvailable,
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                        modifier = Modifier.testTag(optionTag(option)),
                    )
                    option.unavailableReason?.let { reason ->
                        Text(
                            text = reason,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
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
