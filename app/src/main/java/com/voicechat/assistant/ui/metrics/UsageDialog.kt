package com.voicechat.assistant.ui.metrics

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.voicechat.assistant.core.Formatters
import com.voicechat.assistant.data.model.ConversationStats
import com.voicechat.assistant.data.model.UsageTotals
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UsageDialog(
    totals: UsageTotals,
    stats: ConversationStats,
    onDismiss: () -> Unit,
    onReset: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Usage & cost") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text("This conversation", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                StatLine("Turns", stats.turns.toString())
                StatLine("Input tokens", Formatters.tokens(stats.inputTokens))
                StatLine("Output tokens", Formatters.tokens(stats.outputTokens))
                StatLine("Total tokens", Formatters.tokens(stats.inputTokens + stats.outputTokens))
                StatLine("Last TTFT", Formatters.durationMs(stats.lastTtftMs))
                StatLine("Average TTFT", Formatters.durationMs(stats.averageTtftMs))
                StatLine("Estimated cost", Formatters.cost(stats.costUsd))
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 12.dp),
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Text("Since billing start", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                StatLine("Input tokens", Formatters.tokens(totals.inputTokens.toInt()))
                StatLine("Output tokens", Formatters.tokens(totals.outputTokens.toInt()))
                StatLine(
                    "Total tokens",
                    Formatters.tokens((totals.inputTokens + totals.outputTokens).toInt()),
                )
                StatLine("Estimated cost", Formatters.cost(totals.costUsd))
                StatLine(
                    "Billing start",
                    if (totals.billingStartMs > 0) {
                        SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(Date(totals.billingStartMs))
                    } else {
                        "—"
                    },
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Token counts come from response.done usage. Cost is an estimate using the " +
                        "per-million prices in Settings; Model Studio does not expose per-key billing here.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
        dismissButton = {
            TextButton(onClick = onReset) { Text("Reset counters") }
        },
    )
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
