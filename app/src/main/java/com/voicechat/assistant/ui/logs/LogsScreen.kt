package com.voicechat.assistant.ui.logs

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.core.LogEntry
import com.voicechat.assistant.core.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

@Composable
fun LogsScreen() {
    val entries by AppLog.entries.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<Set<LogCategory>>(emptySet()) }
    var query by remember { mutableStateOf("") }

    val filtered = entries
        .asReversed()
        .filter { selected.isEmpty() || it.category in selected }
        .filter { query.isBlank() || it.message.contains(query, ignoreCase = true) || it.detail?.contains(query, ignoreCase = true) == true }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    clipboard?.setPrimaryClip(ClipData.newPlainText("VoiceChat logs", AppLog.export()))
                },
                enabled = entries.isNotEmpty(),
            ) { Text("Copy all") }
            OutlinedButton(onClick = { AppLog.clear() }, enabled = entries.isNotEmpty()) {
                Text("Clear")
            }
            Spacer(Modifier.weight(1f))
            Text(
                "${filtered.size}/${entries.size}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyRow(
            modifier = Modifier.padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(LogCategory.entries.toList()) { category ->
                FilterChip(
                    selected = category in selected,
                    onClick = {
                        selected = if (category in selected) selected - category else selected + category
                    },
                    label = { Text(category.name.lowercase()) },
                )
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Filter text") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        HorizontalDivider(
            modifier = Modifier.padding(top = 8.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(filtered, key = { it.id }) { entry ->
                LogRow(entry)
            }
        }
    }
}

@Composable
private fun LogRow(entry: LogEntry) {
    var expanded by remember(entry.id) { mutableStateOf(false) }
    val levelColor = when (entry.level) {
        LogLevel.ERROR -> MaterialTheme.colorScheme.error
        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
        LogLevel.INFO -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable { expanded = !expanded }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                TIME_FORMAT.format(Date(entry.timestamp)),
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.level.name.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = levelColor,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                entry.category.name.lowercase(),
                style = MaterialTheme.typography.labelSmall,
                color = levelColor,
            )
        }
        Text(
            entry.message,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        val detail = entry.detail
        if (detail != null && expanded) {
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(6.dp),
            )
        }
    }
}
