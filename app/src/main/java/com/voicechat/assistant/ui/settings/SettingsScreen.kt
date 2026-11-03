package com.voicechat.assistant.ui.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicechat.assistant.BuildConfig
import com.voicechat.assistant.core.Formatters
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.core.RealtimeDefaults
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.data.prefs.AppSettings
import com.voicechat.assistant.ui.AppViewModel
import kotlinx.coroutines.delay

private data class PromptPreset(val label: String, val text: String)

private val PROMPT_PRESETS = listOf(
    PromptPreset(
        "General assistant",
        RealtimeDefaults.DEFAULT_INSTRUCTIONS,
    ),
    PromptPreset(
        "Concise voice companion",
        "You are a warm, quick-witted voice companion. Keep answers under three " +
            "sentences, speak naturally, and ask one short follow-up question when it helps.",
    ),
    PromptPreset(
        "Language tutor",
        "You are a patient language tutor. Answer in the language the user practices, " +
            "gently correct mistakes by repeating the sentence correctly, and offer one new " +
            "useful phrase per turn.",
    ),
    PromptPreset(
        "Technical expert",
        "You are a senior software engineer. Be precise and concrete, mention trade-offs, " +
            "and prefer short code snippets or step lists. Ask for missing context instead of guessing.",
    ),
)

@Composable
fun SettingsScreen(
    viewModel: AppViewModel,
    onOpenLogs: () -> Unit,
    onOpenUsage: () -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val usage by viewModel.usageTotals.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SettingsSection("Connection", "How the app reaches Qwen") {
                Text(
                    "Transport",
                    style = MaterialTheme.typography.labelLarge,
                )
                TransportKind.entries.forEach { kind ->
                    val supported = kind in settings.modelInfo.supportedTransports
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = supported) {
                                viewModel.updateSettings { it.copy(transport = kind) }
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = settings.transport == kind,
                            onClick = {
                                viewModel.updateSettings { it.copy(transport = kind) }
                            },
                            enabled = supported,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                kind.label,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (supported) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                if (supported) {
                                    kind.detail
                                } else {
                                    "${kind.detail} Not supported by ${settings.modelInfo.label}."
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                DropdownSetting(
                    label = "Model",
                    value = settings.modelInfo.label,
                    options = RealtimeDefaults.models.map { it.label to it.id },
                    onSelect = { id ->
                        val info = RealtimeDefaults.models.firstOrNull { it.id == id }
                        val supportsTools = info?.supportsTools == true
                        viewModel.updateSettings { current ->
                            current.copy(
                                model = id,
                                voice = info?.defaultVoice ?: current.voice,
                                turnDetection = info?.defaultTurnDetection
                                    ?: current.turnDetection,
                                toolsEnabled = current.toolsEnabled && supportsTools,
                                nativeSearch = current.nativeSearch && supportsTools,
                            )
                        }
                    },
                )
                Text(
                    settings.modelInfo.description,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                DropdownSetting(
                    label = "Voice",
                    value = settings.modelInfo.resolveVoice(settings.voice),
                    options = settings.modelInfo.voices.map { it to it },
                    onSelect = { voice -> viewModel.updateSettings { it.copy(voice = voice) } },
                )
                Text(
                    "Default: ${settings.modelInfo.defaultVoice} · " +
                        "${settings.modelInfo.voices.size} system voices for this model. " +
                        "An unsupported voice falls back to the default.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                DebouncedTextField(
                    initial = settings.workspaceId,
                    label = "Workspace ID",
                    placeholder = "ws-…",
                    onCommit = { value -> viewModel.updateSettings { it.copy(workspaceId = value.trim()) } },
                )
                DebouncedTextField(
                    initial = settings.host,
                    label = "API host",
                    placeholder = "ws-….ap-southeast-1.maas.aliyuncs.com",
                    onCommit = { value -> viewModel.updateSettings { it.copy(host = value.trim()) } },
                )
                DebouncedTextField(
                    initial = settings.apiKeyOverride,
                    label = "API key override",
                    placeholder = if (BuildConfig.QWEN_API_KEY.isNotBlank()) {
                        "Embedded key is configured"
                    } else {
                        "sk-…"
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    onCommit = { value -> viewModel.updateSettings { it.copy(apiKeyOverride = value.trim()) } },
                )
                Text(
                    text = when {
                        settings.apiKeyOverride.isNotBlank() -> "Using the key entered above."
                        BuildConfig.QWEN_API_KEY.isNotBlank() -> "Using the key embedded at build time."
                        else -> "No key configured. Enter one above."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (settings.transport == TransportKind.AOQ) {
                    DebouncedTextField(
                        initial = settings.aoqTokenUrl,
                        label = "AOQ token service URL (optional)",
                        placeholder = "https://your-appserver.example/aoq-token",
                        onCommit = { value -> viewModel.updateSettings { it.copy(aoqTokenUrl = value.trim()) } },
                    )
                    Text(
                        "Leave blank to perform the documented token exchange in-app with the " +
                            "API key. Set an HTTPS AppServer to keep the key off the device; " +
                            "it must return the same aoqTokenForClient/sid/relay JSON.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = "Endpoint: ${viewModel.endpointPreview()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::testConnection) {
                        Icon(Icons.Default.Speed, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Test connection")
                    }
                }
            }
        }

        item {
            SettingsSection("Conversation", "Prompt and turn taking") {
                DropdownSetting(
                    label = "Prompt preset",
                    value = "Apply a preset…",
                    options = PROMPT_PRESETS.map { it.label to it.label },
                    onSelect = { label ->
                        PROMPT_PRESETS.firstOrNull { it.label == label }?.let { preset ->
                            viewModel.updateSettings { it.copy(instructions = preset.text) }
                        }
                    },
                )
                DebouncedTextField(
                    initial = settings.instructions,
                    label = "System prompt",
                    singleLine = false,
                    minLines = 6,
                    onCommit = { value -> viewModel.updateSettings { it.copy(instructions = value) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            viewModel.updateSettings {
                                it.copy(instructions = RealtimeDefaults.DEFAULT_INSTRUCTIONS)
                            }
                        },
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("Reset prompt")
                    }
                }
                DebouncedTextField(
                    initial = settings.languageHint,
                    label = "Preferred language (optional)",
                    placeholder = "e.g. Ukrainian, English",
                    onCommit = { value -> viewModel.updateSettings { it.copy(languageHint = value.trim()) } },
                )
                DropdownSetting(
                    label = "Turn detection",
                    value = settings.turnDetection,
                    options = settings.modelInfo.turnDetectionOptions.map { it to it },
                    onSelect = { value -> viewModel.updateSettings { it.copy(turnDetection = value) } },
                )
                Text(
                    if (settings.modelInfo.id == RealtimeDefaults.AUDIO_MODEL) {
                        "smart_turn_v2 is recommended for Qwen Audio: it combines " +
                            "acoustics with semantics and ignores filler and echo. " +
                            "The app also runs the platform echo canceller in voice mode."
                    } else {
                        "semantic_vad filters filler sounds. semantic_vad_ec also makes " +
                            "barge-in easier."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SliderSetting(
                    label = "VAD threshold",
                    valueText = String.format(java.util.Locale.US, "%.2f", settings.vadThreshold),
                    value = settings.vadThreshold,
                    min = 0.05f,
                    max = 1f,
                    step = 0.05f,
                    onCommit = { value -> viewModel.updateSettings { it.copy(vadThreshold = value) } },
                )
                if (!settings.turnDetection.startsWith("smart_turn")) {
                    SliderSetting(
                        label = "Silence before reply",
                        valueText = "${settings.silenceMs} ms",
                        value = settings.silenceMs.toFloat(),
                        min = 200f,
                        max = 2000f,
                        step = 100f,
                        onCommit = { value ->
                            viewModel.updateSettings { it.copy(silenceMs = value.toInt()) }
                        },
                    )
                } else {
                    Text(
                        "Smart turn detection decides when you have finished speaking; " +
                            "no fixed silence timer is used.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SwitchSetting(
                    title = "Transcribe my voice",
                    subtitle = "Shows your speech as text in the chat.",
                    checked = settings.inputTranscription,
                    onCheckedChange = { value ->
                        viewModel.updateSettings { it.copy(inputTranscription = value) }
                    },
                )
                SwitchSetting(
                    title = "Client tools",
                    subtitle = if (settings.modelInfo.supportsTools) {
                        "Date/time, location and web search through function calling."
                    } else {
                        "Not supported by ${settings.modelInfo.label}."
                    },
                    checked = settings.toolsEnabled && !settings.nativeSearch && settings.modelInfo.supportsTools,
                    enabled = settings.modelInfo.supportsTools && !settings.nativeSearch,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(toolsEnabled = value) } },
                )
                SwitchSetting(
                    title = "Native web search",
                    subtitle = "Server-side search. Cannot be combined with client tools.",
                    checked = settings.nativeSearch && settings.modelInfo.supportsTools,
                    enabled = settings.modelInfo.supportsTools,
                    onCheckedChange = { value ->
                        viewModel.updateSettings {
                            it.copy(nativeSearch = value, toolsEnabled = if (value) false else it.toolsEnabled)
                        }
                    },
                )
                SwitchSetting(
                    title = "Include search sources",
                    subtitle = "Ask the server for source links when native search is on.",
                    checked = settings.searchSources,
                    enabled = settings.nativeSearch,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(searchSources = value) } },
                )
            }
        }

        item {
            SettingsSection("Tools", "Device capabilities the model can use") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Location access", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (locationGranted) "Granted — the model can read your last known location."
                            else "Not granted — get_location returns an error.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!locationGranted) {
                        OutlinedButton(
                            onClick = {
                                locationLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION,
                                    ),
                                )
                            },
                        ) { Text("Grant") }
                    }
                }
                Text(
                    "web_search uses DuckDuckGo and needs no key. Native search is a " +
                        "server-side alternative available on Qwen3.8 Omni.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item {
            SettingsSection("Metrics & cost", "Latency, tokens and estimates") {
                Text(
                    "Input price / 1M tokens (USD)",
                    style = MaterialTheme.typography.labelLarge,
                )
                DebouncedTextField(
                    initial = formatPrice(settings.priceInputPerMillion),
                    label = "Input price",
                    keyboardType = KeyboardType.Decimal,
                    onCommit = { value ->
                        viewModel.updateSettings { it.copy(priceInputPerMillion = value.toDoubleOrNull() ?: 0.0) }
                    },
                )
                DebouncedTextField(
                    initial = formatPrice(settings.priceOutputPerMillion),
                    label = "Output price",
                    keyboardType = KeyboardType.Decimal,
                    onCommit = { value ->
                        viewModel.updateSettings { it.copy(priceOutputPerMillion = value.toDoubleOrNull() ?: 0.0) }
                    },
                )
                Text(
                    "Cost is estimated from response usage. Enter the current Model Studio " +
                        "prices; with 0 it shows tokens only.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchSetting(
                    title = "Show metrics in chat",
                    subtitle = "TTFT, tokens and cost under each answer.",
                    checked = settings.showMetricsInChat,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(showMetricsInChat = value) } },
                )
                Text(
                    "Lifetime: ${Formatters.tokens((usage.inputTokens + usage.outputTokens).toInt())} tokens · " +
                        Formatters.cost(usage.costUsd),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenUsage) { Text("Usage details") }
                    OutlinedButton(onClick = viewModel::resetUsage) { Text("Reset counters") }
                }
            }
        }

        item {
            SettingsSection("Debug", "Protocol and audio tracing") {
                SwitchSetting(
                    title = "Log event payloads",
                    subtitle = "Dumps protocol events with binary payloads redacted.",
                    checked = settings.protocolDump,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(protocolDump = value) } },
                )
                SwitchSetting(
                    title = "Write log file",
                    subtitle = "Rotating app.log in app storage (1 MB × 2).",
                    checked = settings.fileLogging,
                    onCheckedChange = { value -> viewModel.updateSettings { it.copy(fileLogging = value) } },
                )
                Text("Categories", style = MaterialTheme.typography.labelLarge)
                Column {
                    LogCategory.entries.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { category ->
                                FilterChip(
                                    selected = category in settings.debugCategories,
                                    onClick = {
                                        viewModel.updateSettings { current ->
                                            val next = current.debugCategories.toMutableSet()
                                            if (category in next) next.remove(category) else next.add(category)
                                            current.copy(debugCategories = next)
                                        }
                                    },
                                    label = { Text(category.name.lowercase()) },
                                )
                            }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenLogs) {
                        Icon(Icons.Default.Terminal, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Open logs")
                    }
                    OutlinedButton(onClick = viewModel::clearLogs) { Text("Clear logs") }
                }
            }
        }

        item {
            SettingsSection("About", null) {
                Text(
                    "VoiceChat ${BuildConfig.VERSION_NAME} · Qwen realtime",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Single session for text and voice. Audio answers are paired with a " +
                        "live transcript. Barge-in mutes playback instantly.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (settings.apiKey.isBlank()) "API key: missing" else "API key: configured",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    subtitle: String?,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun DebouncedTextField(
    initial: String,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    onCommit: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    LaunchedEffect(text, initial) {
        if (text != initial) {
            delay(450)
            onCommit(text)
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        maxLines = if (singleLine) 1 else 12,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        visualTransformation = visualTransformation,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun DropdownSetting(
    label: String,
    value: String,
    options: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge)
        androidx.compose.foundation.layout.Box {
            OutlinedButton(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    value,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (text, id) ->
                    DropdownMenuItem(
                        text = { Text(text) },
                        onClick = {
                            expanded = false
                            onSelect(id)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SwitchSetting(
    title: String,
    subtitle: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun SliderSetting(
    label: String,
    valueText: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    onCommit: (Float) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(valueText, style = MaterialTheme.typography.labelMedium)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onCommit(local) },
            valueRange = min..max,
            steps = (((max - min) / step).toInt() - 1).coerceAtLeast(0),
        )
    }
}

private fun formatPrice(value: Double): String =
    if (value <= 0.0) "" else java.math.BigDecimal(value).stripTrailingZeros().toPlainString()
