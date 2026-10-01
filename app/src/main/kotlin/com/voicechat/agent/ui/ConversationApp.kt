package com.voicechat.agent.ui

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.voicechat.agent.R
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.providers.ProviderDisclosure
import com.voicechat.agent.ui.theme.OrbIdle
import com.voicechat.agent.ui.theme.OrbListening
import com.voicechat.agent.ui.theme.OrbSpeaking
import com.voicechat.agent.ui.theme.OrbUnavailable
import com.voicechat.agent.ui.theme.OrbWorking
import com.voicechat.agent.ui.theme.VoiceAgentTheme
import com.voicechat.agent.voice.VoiceSessionState
import kotlinx.coroutines.launch

/**
 * Test tags for the conversation surface.
 *
 * Exposed so Compose UI tests locate controls by a stable tag rather than by
 * copy that may change. Every interactive control also has an accessible label.
 */
object ConversationTestTags {
    const val DRAWER = "open-drawer"
    const val DRAWER_NEW_CONVERSATION = "drawer-new-conversation"
    const val NEW_CONVERSATION = "new-conversation"
    const val COMPOSER = "composer"
    const val SEND = "send"
    const val CANCEL = "cancel"
    const val RETRY = "retry"
    const val DELETE_CONVERSATION = "delete-conversation"
    const val CONFIRM_DELETE = "confirm-delete"
    const val CANCEL_DELETE = "cancel-delete"
    const val DISMISS_NOTICE = "dismiss-notice"
    const val TRANSCRIPT = "transcript"
    const val LOADING = "loading"
    const val OPEN_SETTINGS = "open-settings"
    const val VOICE_TOGGLE = "voice-toggle"

    /** Row for [id] in the conversation history drawer. */
    fun conversationRow(id: String): String = "conversation-row-$id"

    /** Delete control for [id] in the conversation history drawer. */
    fun deleteRow(id: String): String = "delete-row-$id"
}

/**
 * Root of the conversation surface.
 *
 * The main content is the chat window — the home surface with the talk control
 * when nothing is open, the open dialog otherwise. History and Settings live in
 * a modal navigation drawer behind the hamburger, so the chat stays the primary
 * surface (the ChatGPT/Gemini pattern) instead of a separate list screen.
 */
@Composable
fun ConversationApp(
    state: ConversationUiState,
    actions: ConversationActions,
    modifier: Modifier = Modifier,
    onOpenSettings: (() -> Unit)? = null,
    onVoiceToggle: (() -> Unit)? = null,
) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val openDrawer: () -> Unit = { scope.launch { drawerState.open() } }
    val closeDrawer: () -> Unit = { scope.launch { drawerState.close() } }

    ModalNavigationDrawer(
        modifier = modifier.fillMaxSize(),
        drawerState = drawerState,
        drawerContent = {
            ConversationDrawer(
                list = state.list,
                onNewConversation = {
                    closeDrawer()
                    actions.onNewConversation()
                },
                onOpenConversation = { id ->
                    closeDrawer()
                    actions.onOpenConversation(id)
                },
                onRequestDelete = actions::onRequestDelete,
                onOpenSettings =
                    onOpenSettings?.let { open ->
                        {
                            closeDrawer()
                            open()
                        }
                    },
            )
        },
    ) {
        when (state.screen) {
            ConversationScreen.LIST -> {
                ConversationHomeScreen(
                    list = state.list,
                    actions = actions,
                    onOpenDrawer = openDrawer,
                    onVoiceToggle = onVoiceToggle,
                )
            }

            ConversationScreen.DIALOG -> {
                ConversationDialogScreen(
                    dialog = requireNotNull(state.dialog),
                    actions = actions,
                    onOpenDrawer = openDrawer,
                    onVoiceToggle = onVoiceToggle,
                )
            }
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

// region drawer

/**
 * History and settings drawer: a new-chat action, the persisted conversations
 * (newest first) with a per-row delete, and the settings entry pinned at the
 * bottom.
 */
@Composable
private fun ConversationDrawer(
    list: ConversationListState,
    onNewConversation: () -> Unit,
    onOpenConversation: (ConversationId) -> Unit,
    onRequestDelete: (ConversationId) -> Unit,
    onOpenSettings: (() -> Unit)?,
) {
    ModalDrawerSheet(
        modifier = Modifier.fillMaxWidth(0.88f),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
        drawerContentColor = MaterialTheme.colorScheme.onSurface,
        drawerTonalElevation = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            Wordmark(
                modifier = Modifier.padding(start = 12.dp, top = 24.dp, bottom = 16.dp),
            )
            DrawerAction(
                label = stringResource(R.string.drawer_new_chat),
                icon = R.drawable.ic_add,
                tag = ConversationTestTags.DRAWER_NEW_CONVERSATION,
                onClick = onNewConversation,
            )
            ListLabel(text = stringResource(R.string.drawer_recent))
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    list.isLoading -> {
                        // No test tag or accessibility label here: the main
                        // surface owns the single LOADING tag, and the drawer is
                        // composed even while it is closed.
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(20.dp),
                            horizontalArrangement = Arrangement.Center,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        }
                    }

                    list.isEmpty -> {
                        Text(
                            text = stringResource(R.string.empty_conversations),
                            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(items = list.summaries, key = { it.id.value }) { summary ->
                                HistoryRow(
                                    summary = summary,
                                    onOpen = { onOpenConversation(summary.id) },
                                    onDelete = { onRequestDelete(summary.id) },
                                    rowTag = ConversationTestTags.conversationRow(summary.id.value),
                                    deleteTag = ConversationTestTags.deleteRow(summary.id.value),
                                )
                            }
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            if (onOpenSettings != null) {
                DrawerAction(
                    label = stringResource(R.string.settings),
                    icon = R.drawable.ic_settings,
                    tag = ConversationTestTags.OPEN_SETTINGS,
                    onClick = onOpenSettings,
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun Wordmark(modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(R.string.brand_wordmark_top),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 3.sp,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.brand_wordmark_accent),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp,
            letterSpacing = 3.sp,
        )
    }
}

@Composable
private fun ListLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(start = 12.dp, top = 18.dp, bottom = 4.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun DrawerAction(
    label: String,
    @DrawableRes icon: Int,
    tag: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 14.dp)
                .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HistoryRow(
    summary: ConversationSummary,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    rowTag: String,
    deleteTag: String,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .clickable(onClick = onOpen)
                .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp)
                .testTag(rowTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = summary.title ?: stringResource(R.string.untitled_conversation),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                text = stringResource(R.string.turn_count, summary.turnCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.testTag(deleteTag)) {
            Icon(
                painter = painterResource(id = R.drawable.ic_delete),
                contentDescription = stringResource(R.string.delete_conversation),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

// endregion

// region home

/**
 * The empty chat surface: the wordmark, the drawer, the talk control, and the
 * hint that text and speech are both available. This is what the app shows on
 * launch and after deleting the open conversation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConversationHomeScreen(
    list: ConversationListState,
    actions: ConversationActions,
    onOpenDrawer: () -> Unit,
    onVoiceToggle: (() -> Unit)?,
) {
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Wordmark() },
                navigationIcon = { DrawerButton(onOpenDrawer) },
            )
        },
        bottomBar = {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // One bottom inset: the keyboard when it is up, otherwise
                        // the navigation bar (the Scaffold's body padding does not
                        // cover the bottom bar itself).
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                        .padding(bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (onVoiceToggle != null) {
                    VoiceOrb(
                        state = VoiceSessionState.IDLE,
                        onClick = onVoiceToggle,
                        size = 104.dp,
                        caption = stringResource(R.string.voice_talk_caption),
                        tag = ConversationTestTags.VOICE_TOGGLE,
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = actions::onNewConversation,
                    modifier = Modifier.testTag(ConversationTestTags.NEW_CONVERSATION),
                ) {
                    Text(stringResource(R.string.home_new_chat))
                }
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
            Box(
                modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (list.isLoading) {
                    LoadingRow(label = stringResource(R.string.loading_conversations))
                } else {
                    WelcomeHint()
                }
            }
        }
    }
}

@Composable
private fun WelcomeHint() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = stringResource(R.string.home_greeting),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.home_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.home_examples),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
        )
    }
}

// endregion

/** One dialog: transcript plus the always-available manual composer. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationDialogScreen(
    dialog: ConversationDialogState,
    actions: ConversationActions,
    modifier: Modifier = Modifier,
    onOpenDrawer: () -> Unit = {},
    onVoiceToggle: (() -> Unit)? = null,
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = dialog.title ?: stringResource(R.string.new_conversation),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { DrawerButton(onOpenDrawer) },
                actions = {
                    IconButton(
                        onClick = { dialog.conversationId?.let(actions::onRequestDelete) },
                        enabled = dialog.conversationId != null,
                        modifier = Modifier.testTag(ConversationTestTags.DELETE_CONVERSATION),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.delete_conversation),
                        )
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                dialog = dialog,
                actions = actions,
                focusRequester = focusRequester,
                onVoiceToggle = onVoiceToggle,
            )
        },
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
            when {
                dialog.isLoading -> {
                    LoadingRow(label = stringResource(R.string.loading_conversation))
                }

                bubbles.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = stringResource(R.string.empty_dialog),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .testTag(ConversationTestTags.TRANSCRIPT),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(items = bubbles, key = { it.key }) { bubble ->
                            val visibleState =
                                remember(bubble.key) {
                                    MutableTransitionState(false).apply { targetState = true }
                                }
                            AnimatedVisibility(
                                visibleState = visibleState,
                                enter = fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 4 },
                            ) {
                                BubbleRow(bubble)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DrawerButton(onOpenDrawer: () -> Unit) {
    val label = stringResource(R.string.drawer_open)
    IconButton(
        onClick = onOpenDrawer,
        modifier =
            Modifier
                .testTag(ConversationTestTags.DRAWER)
                .semantics { contentDescription = label },
    ) {
        Icon(painter = painterResource(id = R.drawable.ic_menu), contentDescription = null)
    }
}

// region composer

@Composable
private fun Composer(
    dialog: ConversationDialogState,
    actions: ConversationActions,
    focusRequester: FocusRequester,
    onVoiceToggle: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (onVoiceToggle != null && dialog.voiceAvailable) {
                VoiceStatusRow(dialog = dialog, onVoiceToggle = onVoiceToggle)
            }
            if (dialog.isGenerating) {
                val generatingLabel = stringResource(R.string.generating)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(
                        modifier =
                            Modifier
                                .size(16.dp)
                                .semantics { contentDescription = generatingLabel },
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = generatingLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    placeholder = {
                        Text(
                            text = stringResource(R.string.message_label),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    shape = RoundedCornerShape(22.dp),
                    maxLines = 4,
                )
                if (onVoiceToggle != null && dialog.voiceAvailable) {
                    Spacer(modifier = Modifier.width(8.dp))
                    VoiceOrb(
                        state = dialog.voiceState,
                        onClick = onVoiceToggle,
                        size = 52.dp,
                        caption = null,
                        tag = ConversationTestTags.VOICE_TOGGLE,
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = actions::onSend,
                    enabled = dialog.canSend,
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                    modifier =
                        Modifier
                            .size(52.dp)
                            .testTag(ConversationTestTags.SEND),
                ) {
                    val sendLabel = stringResource(R.string.send_message)
                    Icon(
                        painter = painterResource(id = R.drawable.ic_send),
                        contentDescription = sendLabel,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
    }
}

/**
 * Live voice state (M24) for the open dialog: the orb caption states what the
 * loop is doing while the orb itself starts/stops hands-free capture.
 */
@Composable
private fun VoiceStatusRow(
    dialog: ConversationDialogState,
    onVoiceToggle: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(dialog.voiceState.voiceStatusRes()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onVoiceToggle) {
            Text(
                text =
                    stringResource(
                        if (dialog.isVoiceActive) R.string.stop_voice else R.string.start_voice,
                    ),
                style = MaterialTheme.typography.labelMedium,
            )
        }
    }
}

private fun VoiceSessionState.voiceStatusRes(): Int =
    when (this) {
        VoiceSessionState.LISTENING -> R.string.voice_listening
        VoiceSessionState.WORKING -> R.string.voice_working
        VoiceSessionState.SPEAKING -> R.string.voice_speaking
        VoiceSessionState.FAILED -> R.string.voice_failed
        VoiceSessionState.IDLE, VoiceSessionState.STOPPED -> R.string.voice_ready
    }

/**
 * Voice orb: the talk control, in the brand orange, reading the live session
 * state through intensity and colour. Tapping starts/stops the voice loop.
 */
@Composable
private fun VoiceOrb(
    state: VoiceSessionState,
    onClick: () -> Unit,
    size: Dp,
    caption: String?,
    tag: String,
) {
    val (core, glow) = orbColors(state)
    val transition = rememberInfiniteTransition(label = "orb")
    val breath by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(3200), RepeatMode.Reverse),
        label = "orb-breath",
    )
    val active = state != VoiceSessionState.IDLE && state != VoiceSessionState.STOPPED
    val bloom = if (active) 1f else 0.5f + breath * 0.14f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier =
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .clickable(onClick = onClick)
                    .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(this.size.width / 2f, this.size.height / 2f)
                val maxRadius = this.size.minDimension / 2f
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(core.copy(alpha = 0.22f), Color.Transparent),
                            center = center,
                            radius = maxRadius,
                        ),
                    radius = maxRadius,
                    center = center,
                )
                val coreRadius = maxRadius * 0.52f * bloom + maxRadius * 0.24f
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(glow.copy(alpha = 0.95f), core.copy(alpha = 0.7f)),
                            center = center,
                            radius = coreRadius,
                        ),
                    radius = coreRadius,
                    center = center,
                )
                val ringRadius = maxRadius * 0.78f
                drawCircle(
                    color = core.copy(alpha = 0.8f),
                    radius = ringRadius,
                    center = center,
                    style = Stroke(width = maxRadius * 0.045f),
                )
            }
        }
        caption?.let { text ->
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun orbColors(state: VoiceSessionState): Pair<Color, Color> =
    when (state) {
        VoiceSessionState.IDLE, VoiceSessionState.STOPPED -> OrbIdle
        VoiceSessionState.LISTENING -> OrbListening
        VoiceSessionState.WORKING -> OrbWorking
        VoiceSessionState.SPEAKING -> OrbSpeaking
        VoiceSessionState.FAILED -> OrbUnavailable
    }

// endregion

// region transcript

@Composable
private fun BubbleRow(bubble: Bubble) {
    val isUser = bubble.role == BubbleRole.USER
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
    ) {
        Surface(
            shape =
                if (isUser) {
                    RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)
                } else {
                    RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp)
                },
            color =
                if (isUser) {
                    MaterialTheme.colorScheme.surfaceVariant
                } else {
                    MaterialTheme.colorScheme.surface
                },
            // A muted bubble surface must not mute the text with it: the bubble
            // body keeps full contrast in both roles.
            contentColor = MaterialTheme.colorScheme.onSurface,
            border =
                if (isUser) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                if (bubble.provisional) {
                    Text(
                        text = stringResource(R.string.provisional_label),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }
                if (bubble.text.isNotEmpty()) {
                    Text(
                        text = bubble.text,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isUser) FontWeight.Medium else FontWeight.Normal,
                    )
                }
                bubble.status?.let { status ->
                    if (bubble.text.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                    Text(
                        text = dialogStatusText(status),
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor(status),
                    )
                }
            }
        }
    }
}

@Composable
private fun statusColor(status: BubbleStatus): Color =
    when (status) {
        BubbleStatus.FAILED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.primary
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
        Text(text = label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DeleteConversationDialog(
    label: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
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
                Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag(ConversationTestTags.CANCEL_DELETE)) {
                Text(stringResource(R.string.cancel))
            }
        },
    )
}

// endregion

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
private fun ConversationHomePreview() {
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
