package com.voicechat.assistant.ui.chat

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Waves
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicechat.assistant.core.Formatters
import com.voicechat.assistant.data.model.Attachment
import com.voicechat.assistant.data.model.AttachmentKind
import com.voicechat.assistant.data.model.Message
import com.voicechat.assistant.data.model.MessageKind
import com.voicechat.assistant.data.model.MessageRole
import com.voicechat.assistant.realtime.LiveStatus
import com.voicechat.assistant.realtime.SessionUiState
import com.voicechat.assistant.ui.StreamingMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun MessageBubble(message: Message, showMetrics: Boolean) {
    when (message.role) {
        MessageRole.TOOL -> ToolBubble(message.text)
        MessageRole.SYSTEM -> SystemRow(message.text)
        else -> {
            val fromUser = message.role == MessageRole.USER
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start,
            ) {
                Column(
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .clip(
                            RoundedCornerShape(
                                topStart = 20.dp,
                                topEnd = 20.dp,
                                bottomStart = if (fromUser) 20.dp else 6.dp,
                                bottomEnd = if (fromUser) 6.dp else 20.dp,
                            ),
                        )
                        .background(
                            if (fromUser) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        )
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    if (message.attachments.isNotEmpty()) {
                        AttachmentStrip(message.attachments)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        text = message.text,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (fromUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                    if (!fromUser) {
                        val voice = message.kind == MessageKind.VOICE
                        if (voice) {
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.GraphicEq,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    "spoken answer",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        if (showMetrics && (message.totalTokens > 0 || message.ttftMs > 0)) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = buildString {
                                    if (message.ttftMs > 0) append("TTFT ${Formatters.durationMs(message.ttftMs)}")
                                    if (message.totalTokens > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${Formatters.tokens(message.totalTokens)} tokens")
                                    }
                                    if (message.costUsd > 0.0) {
                                        if (isNotEmpty()) append(" · ")
                                        append(Formatters.cost(message.costUsd))
                                    }
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StreamingBubble(streaming: StreamingMessage) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 6.dp, bottomEnd = 20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = streaming.text.ifBlank { "…" },
                style = MaterialTheme.typography.bodyLarge,
            )
            if (streaming.voice) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.GraphicEq,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "speaking…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolBubble(text: String) {
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    "Tool call",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SystemRow(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 4.dp),
        )
    }
}

@Composable
fun AttachmentStrip(attachments: List<Attachment>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        attachments.forEach { attachment ->
            if (attachment.kind == AttachmentKind.IMAGE) {
                ImageAttachment(attachment)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        attachment.name,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ImageAttachment(attachment: Attachment) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(
        initialValue = null,
        key1 = attachment.path,
    ) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(attachment.path, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = bitmap
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = attachment.name,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 200.dp)
                .clip(RoundedCornerShape(12.dp)),
            contentScale = ContentScale.Crop,
        )
    } else {
        Text(attachment.name, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun LiveCard(
    state: SessionUiState,
    onInterrupt: () -> Unit,
    onStopLive: () -> Unit,
) {
    val active = state.status == LiveStatus.LISTENING ||
        state.status == LiveStatus.RESPONDING ||
        state.status == LiveStatus.CONNECTING
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(22.dp),
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Waveform(
                    level = state.micLevel,
                    active = active,
                    modifier = Modifier
                        .width(88.dp)
                        .height(36.dp),
                    colorStart = MaterialTheme.colorScheme.primary,
                    colorEnd = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (state.status) {
                            LiveStatus.CONNECTING -> "Connecting…"
                            LiveStatus.LISTENING -> "Listening…"
                            LiveStatus.THINKING -> "Thinking…"
                            LiveStatus.RESPONDING -> "Responding…"
                            LiveStatus.ERROR -> "Connection problem"
                            LiveStatus.IDLE -> "Voice mode paused"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val subtitle = when {
                        state.error != null -> state.error
                        state.status == LiveStatus.LISTENING -> "Speak naturally — talk over the answer to interrupt."
                        state.status == LiveStatus.RESPONDING -> "Reply in progress. Start talking to barge in."
                        state.status == LiveStatus.THINKING -> "Waiting for the first token."
                        else -> state.model.orEmpty()
                    }
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (state.isBusy) {
                    IconButton(onClick = onInterrupt) {
                        Icon(Icons.Default.Stop, contentDescription = "Interrupt")
                    }
                }
                IconButton(onClick = onStopLive) {
                    Icon(Icons.Default.Close, contentDescription = "Stop voice mode")
                }
            }
            if (state.userPartial.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "“${state.userPartial}”",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun Composer(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onAddPhoto: () -> Unit,
    onAddCamera: () -> Unit,
    onAddDocument: () -> Unit,
    pendingAttachments: List<Attachment>,
    onRemoveAttachment: (Attachment) -> Unit,
    liveEnabled: Boolean,
    onToggleLive: () -> Unit,
    enabled: Boolean,
) {
    var addMenuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(26.dp),
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 6.dp, vertical = 6.dp)) {
            if (pendingAttachments.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(pendingAttachments, key = { it.path }) { attachment ->
                        Surface(
                            color = MaterialTheme.colorScheme.surface,
                            shape = RoundedCornerShape(12.dp),
                        ) {
                            Row(
                                modifier = Modifier.padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    attachment.name,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    modifier = Modifier.widthIn(max = 160.dp),
                                    overflow = TextOverflow.Ellipsis,
                                )
                                IconButton(
                                    onClick = { onRemoveAttachment(attachment) },
                                    modifier = Modifier.size(28.dp),
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Remove attachment",
                                        modifier = Modifier.size(14.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Box {
                    IconButton(onClick = { addMenuOpen = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add attachment")
                    }
                    DropdownMenu(
                        expanded = addMenuOpen,
                        onDismissRequest = { addMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Photo") },
                            leadingIcon = { Icon(Icons.Default.Image, contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                onAddPhoto()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Camera") },
                            leadingIcon = { Icon(Icons.Default.PhotoCamera, contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                onAddCamera()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Document") },
                            leadingIcon = { Icon(Icons.Default.Description, contentDescription = null) },
                            onClick = {
                                addMenuOpen = false
                                onAddDocument()
                            },
                        )
                    }
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp, vertical = 10.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 6,
                    enabled = enabled,
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    "Message VoiceChat",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                val canSend = (value.isNotBlank() || pendingAttachments.isNotEmpty()) && enabled
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (canSend) MaterialTheme.colorScheme.primary
                            else Color.Transparent,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(
                            Icons.Default.ArrowUpward,
                            contentDescription = "Send",
                            tint = if (canSend) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(
                            if (liveEnabled) {
                                Brush.linearGradient(
                                    listOf(
                                        MaterialTheme.colorScheme.primary,
                                        MaterialTheme.colorScheme.secondary,
                                    ),
                                )
                            } else {
                                Brush.linearGradient(
                                    listOf(Color.Transparent, Color.Transparent),
                                )
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(onClick = onToggleLive, enabled = enabled) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = if (liveEnabled) "Stop voice mode" else "Start voice mode",
                            tint = if (liveEnabled) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ConnectionDot(connected: Boolean, active: Boolean) {
    val color = when {
        connected -> Color(0xFF45C486)
        active -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
fun EmptyState(onSuggestion: (String) -> Unit, onLive: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(
                            MaterialTheme.colorScheme.primary,
                            MaterialTheme.colorScheme.secondary,
                        ),
                    ),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Waves,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.onPrimary,
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("Hi, I'm VoiceChat", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Talk hands-free or type. Both share one conversation.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(22.dp))
        listOf(
            "What's happening in the world today?",
            "What time is it, and what's the weather?",
            "Explain quantum entanglement like I'm five.",
        ).forEach { suggestion ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier
                    .padding(vertical = 4.dp)
                    .clickable { onSuggestion(suggestion) },
            ) {
                Text(
                    suggestion,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            "Tap the sound wave for a live conversation",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onLive),
        )
    }
}
