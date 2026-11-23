package com.voicechat.assistant.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicechat.assistant.tutor.TutorProgress

@Composable
fun TutorProgressPanel(progress: TutorProgress, onReset: () -> Unit) {
    var showProgress by rememberSaveable { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Mexican Spanish · ${progress.level} · ${progress.vocabulary.size} words",
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { showProgress = true }) { Text("Progress") }
    }
    if (showProgress) AlertDialog(
        onDismissRequest = { showProgress = false },
        title = { Text("Your Spanish · ${progress.level}") },
        text = {
            LazyColumn(Modifier.heightIn(max = 460.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    Text("A selection is sent to the model for lessons and progress reviews. Scores are independent correct answers / attempts.",
                        style = MaterialTheme.typography.bodySmall)
                }
                item { Text(progress.summary.ifBlank { "Starting at A2. Your tutor will adapt as you practice." }) }
                if (progress.levelEvidence.isNotBlank()) item { Text(progress.levelEvidence, style = MaterialTheme.typography.bodySmall) }
                item { Text("Vocabulary", style = MaterialTheme.typography.titleMedium) }
                if (progress.vocabulary.isEmpty()) item { Text("Words appear here after practice.") }
                items(progress.vocabulary.sortedBy { !it.needsWork }) { word ->
                    Text("${word.topic} · ${word.successes}/${word.attempts}" +
                        if (word.note.isNotBlank()) " · ${word.note}" else "")
                }
                item { Text("Grammar", style = MaterialTheme.typography.titleMedium) }
                items(progress.grammar.sortedBy { !it.needsWork }) { point ->
                    Text("${point.topic}: ${point.successes}/${point.attempts} · ${point.note}")
                }
                item { Text("Recent exercises & discussions", style = MaterialTheme.typography.titleMedium) }
                items(progress.activities.takeLast(20).reversed()) { Text(it) }
            }
        },
        confirmButton = { TextButton(onClick = { showProgress = false }) { Text("Done") } },
        dismissButton = { TextButton(onClick = { confirmReset = true }) { Text("Reset progress") } },
    )
    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Reset Spanish progress?") },
        text = { Text("Erase the learning summary, trained vocabulary, grammar and exercise history, and start again at A2. Saved chats and personal facts remain.") },
        confirmButton = { TextButton(onClick = {
            confirmReset = false
            showProgress = false
            onReset()
        }) { Text("Reset") } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
}
