package com.voicechat.assistant.ui.memory

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicechat.assistant.memory.MemoryFact
import com.voicechat.assistant.ui.AppViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The facts the assistant remembers, with the controls to correct or delete
 * them. Memory is device-only, so this screen is the whole truth about what the
 * model can be told.
 */
@Composable
fun MemoryScreen(viewModel: AppViewModel) {
    val facts by viewModel.memoryFacts.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<MemoryFact?>(null) }
    var confirmErase by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Remembered facts", style = MaterialTheme.typography.titleMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (facts.isEmpty()) {
                                "Nothing yet. Facts are learned from what you say — your " +
                                    "name, where you live, preferences, people, routines " +
                                    "and projects — and reused in later chats."
                            } else {
                                "${facts.size} fact${if (facts.size == 1) "" else "s"} " +
                                    "stored on this device."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "Facts never leave the phone as storage, but the ones used in " +
                                "a turn are sent to the model as prompt text, like any other " +
                                "instruction. Passwords, keys and card numbers are never " +
                                "stored.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!settings.memoryEnabled) {
                            Text(
                                "Memory is off in Settings, so facts are kept but never used.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        OutlinedButton(
                            onClick = { confirmErase = true },
                            enabled = facts.isNotEmpty(),
                        ) { Text("Erase all") }
                    }
                }
            }
        }

        items(facts, key = { it.id }) { fact ->
            FactRow(
                fact = fact,
                onEdit = { editing = fact },
                onDelete = { viewModel.deleteMemory(fact) },
            )
        }
    }

    editing?.let { fact ->
        EditFactDialog(
            fact = fact,
            onDismiss = { editing = null },
            onSave = { text ->
                viewModel.updateMemory(fact, text)
                editing = null
            },
        )
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text("Erase all memory?") },
            text = {
                Text(
                    "Every remembered fact is deleted from this device. Chats are not " +
                        "affected.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.clearMemory()
                        confirmErase = false
                    },
                ) { Text("Erase") }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun FactRow(
    fact: MemoryFact,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    fact.text,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    buildString {
                        append(fact.kind.name.lowercase().replaceFirstChar { it.uppercase() })
                        if (fact.pinned) append(" · pinned")
                        append(" · ")
                        append(dateLabel(fact.updatedAt))
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onEdit) {
                Icon(Icons.Default.Edit, contentDescription = "Edit fact")
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete fact")
            }
        }
    }
}

@Composable
private fun EditFactDialog(
    fact: MemoryFact,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(fact.id) { mutableStateOf(fact.text) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit fact") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Fact") },
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(text) },
                enabled = text.isNotBlank(),
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun dateLabel(timestamp: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(timestamp))
