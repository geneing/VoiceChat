package com.voicechat.assistant.ui.logs

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicechat.assistant.ui.AppViewModel
import java.text.DateFormat
import java.util.Date

@Composable
fun IssueReportsScreen(viewModel: AppViewModel) {
    val reports by viewModel.issueReportIds.collectAsStateWithLifecycle()
    var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val id = pendingExport
        pendingExport = null
        if (id != null && uri != null) viewModel.exportIssueReport(id, uri)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
    ) {
        item {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Tap the bug button in chat or say “Save an issue report” followed by the details in one voice turn.")
                Text(
                    "Reports stay on this device until you export a ZIP. They can include recent conversation text.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (reports.isEmpty()) Text("No saved reports yet.")
            }
        }
        items(reports, key = { it }) { id ->
            val timestamp = Date(id.substringBefore('-').toLong())
            ListItem(
                headlineContent = { Text(DateFormat.getDateTimeInstance().format(timestamp)) },
                trailingContent = {
                    TextButton(
                        enabled = pendingExport == null,
                        onClick = {
                            pendingExport = id
                            picker.launch("VoiceChat-$id.zip")
                        },
                    ) { Text("Export ZIP") }
                },
            )
        }
    }
}
