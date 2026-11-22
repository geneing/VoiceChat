package com.voicechat.assistant.ui

import com.voicechat.assistant.realtime.tools.toolResultText
import com.voicechat.assistant.VoiceForegroundService

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voicechat.assistant.VoiceChatApp
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.IssueReportSnapshot
import com.voicechat.assistant.core.IssueReportStore
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.core.PromptCatalog
import com.voicechat.assistant.core.PromptPreset
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
import com.voicechat.assistant.memory.MemoryFact
import com.voicechat.assistant.memory.MemoryPrompt
import com.voicechat.assistant.tutor.ConversationMode
import com.voicechat.assistant.tutor.TutorProgress
import com.voicechat.assistant.realtime.NewsConfig
import com.voicechat.assistant.realtime.RealtimeSession
import com.voicechat.assistant.realtime.SessionConfig
import com.voicechat.assistant.realtime.SessionListener
import com.voicechat.assistant.realtime.SessionUiState
import com.voicechat.assistant.realtime.handoff.HandoffConfig
import com.voicechat.assistant.realtime.handoff.HandoffPrompt
import com.voicechat.assistant.realtime.handoff.HandoffTrace
import com.voicechat.assistant.realtime.handoff.ReasoningModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * How long to let a freshly switched network validate before reconnecting on
 * it. Long enough for Android to finish DHCP/validation, short enough that a
 * voice turn does not lose seconds of air.
 */
private const val NETWORK_SETTLE_MS = 600L

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
    private val _mode = MutableStateFlow(ConversationMode.ASSISTANT)
    val mode = _mode.asStateFlow()
    private val _modeSwitching = MutableStateFlow(false)
    val modeSwitching = _modeSwitching.asStateFlow()
    private val navigation = Mutex()
    private val inputJobs = mutableSetOf<Job>()
    private fun launchInput(block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) { block() }
        inputJobs.add(job)
        job.invokeOnCompletion { inputJobs.remove(job) }
        job.start()
    }
    private suspend fun cancelInput() {
        val pending = inputJobs.toList()
        pending.forEach { it.cancel() }
        pending.joinAll()
    }
    private val persistenceJobs = mutableSetOf<Job>()
    private val tutorLearningJobs = mutableSetOf<Job>()
    private fun persist(block: suspend () -> Unit) {
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.warn(LogCategory.DB, "Could not save conversation", error.message)
                _uiEvents.tryEmit("Could not save part of this conversation.")
            }
        }
        persistenceJobs.add(job)
        job.invokeOnCompletion { persistenceJobs.remove(job) }
        job.start()
    }
    private val lastChats = mutableMapOf<ConversationMode, String?>()
    private fun repository(mode: ConversationMode = _mode.value) =
        if (mode == ConversationMode.SPANISH_TUTOR) container.tutorChats else container.chats
    private fun memoryStore(mode: ConversationMode = _mode.value) =
        if (mode == ConversationMode.SPANISH_TUTOR) container.tutorMemory else container.memory

    val tutorProgress = container.tutorProgress.progress
        .catch { error ->
            AppLog.warn(LogCategory.MEMORY, "Could not read Spanish progress", error.message)
            _uiEvents.tryEmit("Could not read Spanish learning progress.")
            emit(TutorProgress())
        }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), TutorProgress())

    val settings: StateFlow<AppSettings> = container.settings.settings
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    val usageTotals: StateFlow<UsageTotals> = container.settings.usageTotals
        .stateIn(scope, SharingStarted.Eagerly, UsageTotals())

    val chats: StateFlow<List<Chat>> = mode.flatMapLatest { repository(it).chats }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Facts remembered across chats, newest first for the memory screen. */
    val memoryFacts: StateFlow<List<MemoryFact>> = mode.flatMapLatest { memoryStore(it).facts }
        .map { facts -> facts.sortedByDescending { it.updatedAt } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    private val _currentChatId = MutableStateFlow<String?>(null)
    val currentChatId: StateFlow<String?> = _currentChatId.asStateFlow()
    private val chatCreation = Mutex()

    val currentChat: StateFlow<Chat?> = combine(chats, _currentChatId) { list, id ->
        list.firstOrNull { it.id == id }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val messages: StateFlow<List<Message>> = combine(mode, _currentChatId) { mode, id -> mode to id }
        .flatMapLatest { (mode, id) ->
            if (id == null) flowOf(emptyList()) else repository(mode).messages(id)
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    // Keep a finalized bubble visible until Room publishes the same message ID.
    private val voiceMessages = MutableStateFlow<List<Message>>(emptyList())
    val chatMessages: StateFlow<List<Message>> = combine(messages, voiceMessages, _currentChatId) { saved, voice, chatId ->
        val current = saved.filter { it.chatId == chatId }
        val pending = voice.filter { candidate ->
            candidate.chatId == chatId.orEmpty() && current.none { it.id == candidate.id }
        }
        if (pending.isEmpty()) current else (current + pending).sortedBy { it.createdAt }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

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

    private val _issueReportIds = MutableStateFlow<List<String>>(emptyList())
    val issueReportIds = _issueReportIds.asStateFlow()

    private var handoffUnavailableNoted = false
    /** Remembered live-mode intent so a manual retry restores voice mode. */
    private var liveWanted = false

    private val connectivity: ConnectivityManager? = runCatching {
        application.getSystemService(ConnectivityManager::class.java)
    }.getOrNull()

    /** Last default network, kept across a loss so a switch can be recognised. */
    private var lastNetwork: Network? = null

    /**
     * A phone that moves between Wi-Fi and mobile loses the interface its
     * realtime socket was dialled on. Reconnecting on the new default network is
     * what keeps a conversation alive across the switch; waiting for the
     * keep-alive would leave 20 s of dead air, and the old socket never recovers
     * because its route is gone.
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val active = connectivity?.activeNetwork ?: network
            val switched = lastNetwork != null && lastNetwork != active
            lastNetwork = active
            AppLog.info(
                LogCategory.TRANSPORT,
                if (switched) "Network switched" else "Network available",
                "${active} (${connectivity?.getLinkProperties(active)?.interfaceName ?: "?"})",
            )
            if (switched) {
                scope.launch {
                    // Give the new interface a moment to validate before redialling.
                    delay(NETWORK_SETTLE_MS)
                    session.onNetworkChanged()
                }
            } else {
                session.nudgeReconnect()
            }
        }

        override fun onLost(network: Network) {
            AppLog.info(LogCategory.TRANSPORT, "Network lost", "$network")
            // Keep lastNetwork: the next onAvailable compares against it to tell
            // a switch (Wi-Fi -> mobile) from a plain retry on the same network.
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            AppLog.debug(
                LogCategory.TRANSPORT,
                "Network capabilities changed",
                "$network ${IssueReportStore.describeNetwork(capabilities)}",
            )
        }
    }

    val session: RealtimeSession = RealtimeSession(
        scope = scope,
        transportFactory = { kind -> container.createTransport(kind, scope) },
        tools = container.tools,
        listener = this,
        reasoner = container.reasoner,
        memory = {
            // Read at open time, so a fact learned during this conversation
            // reaches the next session instead of restarting this one.
            if (_mode.value == ConversationMode.SPANISH_TUTOR) {
                container.tutorProgress.snapshot().context() +
                    if (settings.value.memoryEnabled) "\n" + container.tutorMemory.sessionBlock() else ""
            } else if (settings.value.memoryEnabled) container.memory.sessionBlock() else ""
        },
    )

    val sessionState: StateFlow<SessionUiState> = session.state

    init {
        scope.launch { VoiceForegroundService.stopRequests.collect { stopLive() } }
        scope.launch {
            var wasLive = false
            sessionState.collect { state ->
                val liveOrRecovering = state.liveEnabled || state.reconnecting
                if (wasLive && !liveOrRecovering) VoiceForegroundService.stop(application)
                wasLive = liveOrRecovering
            }
        }
        scope.launch {
            val saved = container.issueReports.list()
            _issueReportIds.update { (it + saved).distinct().sortedDescending() }
        }
        // Surface a bad search key instead of silently degrading to the fallback.
        container.tools.onSearchIssue = { message -> _uiEvents.tryEmit(message) }
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
        scope.launch {
            settings.collect { current ->
                val info = current.modelInfo
                val transport = if (current.transport in info.supportedTransports) {
                    current.transport
                } else {
                    TransportKind.WEBSOCKET
                }
                val voice = info.resolveVoice(current.voice, ConversationMode.ASSISTANT)
                val tutorVoice = info.resolveVoice(
                    current.tutorVoice,
                    ConversationMode.SPANISH_TUTOR,
                )
                val turnDetection = if (current.turnDetection in info.turnDetectionOptions) {
                    current.turnDetection
                } else {
                    info.defaultTurnDetection
                }
                if (transport != current.transport ||
                    voice != current.voice ||
                    tutorVoice != current.tutorVoice ||
                    turnDetection != current.turnDetection
                ) {
                    AppLog.info(
                        LogCategory.SESSION,
                        "Normalising model settings",
                        "transport ${current.transport}->$transport voice ${current.voice}->$voice " +
                            "tutorVoice ${current.tutorVoice}->$tutorVoice " +
                            "turn ${current.turnDetection}->$turnDetection",
                    )
                    container.settings.update {
                        it.copy(
                            transport = transport,
                            voice = voice,
                            tutorVoice = tutorVoice,
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

    override fun onModeSwitchRequested(mode: ConversationMode) = switchMode(mode)

    fun switchMode(target: ConversationMode) {
        scope.launch {
            navigation.withLock {
                if (target == _mode.value) return@withLock
                _modeSwitching.value = true
                try {
                    val resumeVoice = liveWanted
                    cancelInput()
                    session.stop()
                    persistenceJobs.toList().joinAll()
                    VoiceForegroundService.stop(getApplication<Application>())
                    chatCreation.withLock {
                        lastChats[_mode.value] = _currentChatId.value
                        _currentChatId.value = null
                        _streaming.value = null
                        voiceMessages.value = emptyList()
                        _pendingAttachments.value = emptyList()
                        _mode.value = target
                        val remembered = lastChats[target]
                        _currentChatId.value = remembered?.takeIf { repository(target).chat(it) != null }
                            ?: repository(target).chats.first().firstOrNull()?.id
                    }
                    liveWanted = false
                    _uiEvents.tryEmit("${target.label} mode")
                    if (resumeVoice) startLive()
                } finally {
                    _modeSwitching.value = false
                }
            }
        }
    }

    fun newChat() {
        liveWanted = false
        scope.launch { navigation.withLock {
            cancelInput()
            session.stop()
            persistenceJobs.toList().joinAll()
            _streaming.value = null
            _currentChatId.value = null
            lastChats[_mode.value] = null
            _pendingAttachments.value = emptyList()
        } }
    }

    fun selectChat(chatId: String) {
        if (_currentChatId.value == chatId) return
        liveWanted = false
        scope.launch { navigation.withLock {
            cancelInput()
            if (repository().chat(chatId) == null) return@withLock
            session.stop()
            persistenceJobs.toList().joinAll()
            _streaming.value = null
            _currentChatId.value = chatId
            _pendingAttachments.value = emptyList()
        } }
    }

    fun deleteChat(chatId: String) {
        val repo = repository()
        scope.launch {
            if (_currentChatId.value == chatId) {
                session.stop()
                _currentChatId.value = null
                _streaming.value = null
            }
            repo.deleteChat(chatId)
            _uiEvents.tryEmit("Chat deleted")
        }
    }

    fun renameChat(chatId: String, title: String) {
        val repo = repository()
        scope.launch { repo.renameChat(chatId, title) }
    }

    // ---- Conversation ------------------------------------------------------

    fun sendText(raw: String) {
        val typed = raw.trim()
        liveWanted = false
        // A typed control command follows the same cold-microphone policy as a text turn.
        val command = ConversationMode.command(typed)
        if (command != null) {
            stopLive()
            switchMode(command)
            return
        }
        if (_modeSwitching.value) return
        val attachments = _pendingAttachments.value
        if (typed.isEmpty() && attachments.isEmpty()) return
        if (attachments.any { it.kind == AttachmentKind.IMAGE } && !ReasoningModels.info(settings.value.handoffModel).supportsImages) {
            _uiEvents.tryEmit("The reasoning model cannot receive image attachments. Remove the image to send this message.")
            return
        }
        launchInput {
            session.setLiveMode(false)
            VoiceForegroundService.stop(getApplication<Application>())
            val chatId = ensureChatId()
            val prepared = prepareOutgoing(typed, attachments)
            if (prepared.images.size != attachments.count { it.kind == AttachmentKind.IMAGE }) {
                _uiEvents.tryEmit("An image could not be prepared. Please try again.")
                return@launchInput
            }
            repository().addMessage(
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
            learnFromUserTurn(typed, chatId)
            val config = buildConfig()
            if (config.apiKey.isBlank()) {
                _uiEvents.tryEmit("Add your Qwen API key in Settings first.")
                return@launchInput
            }
            val result = ensureSession(config)
            if (result.isFailure) {
                _uiEvents.tryEmit(
                    result.exceptionOrNull()?.message ?: "Could not open the realtime session.",
                )
                return@launchInput
            }
            liveWanted = false
            val typedReasoning = buildTypedReasoning(settings.value)
            session.sendText(
                text = MemoryPrompt.withTurnContext(
                    prepared.sentText,
                    relevantMemory(prepared.sentText),
                ),
                images = prepared.images,
                typedReasoning = typedReasoning,
                userInput = typed,
            )
                .onFailure { error ->
                    if (error !is CancellationException) {
                        _uiEvents.tryEmit("Could not send the message: ${error.message}")
                    }
                }
        }
    }

    fun startLive() {
        liveWanted = true
        launchInput {
            val config = buildConfig()
            if (config.apiKey.isBlank()) {
                _uiEvents.tryEmit("Add your Qwen API key in Settings first.")
                return@launchInput
            }
            val app = getApplication<Application>()
            ensureChatId()
            if (!VoiceForegroundService.start(app)) {
                _uiEvents.tryEmit("Voice can continue while VoiceChat is visible. Android blocked background listening.")
            }
            val state = session.state.value
            val result = if (state.connected) {
                session.setLiveMode(true)
                Result.success(Unit)
            } else {
                session.start(config, live = true)
            }
            if (result.isFailure) {
                VoiceForegroundService.stop(app)
                _uiEvents.tryEmit(
                    result.exceptionOrNull()?.message ?: "Could not start voice mode.",
                )
            } else if (config.spanishTutor && !state.connected) {
                session.startTutorLesson()
            }
        }
    }

    fun stopLive() {
        liveWanted = false
        scope.launch {
            cancelInput()
            session.setLiveMode(false)
            VoiceForegroundService.stop(getApplication<Application>())
        }
    }

    fun interrupt() {
        scope.launch { session.interrupt() }
    }

    fun disconnect() {
        liveWanted = false
        scope.launch {
            session.stop()
            _streaming.value = null
        }
    }

    /** Manual retry from the error banner, keeping the previous mode. */
    fun reconnect() {
        launchInput {
            val config = buildConfig()
            if (config.apiKey.isBlank()) {
                _uiEvents.tryEmit("Add your Qwen API key in Settings first.")
                return@launchInput
            }
            val live = liveWanted
            if (live) VoiceForegroundService.start(getApplication<Application>())
            val result = session.start(config, live = live)
            if (result.isFailure) {
                VoiceForegroundService.stop(getApplication<Application>())
                _uiEvents.tryEmit(
                    result.exceptionOrNull()?.message ?: "Could not reconnect.",
                )
            } else {
                _uiEvents.tryEmit(if (live) "Voice mode restored." else "Reconnected.")
            }
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

    /** Prompt presets and the reset target, from `assets/prompts.json`. */
    val promptPresets: List<PromptPreset> get() = PromptCatalog.presets
    val defaultPrompt: String get() = PromptCatalog.defaultPrompt

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
            val voice = info.resolveVoice(after.voice, ConversationMode.ASSISTANT)
            val tutorVoice = info.resolveVoice(after.tutorVoice, ConversationMode.SPANISH_TUTOR)
            val turnDetection = if (after.turnDetection in info.turnDetectionOptions) {
                after.turnDetection
            } else {
                info.defaultTurnDetection
            }
            if (transport != after.transport ||
                voice != after.voice ||
                tutorVoice != after.tutorVoice ||
                turnDetection != after.turnDetection
            ) {
                after = after.copy(
                    transport = transport,
                    voice = voice,
                    tutorVoice = tutorVoice,
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

    // ---- Memory ------------------------------------------------------------

    fun resetTutorProgress() {
        scope.launch {
            navigation.withLock {
                if (_mode.value == ConversationMode.SPANISH_TUTOR) {
                    cancelInput()
                    liveWanted = false
                    session.stop()
                    persistenceJobs.toList().joinAll()
                    _streaming.value = null
                    VoiceForegroundService.stop(getApplication<Application>())
                }
                val learning = tutorLearningJobs.toList()
                learning.forEach { it.cancel() }
                learning.joinAll()
                container.tutorProgress.clear()
                _uiEvents.tryEmit("Spanish learning progress reset to A2.")
            }
        }
    }

    fun deleteMemory(fact: MemoryFact) {
        val store = memoryStore()
        scope.launch {
            store.delete(fact.id)
            _uiEvents.tryEmit("Fact forgotten.")
        }
    }

    fun updateMemory(fact: MemoryFact, text: String) {
        val store = memoryStore()
        scope.launch {
            store.update(fact.id, text)
            _uiEvents.tryEmit("Fact updated.")
        }
    }

    fun clearMemory() {
        val store = memoryStore()
        scope.launch {
            store.clear()
            _uiEvents.tryEmit("Memory erased.")
        }
    }

    fun clearLogs() = AppLog.clear()

    fun exportLogs(): String = AppLog.export()

    fun saveButtonIssueReport() = saveIssueReport("Report button", "")

    override fun onIssueReportRequested(description: String) = saveIssueReport("Voice command", description)

    private fun saveIssueReport(source: String, description: String) {
        // Capture the active state before any disk or platform diagnostics run.
        val capturedAt = System.currentTimeMillis()
        val current = settings.value
        val state = sessionState.value
        val chatId = _currentChatId.value
        val snapshot = IssueReportSnapshot(
            capturedAt = capturedAt,
            source = source,
            description = description,
            session = "Configured model ${current.model} over ${current.transport}\n\n" +
                "Status ${state.status}, connected=${state.connected}, live=${state.liveEnabled}, " +
                "reconnecting=${state.reconnecting}, reasoningModel=${state.handoff?.model ?: "none"}",
            messages = messages.value.asReversed().asSequence()
                .filter { it.chatId == chatId && it.createdAt <= capturedAt }
                .take(24).toList().asReversed(),
            assistantPartial = state.assistantPartial,
            appLogs = AppLog.entries.value,
            apiKeyOverride = current.apiKeyOverride,
            protocolDump = AppLog.protocolDump,
        )
        scope.launch {
            try {
                val id = container.issueReports.save(snapshot)
                _issueReportIds.update { (it + id).sortedDescending() }
                showMessage("Issue report saved. Open Debug logs → Issue reports to export it.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage("Could not save the issue report.")
            }
        }
    }

    fun exportIssueReport(id: String, uri: Uri) {
        scope.launch {
            try {
                container.issueReports.export(id, uri)
                showMessage("Issue report ZIP exported.")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                showMessage("Could not export the issue report.")
            }
        }
    }

    fun showMessage(message: String) {
        _uiEvents.tryEmit(message)
    }

    // ---- SessionListener ---------------------------------------------------

    override fun onUserTranscriptPartial(text: String) {
        val current = voiceMessages.value
        val pending = current.filterNot { it.streaming }
        voiceMessages.value = if (text.isBlank()) {
            pending
        } else {
            val draft = current.lastOrNull { it.streaming } ?: newVoiceMessage(text)
            pending + draft.copy(text = text, streaming = true)
        }
    }

    override fun onUserTranscriptFinal(text: String) {
        if (text.isBlank()) return
        val turnMode = _mode.value
        val repo = repository(turnMode)
        val current = voiceMessages.value
        val draft = current.lastOrNull { it.streaming } ?: newVoiceMessage(text)
        val message = draft.copy(text = text, streaming = false)
        voiceMessages.value = current.filterNot { it.streaming } + message
        persist {
            try {
                val chatId = message.chatId.ifEmpty { ensureChatId(turnMode) }
                voiceMessages.update { rows ->
                    rows.map { if (it.id == message.id) message.copy(chatId = chatId) else it }
                }
                repo.addMessage(message.copy(chatId = chatId))
                learnFromUserTurn(text, chatId, turnMode)
                combine(messages, _currentChatId) { saved, selected ->
                    selected != chatId || saved.any { it.id == message.id }
                }.first { it }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                AppLog.warn(LogCategory.DB, "Could not save voice transcript", error.message)
                showMessage("Could not save the voice transcript.")
            } finally {
                voiceMessages.update { rows -> rows.filterNot { it.id == message.id } }
            }
        }
    }

    private fun newVoiceMessage(text: String) = Message(
        id = UUID.randomUUID().toString(),
        chatId = _currentChatId.value.orEmpty(),
        role = MessageRole.USER,
        kind = MessageKind.VOICE,
        text = text,
        attachments = emptyList(),
        createdAt = System.currentTimeMillis(),
    )

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
        val turnMode = _mode.value
        val selectedId = _currentChatId.value
        val repo = repository(turnMode)
        val stream = _streaming.value
        _streaming.value = null
        val content = text.trim()
        if (content.isEmpty()) return
        val messageId = stream?.id ?: "assistant-${UUID.randomUUID()}"
        val learningSettings = settings.value
        persist {
            val chatId = selectedId ?: ensureChatId(turnMode)
            repo.addMessage(
                Message(
                    id = messageId,
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
            if (turnMode == ConversationMode.SPANISH_TUTOR && !partial && container.reasoner.configured) {
                val config = buildNews(learningSettings)?.model
                if (config != null) {
                    val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
                        try {
                            container.tutorLearning.observe(messageId, learningSettings.apiKey, config) { usage, cost ->
                                onUsage(usage, cost)
                                repo.message(messageId)?.let { saved ->
                                    repo.updateMessage(messageId,
                                        inputTokens = saved.inputTokens + usage.inputTokens,
                                        outputTokens = saved.outputTokens + usage.outputTokens,
                                        costUsd = saved.costUsd + cost,
                                    )
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            AppLog.warn(LogCategory.MEMORY, "Spanish learning recap failed", error.message)
                            _uiEvents.tryEmit("Could not update the Spanish learning summary. Your dialog is saved.")
                        }
                    }
                    tutorLearningJobs.add(job)
                    job.invokeOnCompletion { tutorLearningJobs.remove(job) }
                    job.start()
                }
            }
        }
    }

    override fun onToolCall(name: String, arguments: String, result: String?) {
        // Learning bookkeeping is displayed in the progress panel, not as noisy chat bubbles.
        if (name == "record_tutor_progress" || name == "recall_tutor_progress") return
        val turnMode = _mode.value
        val selectedId = _currentChatId.value
        val repo = repository(turnMode)
        if (result == null) {
            _uiEvents.tryEmit("Using tool: $name")
            return
        }
        persist {
            val chatId = selectedId ?: ensureChatId(turnMode)
            val body = toolResultText(name, result) ?: buildString {
                append(name)
                append("(")
                append(arguments.take(160))
                append(")\n")
                append(result.take(600))
            }
            repo.addMessage(
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

    override fun onHandoff(trace: HandoffTrace) {
        val turnMode = _mode.value
        val selectedId = _currentChatId.value
        val repo = repository(turnMode)
        persist {
            val chatId = selectedId ?: ensureChatId(turnMode)
            repo.addMessage(
                Message(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId,
                    role = MessageRole.TOOL,
                    kind = MessageKind.HANDOFF,
                    text = HandoffPrompt.cardText(trace),
                    attachments = emptyList(),
                    createdAt = System.currentTimeMillis(),
                    streaming = false,
                    inputTokens = trace.usage.inputTokens,
                    outputTokens = trace.usage.outputTokens,
                    costUsd = trace.costUsd,
                ),
            )
        }
        if (trace.failed) {
            _uiEvents.tryEmit(trace.error ?: "Deep reasoning failed.")
        }
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

    private suspend fun ensureChatId(mode: ConversationMode = _mode.value): String = chatCreation.withLock {
        val repo = repository(mode)
        val existing = if (mode == _mode.value) _currentChatId.value else lastChats[mode]
        if (existing != null && repo.chat(existing) != null) return@withLock existing
        val id = repo.createChat()
        lastChats[mode] = id
        if (mode == _mode.value) _currentChatId.value = id
        id
    }

    /**
     * Facts worth attaching to one typed turn. Empty when memory is off, the
     * per-turn block is disabled, nothing matches, or the database fails: a
     * failed lookup must never cost the user their message.
     *
     * Voice turns cannot be extended this way — the server transcribes them — so
     * they rely on the session block and the `recall_memory` tool instead.
     */
    private suspend fun relevantMemory(text: String): String {
        val current = settings.value
        if (!current.memoryEnabled || !current.memoryPerTurn || text.isBlank()) return ""
        return try {
            memoryStore().turnBlock(text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            AppLog.warn(LogCategory.MEMORY, "Memory lookup failed", e.message)
            ""
        }
    }

    /**
     * Learns from a user turn on the device. Fire and forget: extraction is
     * regex work on one sentence, but it still runs off the turn so it can never
     * delay a reply.
     */
    private fun learnFromUserTurn(text: String, chatId: String?, mode: ConversationMode = _mode.value) {
        // Tutor exercises contain fictional first-person sentences. Explicit facts use remember_fact.
        if (mode == ConversationMode.SPANISH_TUTOR) return
        if (text.isBlank() || !settings.value.memoryEnabled) return
        scope.launch(Dispatchers.IO) {
            try {
                memoryStore(mode).learn(text, chatId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.warn(LogCategory.MEMORY, "Could not learn from a turn", e.message)
            }
        }
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
        val tutor = _mode.value == ConversationMode.SPANISH_TUTOR
        val modelInfo = current.modelInfo
        val toolsEnabled = (current.toolsEnabled || tutor) && modelInfo.supportsTools
        val nativeSearch = !tutor && modelInfo.resolveNativeSearch(current.nativeSearch, toolsEnabled)
        val handoff = buildHandoff(current, toolsEnabled)
        if (current.handoffEnabled && handoff == null && !handoffUnavailableNoted) {
            handoffUnavailableNoted = true
            _uiEvents.tryEmit(
                if (!toolsEnabled) {
                    "Deep reasoning needs client tools. Enable Tools in Settings to use it."
                } else {
                    "Deep reasoning endpoint is not configured."
                },
            )
        }
        return SessionConfig(
            model = current.model,
            voice = modelInfo.resolveVoice(current.voiceFor(_mode.value), _mode.value),
            instructions = if (tutor) PromptCatalog.spanishTutor else current.instructions,
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
            onDeviceVad = current.onDeviceVad,
            vadSensitivityDb = current.vadSensitivityDb,
            inputTranscription = current.inputTranscription || tutor,
            transcriptionModel = modelInfo.transcriptionModel,
            toolsJson = if (toolsEnabled) {
                container.tools.definitions(
                    includeWebSearch = !nativeSearch,
                    includeHandoff = handoff != null,
                    includeMemory = current.memoryEnabled,
                    includeTutor = tutor,
                )
            } else {
                null
            },
            handoff = handoff,
            news = buildNews(current),
            nativeSearch = nativeSearch,
            searchSources = current.searchSources,
            searchProvider = current.searchProvider,
            priceInputPerMillion = current.priceInputPerMillion,
            priceOutputPerMillion = current.priceOutputPerMillion,
            languageHint = if (tutor) "Mexican Spanish for practice; English for explanations and questions when helpful." else current.languageHint,
            spanishTutor = tutor,
        )
    }

    private fun buildHandoff(current: AppSettings, toolsEnabled: Boolean): HandoffConfig? {
        // The handoff is a client tool: without the tools array the realtime
        // model can never call it, so do not advertise one.
        if (!current.handoffEnabled || !toolsEnabled) return null
        if (!container.reasoner.configured) return null
        return HandoffConfig(
            model = ReasoningModels.resolve(current.handoffModel),
            thinkingBudget = current.handoffThinkingBudget.coerceIn(
                ReasoningModels.MIN_THINKING_BUDGET,
                ReasoningModels.MAX_THINKING_BUDGET,
            ),
            timeoutMs = current.handoffTimeoutMs.coerceIn(
                ReasoningModels.MIN_TIMEOUT_MS,
                ReasoningModels.MAX_TIMEOUT_MS,
            ),
            contextTurns = current.handoffContextTurns.coerceIn(0, 16),
            forceForTypedTurns = current.handoffHeuristic,
            speculativeVoice = current.handoffSpeculativeVoice,
            priceInputPerMillion = current.handoffPriceInputPerMillion,
            priceOutputPerMillion = current.handoffPriceOutputPerMillion,
            nativeWebSearch = true,
        )
    }

    private fun buildTypedReasoning(current: AppSettings): HandoffConfig? {
        if (!container.reasoner.configured) return null
        return HandoffConfig(
            model = ReasoningModels.resolve(current.handoffModel),
            thinkingBudget = current.handoffThinkingBudget.coerceIn(ReasoningModels.MIN_THINKING_BUDGET, ReasoningModels.MAX_THINKING_BUDGET),
            timeoutMs = current.handoffTimeoutMs.coerceIn(ReasoningModels.MIN_TIMEOUT_MS, ReasoningModels.MAX_TIMEOUT_MS),
            contextTurns = current.handoffContextTurns.coerceIn(0, 16),
            priceInputPerMillion = current.handoffPriceInputPerMillion,
            priceOutputPerMillion = current.handoffPriceOutputPerMillion,
            nativeWebSearch = true,
        )
    }

    /**
     * News digest settings. Independent of the handoff switch: a user can want
     * news composed by the big model without the `deep_reasoning` tool. `null`
     * means the session has no reasoning endpoint, so `get_news` answers with
     * the app's dated items only.
     */
    private fun buildNews(current: AppSettings): NewsConfig? {
        if (!container.reasoner.configured) return null
        return NewsConfig(
            model = HandoffConfig(
                model = ReasoningModels.resolve(current.handoffModel),
                // A digest is summarization, not reasoning: a thinking trace only
                // adds seconds to the wait before the assistant can speak.
                thinkingBudget = 0,
                timeoutMs = current.handoffTimeoutMs.coerceIn(
                    ReasoningModels.MIN_TIMEOUT_MS,
                    ReasoningModels.MAX_TIMEOUT_MS,
                ),
                priceInputPerMillion = current.handoffPriceInputPerMillion,
                priceOutputPerMillion = current.handoffPriceOutputPerMillion,
            ),
        )
    }

    private suspend fun prepareOutgoing(
        typed: String,
        attachments: List<Attachment>,
    ): PreparedOutgoing {
        val imageAttachments = attachments.filter { it.kind == AttachmentKind.IMAGE }
        val images = mutableListOf<String>()
        if (imageAttachments.isNotEmpty()) {
            imageAttachments.forEach { attachment ->
                container.attachments.imageBase64(attachment)?.let { images += it }
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
        VoiceForegroundService.stop(getApplication<Application>())
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        session.shutdown()
        super.onCleared()
    }
}

private fun AppSettings.sessionKey(): List<Any> = listOf(
    transport,
    model,
    voice,
    tutorVoice,
    instructions,
    host,
    workspaceId,
    apiKey,
    turnDetection,
    vadThreshold,
    silenceMs,
    onDeviceVad,
    vadSensitivityDb,
    inputTranscription,
    toolsEnabled,
    nativeSearch,
    searchSources,
    searchProvider,
    languageHint,
    memoryEnabled,
)
