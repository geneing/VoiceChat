package com.voicechat.assistant.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.voicechat.assistant.ui.chat.ChatScreen
import com.voicechat.assistant.ui.chat.ConnectionDot
import com.voicechat.assistant.ui.drawer.ChatDrawer
import com.voicechat.assistant.ui.logs.LogsScreen
import com.voicechat.assistant.ui.metrics.UsageDialog
import com.voicechat.assistant.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

private enum class Route { CHAT, SETTINGS, LOGS }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: AppViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    var routeName by rememberSaveable { mutableStateOf(Route.CHAT.name) }
    val route = Route.entries.firstOrNull { it.name == routeName } ?: Route.CHAT

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val chats by viewModel.chats.collectAsStateWithLifecycle()
    val currentChat by viewModel.currentChat.collectAsStateWithLifecycle()
    val currentChatId by viewModel.currentChatId.collectAsStateWithLifecycle()
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val usage by viewModel.usageTotals.collectAsStateWithLifecycle()
    val stats by viewModel.conversationStats.collectAsStateWithLifecycle()

    var showUsage by rememberSaveable { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        viewModel.uiEvents.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            viewModel.startLive()
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("Microphone permission is required for voice mode.")
            }
        }
    }

    val requestLive: () -> Unit = {
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) viewModel.startLive() else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    BackHandler(enabled = route != Route.CHAT) { routeName = Route.CHAT.name }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                chats = chats,
                currentChatId = currentChatId,
                connected = session.connected,
                modelLabel = settings.modelInfo.label,
                usage = usage,
                onNewChat = {
                    viewModel.newChat()
                    routeName = Route.CHAT.name
                    scope.launch { drawerState.close() }
                },
                onSelectChat = { chatId ->
                    viewModel.selectChat(chatId)
                    routeName = Route.CHAT.name
                    scope.launch { drawerState.close() }
                },
                onDeleteChat = viewModel::deleteChat,
                onRenameChat = viewModel::renameChat,
                onOpenSettings = {
                    routeName = Route.SETTINGS.name
                    scope.launch { drawerState.close() }
                },
                onOpenLogs = {
                    routeName = Route.LOGS.name
                    scope.launch { drawerState.close() }
                },
                onOpenUsage = {
                    showUsage = true
                    scope.launch { drawerState.close() }
                },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                when (route) {
                    Route.CHAT -> CenterAlignedTopAppBar(
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = currentChat?.title ?: "VoiceChat",
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (session.active) {
                                    Text(
                                        text = statusLine(session.status.name, session.transport?.label, session.connectMs),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                    )
                                }
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open chats")
                            }
                        },
                        actions = {
                            ConnectionDot(
                                connected = session.connected,
                                active = session.active,
                            )
                            Spacer(Modifier.width(4.dp))
                            IconButton(onClick = { routeName = Route.SETTINGS.name }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )

                    Route.SETTINGS, Route.LOGS -> CenterAlignedTopAppBar(
                        title = {
                            Text(if (route == Route.SETTINGS) "Settings" else "Debug logs")
                        },
                        navigationIcon = {
                            IconButton(onClick = { routeName = Route.CHAT.name }) {
                                Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                            }
                        },
                        colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background,
                        ),
                    )
                }
            },
        ) { padding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) {
                when (route) {
                    Route.CHAT -> ChatScreen(
                        viewModel = viewModel,
                        onOpenSettings = { routeName = Route.SETTINGS.name },
                        onRequestLive = requestLive,
                    )

                    Route.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        onOpenLogs = { routeName = Route.LOGS.name },
                        onOpenUsage = { showUsage = true },
                    )

                    Route.LOGS -> LogsScreen()
                }
            }
        }
    }

    if (showUsage) {
        UsageDialog(
            totals = usage,
            stats = stats,
            onDismiss = { showUsage = false },
            onReset = { viewModel.resetUsage() },
        )
    }
}

private fun statusLine(status: String, transport: String?, connectMs: Long): String {
    val prefix = when (status) {
        "CONNECTING" -> "Connecting"
        "LISTENING" -> "Listening"
        "THINKING" -> "Thinking"
        "RESPONDING" -> "Responding"
        "ERROR" -> "Error"
        else -> "Idle"
    }
    return buildString {
        append(prefix)
        if (transport != null) append(" · ").append(transport)
        if (connectMs > 0) append(" · ").append(connectMs).append(" ms")
    }
}
