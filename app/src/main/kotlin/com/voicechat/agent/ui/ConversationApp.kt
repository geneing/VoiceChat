package com.voicechat.agent.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.voicechat.agent.R
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.providers.ProviderDisclosure
import com.voicechat.agent.ui.theme.VoiceAgentTheme

/**
 * Test tags for the conversation surface.
 *
 * Exposed so Compose UI tests locate controls by a stable tag rather than by
 * copy that may change. Every interactive control also has an accessible label.
 */
object ConversationTestTags {
    const val NEW_CONVERSATION = "new-conversation"
    const val COMPOSER = "composer"
    const val SEND = "send"
    const val CANCEL = "cancel"
    const val RETRY = "retry"
    const val BACK = "back"
    const val DELETE_CONVERSATION = "delete-conversation"
    const val CONFIRM_DELETE = "confirm-delete"
    const val CANCEL_DELETE = "cancel-delete"
    const val DISMISS_NOTICE = "dismiss-notice"
    const val TRANSCRIPT = "transcript"
    const val LOADING = "loading"
    const val OPEN_SETTINGS = "open-settings"
    const val PROVIDER_DISCLOSURE = "provider-disclosure"
    const val REMOTE_TRANSFER_NOTICE = "conversation-remote-transfer"
    const val RETENTION_NOTICE = "conversation-retention"
    const val TOOL_EXECUTION_NOTICE = "conversation-tool-execution"

    /** Row for [id] in the conversation list. */
    fun conversationRow(id: String): String = "conversation-row-$id"

    /** Delete control for [id] in the conversation list. */
    fun deleteRow(id: String): String = "delete-row-$id"
}

/** Root of the conversation surface: list, dialog, and the shared delete confirmation. */
@Composable
fun ConversationApp(
    state: ConversationUiState,
    actions: ConversationActions,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
) {
    when (state.screen) {
        ConversationScreen.LIST -> {
            ConversationListScreen(
                list = state.list,
                actions = actions,
                onOpenSettings = onOpenSettings,
                modifier = modifier,
            )
        }

        ConversationScreen.DIALOG -> {
            ConversationDialogScreen(
                dialog = requireNotNull(state.dialog),
                actions = actions,
                modifier = modifier,
            )
        }
    }

    state.pendingDeletion?.let { pending ->
        DeleteConversationDialog(
            label = pending.label,
            onConfirm = actions::onConfirmDelete,
            onDismiss = actions::onDismissDelete,
        )
    }
}

/** History list: new conversation, reopen, and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(
    list: ConversationListState,
    actions: ConversationActions,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.conversations_title)) },
                actions = {
                    if (onOpenSettings != null) {
                        TextButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag(ConversationTestTags.OPEN_SETTINGS),
                        ) {
                            Text(stringResource(R.string.settings))
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = actions::onNewConversation,
                modifier = Modifier.testTag(ConversationTestTags.NEW_CONVERSATION),
            ) {
                Text(stringResource(R.string.new_conversation))
            }
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            list.notice?.let { notice ->
                NoticeBanner(notice = notice, onDismiss = actions::onDismissNotice)
            }
            when {
                list.isLoading -> {
                    LoadingRow(label = stringResource(R.string.loading_conversations))
                }

                list.isEmpty -> {
                    EmptyHint(
                        text = stringResource(R.string.empty_conversations),
                        modifier = Modifier.padding(24.dp),
                    )
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(items = list.summaries, key = { it.id.value }) { summary ->
                            ConversationRow(
                                title = summary.title ?: stringResource(R.string.untitled_conversation),
                                turnCount = summary.turnCount,
                                onOpen = { actions.onOpenConversation(summary.id) },
                                onDelete = { actions.onRequestDelete(summary.id) },
                                rowTag = ConversationTestTags.conversationRow(summary.id.value),
                                deleteTag = ConversationTestTags.deleteRow(summary.id.value),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConversationRow(
    title: String,
    turnCount: Int,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    rowTag: String,
    deleteTag: String,
) {
    ListItem(
        headlineContent = {
            Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(stringResource(R.string.turn_count, turnCount)) },
        trailingContent = {
            TextButton(onClick = onDelete, modifier = Modifier.testTag(deleteTag)) {
                Text(stringResource(R.string.delete))
            }
        },
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .testTag(rowTag),
    )
}

/** One dialog: transcript plus the always-available manual composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDialogScreen(
    dialog: ConversationDialogState,
    actions: ConversationActions,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val bubbles = dialog.toBubbles()

    // Keep the newest content visible as it streams in.
    LaunchedEffect(bubbles.size) {
        if (bubbles.isNotEmpty()) {
            listState.scrollToItem(bubbles.lastIndex)
        }
    }
    // Focus the composer when the conversation opens so typing needs no extra tap.
    LaunchedEffect(dialog.conversationId) {
        if (!dialog.isLoading) {
            focusRequester.requestFocus()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = dialog.title ?: stringResource(R.string.new_conversation),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    TextButton(onClick = actions::onBackToList, modifier = Modifier.testTag(ConversationTestTags.BACK)) {
                        Text(stringResource(R.string.back))
                    }
                },
                actions = {
                    TextButton(
                        onClick = { dialog.conversationId?.let(actions::onRequestDelete) },
                        enabled = dialog.conversationId != null,
                        modifier = Modifier.testTag(ConversationTestTags.DELETE_CONVERSATION),
                    ) {
                        Text(stringResource(R.string.delete))
                    }
                },
            )
        },
        bottomBar = { Composer(dialog = dialog, actions = actions, focusRequester = focusRequester) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            dialog.notice?.let { notice ->
                NoticeBanner(notice = notice, onDismiss = actions::onDismissNotice)
            }
            ProviderDisclosureBanner(provider = dialog.provider)
            when {
                dialog.isLoading -> {
                    LoadingRow(label = stringResource(R.string.loading_conversation))
                }

                bubbles.isEmpty() -> {
                    EmptyHint(
                        text = stringResource(R.string.empty_dialog),
                        modifier = Modifier.padding(24.dp),
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag(ConversationTestTags.TRANSCRIPT),
                    ) {
                        items(items = bubbles, key = { it.key }) { bubble ->
                            BubbleRow(bubble)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Composer(
    dialog: ConversationDialogState,
    actions: ConversationActions,
    focusRequester: FocusRequester,
) {
    Surface(tonalElevation = 3.dp) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (dialog.isGenerating) {
                val generatingLabel = stringResource(R.string.generating)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(16.dp)
                                .semantics { contentDescription = generatingLabel },
                        strokeWidth = 2.dp,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = generatingLabel, style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.weight(1f))
                    TextButton(onClick = actions::onCancel, modifier = Modifier.testTag(ConversationTestTags.CANCEL)) {
                        Text(stringResource(R.string.cancel))
                    }
                }
            } else if (dialog.canRetry) {
                TextButton(onClick = actions::onRetry, modifier = Modifier.testTag(ConversationTestTags.RETRY)) {
                    Text(stringResource(R.string.retry))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = dialog.composerText,
                    onValueChange = actions::onComposerChanged,
                    modifier =
                        Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .testTag(ConversationTestTags.COMPOSER),
                    label = { Text(stringResource(R.string.message_label)) },
                    maxLines = 4,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = actions::onSend,
                    enabled = dialog.canSend,
                    modifier = Modifier.testTag(ConversationTestTags.SEND),
                ) {
                    Text(stringResource(R.string.send))
                }
            }
        }
    }
}

@Composable
private fun BubbleRow(bubble: Bubble) {
    val isUser = bubble.role == BubbleRole.USER
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color =
                if (isUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (bubble.provisional) {
                    Text(
                        text = stringResource(R.string.provisional_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (bubble.text.isNotEmpty()) {
                    Text(text = bubble.text, style = MaterialTheme.typography.bodyLarge)
                }
                bubble.status?.let { status ->
                    Spacer(modifier = Modifier.size(4.dp))
                    Text(
                        text = dialogStatusText(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(
    notice: ConversationNotice,
    onDismiss: () -> Unit,
) {
    val message = noticeText(notice)
    Surface(color = MaterialTheme.colorScheme.errorContainer) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .semantics {
                        liveRegion = LiveRegionMode.Assertive
                        error(message)
                    },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ConversationTestTags.DISMISS_NOTICE)) {
                Text(stringResource(R.string.dismiss))
            }
        }
    }
}

/**
 * Discloses where the next request will go before it is sent (M23, R-0097).
 *
 * It shows the persisted selection's provider/model, the validated destination,
 * the remote text/context-transfer notice, and the provider's retention/training
 * note where the registry records one (R-0139). With nothing selected it shows
 * the honest not-configured hint instead of an empty or misleading line.
 */
@Composable
private fun ProviderDisclosureBanner(provider: ProviderDisclosure) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        if (!provider.hasSelection) {
            Text(
                text = stringResource(R.string.disclosure_not_configured),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ConversationTestTags.PROVIDER_DISCLOSURE),
            )
            return
        }
        Text(
            text = stringResource(R.string.disclosure_provider_model, provider.providerDisplayName!!, provider.modelId!!),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(ConversationTestTags.PROVIDER_DISCLOSURE),
        )
        if (provider.destination != null) {
            Text(
                text = stringResource(R.string.disclosure_requests_go_to, provider.destination),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (provider.remoteTransfer) {
            Text(
                text = stringResource(R.string.disclosure_remote_transfer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ConversationTestTags.REMOTE_TRANSFER_NOTICE),
            )
        }
        provider.retentionNotice?.let { note ->
            Text(
                text = stringResource(R.string.disclosure_retention, note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ConversationTestTags.RETENTION_NOTICE),
            )
        }
        if (provider.toolExecutionOnServer) {
            Text(
                text = stringResource(R.string.disclosure_tool_execution),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag(ConversationTestTags.TOOL_EXECUTION_NOTICE),
            )
        }
    }
}

@Composable
private fun LoadingRow(label: String) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .testTag(ConversationTestTags.LOADING)
                .semantics { contentDescription = label },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label)
    }
}

@Composable
private fun EmptyHint(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.fillMaxWidth(),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DeleteConversationDialog(
    label: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.delete_dialog_title)) },
        text = {
            Text(
                text =
                    stringResource(
                        R.string.delete_dialog_message,
                        label ?: stringResource(R.string.untitled_conversation),
                    ),
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(ConversationTestTags.CONFIRM_DELETE)) {
                Text(stringResource(R.string.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ConversationTestTags.CANCEL_DELETE)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// region bubble model

private enum class BubbleRole { USER, ASSISTANT }

private enum class BubbleStatus { GENERATING, CANCELLED, FAILED, INTERRUPTED }

private data class Bubble(
    val key: String,
    val role: BubbleRole,
    val text: String,
    val provisional: Boolean = false,
    val status: BubbleStatus? = null,
)

/**
 * Flattens committed turns plus live provisional/streaming text into renderable
 * bubbles. Assistant bubbles show delivered text (what the user actually saw);
 * a generated-but-undelivered suffix is never rendered as if it were shown.
 */
private fun ConversationDialogState.toBubbles(): List<Bubble> =
    buildList {
        turns.forEach { turn ->
            when (turn) {
                is UserTurn -> {
                    add(
                        Bubble(
                            key = turn.id.value,
                            role = BubbleRole.USER,
                            text = turn.transcript.text,
                        ),
                    )
                }

                is AssistantTurn -> {
                    val text = turn.delivery.deliveredText.ifEmpty { turn.generated.text }
                    val status =
                        when {
                            turn.generated.state == GenerationState.FAILED -> BubbleStatus.FAILED

                            // Interrupted takes precedence over a bare cancellation: the
                            // reply was cut off after some of it was delivered.
                            turn.delivery.state == DeliveryState.INTERRUPTED -> BubbleStatus.INTERRUPTED

                            turn.generated.state == GenerationState.IN_PROGRESS -> BubbleStatus.GENERATING

                            turn.generated.state == GenerationState.CANCELLED -> BubbleStatus.CANCELLED

                            else -> null
                        }
                    if (text.isNotEmpty() || status != null) {
                        add(Bubble(key = turn.id.value, role = BubbleRole.ASSISTANT, text = text, status = status))
                    }
                }
            }
        }
        provisionalUserText?.takeIf { it.isNotBlank() }?.let { text ->
            add(Bubble(key = "provisional-user", role = BubbleRole.USER, text = text, provisional = true))
        }
        liveAssistantText?.let { text ->
            if (text.isNotEmpty() || phase == TurnPhase.GENERATING) {
                add(Bubble(key = "live-assistant", role = BubbleRole.ASSISTANT, text = text, status = BubbleStatus.GENERATING))
            }
        }
    }

@Composable
private fun dialogStatusText(status: BubbleStatus): String =
    when (status) {
        BubbleStatus.GENERATING -> stringResource(R.string.generating)
        BubbleStatus.CANCELLED -> stringResource(R.string.cancelled)
        BubbleStatus.FAILED -> stringResource(R.string.failed)
        BubbleStatus.INTERRUPTED -> stringResource(R.string.interrupted)
    }

@Composable
private fun noticeText(notice: ConversationNotice): String =
    when (notice) {
        is ConversationNotice.RequestCancelled -> stringResource(R.string.notice_request_cancelled)
        is ConversationNotice.Failure -> stringResource(notice.code.messageRes())
    }

private fun ErrorCode.messageRes(): Int =
    when (this) {
        ErrorCode.LLM_NOT_CONFIGURED -> R.string.notice_llm_not_configured
        ErrorCode.LLM_AUTHENTICATION_FAILED -> R.string.notice_llm_auth_failed
        ErrorCode.LLM_RATE_LIMITED -> R.string.notice_llm_rate_limited
        ErrorCode.LLM_TIMEOUT -> R.string.notice_llm_timeout
        ErrorCode.LLM_NETWORK_FAILED -> R.string.notice_llm_network
        ErrorCode.PERSISTENCE_FAILED -> R.string.notice_persistence_failed
        else -> R.string.notice_generic_error
    }

// endregion

@Preview(showBackground = true)
@Composable
private fun ConversationListPreview() {
    VoiceAgentTheme {
        ConversationApp(
            state =
                ConversationUiState(
                    list =
                        ConversationListState(
                            isLoading = false,
                        ),
                ),
            actions = NoOpConversationActions,
        )
    }
}

/** No-op actions for previews and static rendering. */
object NoOpConversationActions : ConversationActions {
    override fun onNewConversation() = Unit

    override fun onOpenConversation(id: ConversationId) = Unit

    override fun onBackToList() = Unit

    override fun onComposerChanged(text: String) = Unit

    override fun onSend() = Unit

    override fun onCancel() = Unit

    override fun onRetry() = Unit

    override fun onRequestDelete(id: ConversationId) = Unit

    override fun onConfirmDelete() = Unit

    override fun onDismissDelete() = Unit

    override fun onDismissNotice() = Unit
}
