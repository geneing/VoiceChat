package com.voicechat.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicechat.assistant.VoiceChatApp
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.core.RealtimeDefaults
import com.voicechat.assistant.data.model.Attachment
import com.voicechat.assistant.data.model.AttachmentKind
import com.voicechat.assistant.data.model.Chat
import com.voicechat.assistant.data.model.ConversationStats
import com.voicechat.assistant.data.model.Message
import com.voicechat.assistant.data.model.MessageKind
import com.voicechat.assistant.data.model.MessageRole
import com.voicechat.assistant.data.model.TokenUsage
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.data.model.TurnMetrics
import com.voicechat.assistant.data.model.UsageTotals
import com.voicechat.assistant.data.prefs.AppSettings
import com.voicechat.assistant.realtime.RealtimeSession
import com.voicechat.assistant.realtime.SessionConfig
import com.voicechat.assistant.realtime.SessionListener
import com.voicechat.assistant.realtime.SessionUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

data class StreamingMessage(val id: String, val text: String, val voice: Boolean)

private data class PreparedOutgoing(
    val sentText: String,
    val displayText: String,
    val images: List<String>,
)

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(application: Application) :
    AndroidViewModel(application),
    SessionListener {

    private val container = (application as VoiceChatApp).container
    private val scope = viewModelScope

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val usageTotals: StateFlow<UsageTotals> = container.settings.usageTotals
        .stateIn(scope, SharingStarted.Eagerly, UsageTotals())

    val chats: StateFlow<List<Chat>> = container.chats.chats
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()

    val currentChat: StateFlow<Chat?> = combine(chats, _currentChatId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<Message>> = _currentChatId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else container.chats.messages(id)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val conversationStats: StateFlow<ConversationStats> = messages
        .map { list ->
            val assistant = list.filter { it.role == MessageRole.ASSISTANT }
            val ttfts = assistant.map { it.ttftMs }.filter { it > 0 }
            ConversationStats(
                turns = assistant.size,
                inputTokens = list.sumOf { it.inputTokens },
                outputTokens = list.sumOf { it.outputTokens },
                costUsd = list.sumOf { it.costUsd },
                lastTtftMs = ttfts.lastOrNull() ?: -1,
                averageTtftMs = if (ttfts.isEmpty()) -1 else ttfts.average().toLong(),
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, ConversationStats())

    private val _pendingAttachments = MutableStateFlow<List<Attachment>>(emptyList())
    val pendingAttachments: StateFlow<List<Attachment>> = _pendingAttachments.asStateFlow()

    private val _streaming = MutableStateFlow<StreamingMessage?>(null)
    val streaming: StateFlow<StreamingMessage?> = _streaming.asStateFlow()

    private val _uiEvents = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val uiEvents = _uiEvents.asSharedFlow()

    val session: RealtimeSession = RealtimeSession(
        scope = scope,
        transportFactory = { kind -> container.createTransport(kind, scope) },
        tools = container.tools,
        listener = this,
    )

    val sessionState: StateFlow<SessionUiState> = session.state

    init {
        scope.launch {
            settings.collect { current ->
                val info = current.modelInfo
                val transport = if (current.transport in info.supportedTransports) {
                    current.transport
                } else {
                    TransportKind.WEBSOCKET
                }
                val voice = info.resolveVoice(current.voice)
                val turnDetection = if (current.turnDetection in info.turnDetectionOptions) {
                    current.turnDetection
                } else {
                    info.defaultTurnDetection
                }
                if (transport != current.transport ||
                    voice != current.voice ||
                    turnDetection != current.turnDetection
                ) {
                    AppLog.info(
                        LogCategory.SESSION,
                        "Normalising model settings",
                        "transport ${current.transport}->$transport voice ${current.voice}->$voice " +
                            "turn ${current.turnDetection}->$turnDetection",
                    )
                    container.settings.update {
                        it.copy(
                            transport = transport,
                            voice = voice,
                            turnDetection = turnDetection,
                        )
                    }
                    _uiEvents.tryEmit(
                        "${info.label}: adjusted transport/voice/turn detection.",
                    )
                }
                AppLog.configure(
                    categories = current.debugCategories,
                    protocolDump = current.protocolDump,
                    fileLogging = current.fileLogging,
                )
            }
        }
        AppLog.configure(
            settings.value.debugCategories,
            settings.value.protocolDump,
            settings.value.fileLogging,
        )
    }

    // ---- Chats -------------------------------------------------------------

    fun newChat() {
        scope.launch {
            session.stop()
            _streaming.value = null
            _currentChatId.value = null
            _pendingAttachments.value = emptyList()
        }
    }

    fun selectChat(chatId: String) {
        if (_currentChatId.value == chatId) return
        scope.launch {
            session.stop()
            _streaming.value = null
            _currentChatId.value = chatId
            _pendingAttachments.value = emptyList()
        }
    }

    fun deleteChat(chatId: String) {
        scope.launch {
            if (_currentChatId.value == chatId) {
                session.stop()
                _currentChatId.value = null
                _streaming.value = null
            }
            container.chats.deleteChat(chatId)
            _uiEvents.tryEmit("Chat deleted")
        }
    }

    fun renameChat(chatId: String, title: String) {
        scope.launch { container.chats.renameChat(chatId, title) }
    }

    // ---- Conversation ------------------------------------------------------

    fun sendText(raw: String) {
        val typed = raw.trim()
        val attachments = _pendingAttachments.value
        if (typed.isEmpty() && attachments.isEmpty()) return
        scope.launch {
            val chatId = ensureChatId()
            val prepared = prepareOutgoing(typed, attachments)
            container.chats.addMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId,
                    role = MessageRole.USER,
                    kind = MessageKind.TEXT,
                    text = prepared.displayText,
                    attachments = attachments,
                    createdAt = System.currentTimeMillis(),
                    streaming = false,
                ),
            )
            _pendingAttachments.value = emptyList()
            val config = buildConfig()
            if (config.apiKey.isBlank()) {
                _uiEvents.tryEmit("Add your Qwen API key in Settings first.")
                return@launch
            }
            val result = ensureSession(config)
            if (result.isFailure) {
                _uiEvents.tryEmit(
                    result.exceptionOrNull()?.message ?: "Could not open the realtime session.",
                )
                return@launch
            }
            session.sendText(prepared.sentText, prepared.images).onFailure { error ->
                _uiEvents.tryEmit("Could not send the message: ${error.message}")
            }
        }
    }

    fun startLive() {
        scope.launch {
            val config = buildConfig()
            if (config.apiKey.isBlank()) {
                _uiEvents.tryEmit("Add your Qwen API key in Settings first.")
                return@launch
            }
            val state = session.state.value
            val result = if (state.connected) {
                session.setLiveMode(true)
                Result.success(Unit)
            } else {
                session.start(config, live = true)
            }
            if (result.isFailure) {
                _uiEvents.tryEmit(
                    result.exceptionOrNull()?.message ?: "Could not start voice mode.",
                )
            }
        }
    }

    fun stopLive() {
        scope.launch { session.setLiveMode(false) }
    }

    fun interrupt() {
        scope.launch { session.interrupt() }
    }

    fun disconnect() {
        scope.launch {
            session.stop()
            _streaming.value = null
        }
    }

    fun dismissError() = session.dismissError()

    fun testConnection() {
        scope.launch {
            val config = buildConfig()
            if (config.apiKey.isBlank()) {
                _uiEvents.tryEmit("Add your Qwen API key in Settings first.")
                return@launch
            }
            if (session.state.value.connected) {
                _uiEvents.tryEmit("Already connected to ${config.model}.")
                return@launch
            }
            val started = session.start(config, live = false)
            if (started.isFailure) {
                _uiEvents.tryEmit(
                    "Connection failed: ${started.exceptionOrNull()?.message ?: "unknown error"}",
                )
                return@launch
            }
            delay(700)
            val ms = session.state.value.connectMs
            _uiEvents.tryEmit(
                if (ms > 0) "Connected to ${config.model} in $ms ms." else "Connected to ${config.model}.",
            )
            session.stop()
        }
    }

    // ---- Attachments -------------------------------------------------------

    fun createCameraTarget(): Pair<Uri, java.io.File> = container.attachments.createCameraTarget()

    fun attachUri(uri: Uri) {
        scope.launch {
            val attachment = container.attachments.importUri(uri)
            if (attachment == null) {
                _uiEvents.tryEmit("Could not read that file.")
            } else {
                _pendingAttachments.update { it + attachment }
            }
        }
    }

    fun attachCameraPhoto(file: java.io.File) {
        scope.launch {
            val attachment = container.attachments.importCameraPhoto(file)
            if (attachment == null) {
                _uiEvents.tryEmit("Could not read the camera photo.")
            } else {
                _pendingAttachments.update { it + attachment }
            }
        }
    }

    fun removeAttachment(attachment: Attachment) {
        _pendingAttachments.update { list -> list.filterNot { it.path == attachment.path } }
    }

    fun clearAttachments() {
        _pendingAttachments.value = emptyList()
    }

    // ---- Settings ----------------------------------------------------------

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        scope.launch {
            val before = settings.value
            var after = transform(before)
            val info = after.modelInfo
            val transport = if (after.transport in info.supportedTransports) {
                after.transport
            } else {
                TransportKind.WEBSOCKET
            }
            val voice = info.resolveVoice(after.voice)
            val turnDetection = if (after.turnDetection in info.turnDetectionOptions) {
                after.turnDetection
            } else {
                info.defaultTurnDetection
            }
            if (transport != after.transport ||
                voice != after.voice ||
                turnDetection != after.turnDetection
            ) {
                after = after.copy(
                    transport = transport,
                    voice = voice,
                    turnDetection = turnDetection,
                )
                _uiEvents.tryEmit(
                    "${info.label}: adjusted transport/voice/turn detection.",
                )
            }
            container.settings.update { after }
            if (before.sessionKey() != after.sessionKey() && session.state.value.active) {
                session.stop()
                _streaming.value = null
                _uiEvents.tryEmit("Session settings changed. Reconnecting on the next turn.")
            }
        }
    }

    fun resetUsage() {
        scope.launch {
            container.settings.resetUsage()
            _uiEvents.tryEmit("Usage counters cleared.")
        }
    }

    fun clearLogs() = AppLog.clear()

    fun exportLogs(): String = AppLog.export()

    fun showMessage(message: String) {
        _uiEvents.tryEmit(message)
    }

    // ---- SessionListener ---------------------------------------------------

    override fun onUserTranscriptFinal(text: String) {
        if (text.isBlank()) return
        scope.launch {
            val chatId = ensureChatId()
            container.chats.addMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId,
                    role = MessageRole.USER,
                    kind = MessageKind.VOICE,
                    text = text,
                    attachments = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    streaming = false,
                ),
            )
        }
    }

    override fun onAssistantStart(voice: Boolean) {
        if (_streaming.value == null) {
            _streaming.value = StreamingMessage(
                id = "assistant-${UUID.randomUUID()}",
                text = "",
                voice = voice,
            )
        }
    }

    override fun onAssistantDelta(text: String) {
        _streaming.update { current ->
            current?.copy(text = text)
                ?: StreamingMessage(
                    id = "assistant-${UUID.randomUUID()}",
                    text = text,
                    voice = session.state.value.speaking,
                )
        }
    }

    override fun onAssistantFinished(text: String, metrics: TurnMetrics, partial: Boolean) {
        val stream = _streaming.value
        _streaming.value = null
        val content = text.trim()
        if (content.isEmpty()) return
        scope.launch {
            val chatId = ensureChatId()
            container.chats.addMessage(
                Message(
                    id = stream?.id ?: "assistant-${UUID.randomUUID()}",
                    chatId = chatId,
                    role = MessageRole.ASSISTANT,
                    kind = if (stream?.voice == true) MessageKind.VOICE else MessageKind.TEXT,
                    text = content,
                    attachments = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    streaming = false,
                    inputTokens = metrics.usage?.inputTokens ?: 0,
                    outputTokens = metrics.usage?.outputTokens ?: 0,
                    ttftMs = metrics.ttftMs,
                    costUsd = metrics.costUsd,
                ),
            )
        }
    }

    override fun onToolCall(name: String, arguments: String, result: String?) {
        if (result == null) {
            _uiEvents.tryEmit("Using tool: $name")
            return
        }
        scope.launch {
            val chatId = ensureChatId()
            val body = buildString {
                append(name)
                append("(")
                append(arguments.take(160))
                append(")\n")
                append(result.take(600))
            }
            container.chats.addMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId,
                    role = MessageRole.TOOL,
                    kind = MessageKind.TOOL,
                    text = body,
                    attachments = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    streaming = false,
                ),
            )
        }
    }

    override fun onError(message: String) {
        _uiEvents.tryEmit(message)
    }

    override fun onUsage(usage: TokenUsage, costUsd: Double) {
        scope.launch {
            container.settings.addUsage(usage.inputTokens, usage.outputTokens, costUsd)
        }
    }

    // ---- Helpers -----------------------------------------------------------

    fun endpointPreview(): String {
        val current = settings.value
        return when (current.transport) {
            TransportKind.WEBSOCKET -> "wss://…/api-ws/v1/realtime?model=${current.model}"
            TransportKind.WEBRTC, TransportKind.AOQ -> "https://…/api/v1/webrtc/realtime?model=${current.model}"
        }
    }

    fun defaultVoiceForModel(model: String): String =
        RealtimeDefaults.models.firstOrNull { it.id == model }?.defaultVoice ?: "Tina"

    private suspend fun ensureChatId(): String {
        val existing = _currentChatId.value
        if (existing != null && container.chats.chat(existing) != null) return existing
        val id = container.chats.createChat()
        _currentChatId.value = id
        return id
    }

    private suspend fun ensureSession(config: SessionConfig): Result<Unit> {
        val state = session.state.value
        val current = session.currentConfig()
        return if (state.connected && current == config) {
            Result.success(Unit)
        } else {
            val live = state.liveEnabled
            session.start(config, live = live)
        }
    }

    private fun buildConfig(): SessionConfig {
        val current = settings.value
        val modelInfo = current.modelInfo
        val toolsEnabled = current.toolsEnabled && !current.nativeSearch && modelInfo.supportsTools
        return SessionConfig(
            model = current.model,
            voice = modelInfo.resolveVoice(current.voice),
            instructions = current.instructions,
            transport = if (current.transport in modelInfo.supportedTransports) {
                current.transport
            } else {
                TransportKind.WEBSOCKET
            },
            apiKey = current.apiKey,
            host = current.host,
            workspaceId = current.workspaceId,
            aoqTokenUrl = current.aoqTokenUrl.trim(),
            turnDetection = if (current.turnDetection in modelInfo.turnDetectionOptions) {
                current.turnDetection
            } else {
                modelInfo.defaultTurnDetection
            },
            vadThreshold = current.vadThreshold,
            silenceMs = current.silenceMs,
            speechRate = if (modelInfo.supportsSpeechRate) modelInfo.defaultSpeechRate else 0.0,
            inputTranscription = current.inputTranscription,
            transcriptionModel = modelInfo.transcriptionModel,
            toolsJson = if (toolsEnabled) container.tools.definitionsJson else null,
            nativeSearch = current.nativeSearch && modelInfo.supportsTools,
            searchSources = current.searchSources,
            priceInputPerMillion = current.priceInputPerMillion,
            priceOutputPerMillion = current.priceOutputPerMillion,
            languageHint = current.languageHint,
        )
    }

    private suspend fun prepareOutgoing(
        typed: String,
        attachments: List<Attachment>,
    ): PreparedOutgoing {
        val transport = settings.value.transport
        val imageAttachments = attachments.filter { it.kind == AttachmentKind.IMAGE }
        val images = mutableListOf<String>()
        if (imageAttachments.isNotEmpty()) {
            if (transport != TransportKind.WEBSOCKET) {
                _uiEvents.tryEmit(
                    "Image attachments need the WebSocket transport. The text was sent without them.",
                )
            } else {
                imageAttachments.forEach { attachment ->
                    container.attachments.imageBase64(attachment)?.let { images += it }
                }
                if (images.isEmpty()) {
                    _uiEvents.tryEmit("Images could not be prepared; sending text only.")
                }
            }
        }
        val documents = attachments.filter { it.kind == AttachmentKind.DOCUMENT }
        val documentTexts = documents.mapNotNull { attachment ->
            val content = container.attachments.documentText(attachment)
            if (content != null) {
                "Document \"${attachment.name}\":\n$content"
            } else {
                "Attached file \"${attachment.name}\" (${attachment.mimeType})."
            }
        }
        val sentText = buildString {
            if (typed.isNotBlank()) append(typed)
            if (documentTexts.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append(documentTexts.joinToString("\n\n"))
            }
            if (isBlank()) append("Please look at the attached content.")
        }
        val displayText = typed.ifBlank {
            attachments.joinToString(", ") { it.name }.ifBlank { "Attachment" }
        }
        return PreparedOutgoing(sentText, displayText, images)
    }

    override fun onCleared() {
        session.shutdown()
        super.onCleared()
    }
}

private fun AppSettings.sessionKey(): List<Any> = listOf(
    transport,
    model,
    voice,
    instructions,
    host,
    workspaceId,
    apiKey,
    turnDetection,
    vadThreshold,
    silenceMs,
    inputTranscription,
    toolsEnabled,
    nativeSearch,
    searchSources,
    languageHint,
)
