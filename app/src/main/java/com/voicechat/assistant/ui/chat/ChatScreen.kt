package com.voicechat.assistant.ui.chat

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicechat.assistant.realtime.LiveStatus
import com.voicechat.assistant.ui.AppViewModel
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(
    viewModel: AppViewModel,
    onOpenSettings: () -> Unit,
    onRequestLive: () -> Unit,
) {
    val context = LocalContext.current
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val streaming by viewModel.streaming.collectAsStateWithLifecycle()
    val session by viewModel.sessionState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pending by viewModel.pendingAttachments.collectAsStateWithLifecycle()
    val chatId by viewModel.currentChatId.collectAsStateWithLifecycle()

    var input by rememberSaveable(chatId) { mutableStateOf("") }
    var cameraFile by remember { mutableStateOf<java.io.File?>(null) }
    val listState = rememberLazyListState()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? -> uri?.let(viewModel::attachUri) }

    val documentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? -> uri?.let(viewModel::attachUri) }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { success ->
        if (success) cameraFile?.let(viewModel::attachCameraPhoto)
        cameraFile = null
    }

    val itemCount = messages.size + if (streaming != null) 1 else 0
    LaunchedEffect(itemCount, streaming?.text?.length) {
        if (itemCount > 0) {
            runCatching { listState.scrollToItem(itemCount - 1) }
            // A second pass catches items inserted while the first scroll was
            // still being measured (tool rows arrive between answer chunks).
            kotlinx.coroutines.delay(120)
            runCatching { listState.scrollToItem(itemCount - 1) }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding(),
    ) {
        val error = session.error
        if (error != null) {
            Surface(
                color = MaterialTheme.colorScheme.errorContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { viewModel.dismissError() }) { Text("Dismiss") }
                }
            }
        }

        if (!settings.hasApiKey) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Row(
                    modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "No Qwen API key configured. Add one in Settings.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onOpenSettings) { Text("Open") }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 10.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty() && streaming == null) {
                item(key = "empty") {
                    EmptyState(
                        onSuggestion = { suggestion -> viewModel.sendText(suggestion) },
                        onLive = onRequestLive,
                    )
                }
            }
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message, showMetrics = settings.showMetricsInChat)
            }
            if (streaming != null) {
                item(key = "streaming") {
                    StreamingBubble(streaming!!)
                }
            }
        }

        val showLive = session.active &&
            (session.liveEnabled || session.isBusy || session.status == LiveStatus.CONNECTING)
        if (showLive) {
            LiveCard(
                state = session,
                onInterrupt = viewModel::interrupt,
                onStopLive = viewModel::stopLive,
            )
        }

        Composer(
            value = input,
            onValueChange = { input = it },
            onSend = {
                if (input.isNotBlank() || pending.isNotEmpty()) {
                    viewModel.sendText(input)
                    input = ""
                    keyboard?.hide()
                }
            },
            onAddPhoto = {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            },
            onAddCamera = {
                runCatching {
                    val (uri, file) = viewModel.createCameraTarget()
                    cameraFile = file
                    cameraLauncher.launch(uri)
                }.onFailure {
                    viewModel.showMessage("No camera app is available on this device.")
                }
            },
            onAddDocument = {
                documentPicker.launch(arrayOf("*/*"))
            },
            pendingAttachments = pending,
            onRemoveAttachment = viewModel::removeAttachment,
            liveEnabled = session.liveEnabled,
            onToggleLive = {
                if (session.liveEnabled) {
                    viewModel.stopLive()
                } else {
                    onRequestLive()
                }
            },
            enabled = true,
        )
        Spacer(Modifier.height(2.dp))
    }
}
