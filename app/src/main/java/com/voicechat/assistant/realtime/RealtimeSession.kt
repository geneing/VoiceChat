package com.voicechat.assistant.realtime

import android.os.SystemClock
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.CostCalculator
import com.voicechat.assistant.data.model.TokenUsage
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.data.model.TurnMetrics
import com.voicechat.assistant.realtime.tools.ToolRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LiveStatus { IDLE, CONNECTING, LISTENING, THINKING, RESPONDING, ERROR }

data class SessionUiState(
    val active: Boolean = false,
    val connected: Boolean = false,
    val liveEnabled: Boolean = false,
    val status: LiveStatus = LiveStatus.IDLE,
    val transport: TransportKind? = null,
    val model: String? = null,
    val voice: String? = null,
    val userPartial: String = "",
    val assistantPartial: String = "",
    val micLevel: Float = 0f,
    val speaking: Boolean = false,
    val connectMs: Long = -1,
    val ttftMs: Long = -1,
    val error: String? = null,
    val turnCount: Int = 0,
) {
    val isBusy: Boolean
        get() = status == LiveStatus.THINKING || status == LiveStatus.RESPONDING
}

interface SessionListener {
    fun onUserTranscriptFinal(text: String)
    fun onAssistantStart(voice: Boolean)
    fun onAssistantDelta(text: String)
    fun onAssistantFinished(text: String, metrics: TurnMetrics, partial: Boolean)
    fun onToolCall(name: String, arguments: String, result: String?)
    fun onError(message: String)
    fun onUsage(usage: TokenUsage, costUsd: Double)
}

/**
 * Protocol state machine shared by every transport: opens a session, enforces
 * the text-vs-voice output policy, handles barge-in, executes tools and collects
 * latency/usage metrics.
 */
class RealtimeSession(
    private val scope: CoroutineScope,
    private val transportFactory: (TransportKind) -> RealtimeTransport,
    private val tools: ToolRegistry,
    private val listener: SessionListener,
) {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var transport: RealtimeTransport? = null
    private var eventsJob: Job? = null
    private var levelJob: Job? = null
    private var config: SessionConfig? = null

    private var responding = false
    private var assistantStarted = false
    private var currentTurnVoice = false
    private var buffer = StringBuilder()
    private var deltaChannel: DeltaChannel = DeltaChannel.NONE
    private var turnStartedAt = 0L
    private var connectStartedAt = 0L
    private var firstDeltaAt = 0L
    private var turnUsage: TokenUsage? = null
    private var liveEnabled = false
    private var toolOutputsSent = false
    private val handledCalls = mutableSetOf<String>()

    private enum class DeltaChannel { NONE, TEXT, TRANSCRIPT }

    suspend fun start(config: SessionConfig, live: Boolean): Result<Unit> = mutex.withLock {
        val current = transport
        if (current != null && this.config == config && current.state.value == TransportState.CONNECTED) {
            applyLiveMode(live)
            return@withLock Result.success(Unit)
        }
        stopLocked()
        this.config = config
        connectStartedAt = SystemClock.elapsedRealtime()
        liveEnabled = live
        _state.value = SessionUiState(
            active = true,
            connected = false,
            liveEnabled = live,
            status = LiveStatus.CONNECTING,
            transport = config.transport,
            model = config.model,
            voice = config.voice,
        )
        val newTransport = transportFactory(config.transport)
        transport = newTransport
        eventsJob = scope.launch {
            newTransport.events.collect { event -> handleEvent(event) }
        }
        levelJob = scope.launch {
            newTransport.micLevel.collect { level ->
                _state.update { if (it.liveEnabled) it.copy(micLevel = level) else it.copy(micLevel = 0f) }
            }
        }
        val result = newTransport.connect(config)
        if (result.isSuccess) {
            newTransport.setMicEnabled(live)
            if (!newTransport.send(SessionPayloads.sessionUpdate(config))) {
                AppLog.warn(LogCategory.SESSION, "session.update could not be sent")
            }
            _state.update {
                it.copy(
                    active = true,
                    connected = true,
                    liveEnabled = live,
                    status = if (live) LiveStatus.LISTENING else LiveStatus.IDLE,
                )
            }
            AppLog.info(
                LogCategory.SESSION,
                "Session started",
                "model=${config.model} transport=${config.transport} live=$live",
            )
        } else {
            val message = result.exceptionOrNull()?.message ?: "Connection failed"
            _state.update {
                it.copy(
                    active = true,
                    connected = false,
                    status = LiveStatus.ERROR,
                    error = message,
                )
            }
            listener.onError(message)
        }
        result
    }

    suspend fun sendText(text: String, images: List<String> = emptyList()): Result<Unit> =
        mutex.withLock {
            val activeTransport = transport
            if (activeTransport == null || activeTransport.state.value != TransportState.CONNECTED) {
                return@withLock Result.failure(IllegalStateException("Session is not connected."))
            }
            if (responding) {
                activeTransport.send(SessionPayloads.cancelResponse())
                finishTurn(partial = true)
            }
            if (images.isNotEmpty() && liveEnabled) {
                // Image uploads need the input buffer to be committed; stop
                // listening for this turn without touching turn_detection.
                liveEnabled = false
                activeTransport.setMicEnabled(false)
                _state.update { it.copy(liveEnabled = false, status = LiveStatus.IDLE) }
            }
            beginTurn(voice = false)
            _state.update {
                it.copy(status = LiveStatus.THINKING, assistantPartial = "", userPartial = "")
            }
            SessionPayloads.textTurn(text, images).forEach { event ->
                if (!activeTransport.send(event)) {
                    AppLog.warn(LogCategory.SESSION, "Could not send text turn event")
                }
            }
            AppLog.info(
                LogCategory.SESSION,
                "Text turn sent",
                "chars=${text.length} images=${images.size}",
            )
            Result.success(Unit)
        }

    suspend fun setLiveMode(enabled: Boolean) = mutex.withLock {
        applyLiveMode(enabled)
    }

    suspend fun interrupt() = mutex.withLock {
        val activeTransport = transport ?: return@withLock
        if (responding) {
            activeTransport.setSpeakerMuted(true)
            activeTransport.send(SessionPayloads.cancelResponse())
            finishTurn(partial = true)
        }
        _state.update {
            it.copy(
                status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE,
                speaking = false,
            )
        }
    }

    suspend fun stop() = mutex.withLock { stopLocked() }

    /** Non-suspending cleanup for ViewModel/process teardown. */
    fun shutdown() {
        eventsJob?.cancel()
        eventsJob = null
        levelJob?.cancel()
        levelJob = null
        runCatching { transport?.close() }
        transport = null
        config = null
        responding = false
        _state.value = SessionUiState()
    }

    fun currentConfig(): SessionConfig? = config

    fun dismissError() {
        _state.update {
            it.copy(
                error = null,
                status = when {
                    !it.active -> LiveStatus.IDLE
                    it.liveEnabled -> LiveStatus.LISTENING
                    else -> LiveStatus.IDLE
                },
            )
        }
    }

    private fun applyLiveMode(enabled: Boolean) {
        liveEnabled = enabled
        // Never change turn_detection on an established session: the server
        // rejects it ("cannot change turn detection") and the conversation
        // context would be lost by reconnecting. The session is always opened
        // with the configured VAD; live mode only starts/stops the microphone.
        transport?.setMicEnabled(enabled)
        _state.update {
            it.copy(
                liveEnabled = enabled,
                status = when {
                    !enabled -> LiveStatus.IDLE
                    it.connected -> LiveStatus.LISTENING
                    else -> it.status
                },
                micLevel = if (enabled) it.micLevel else 0f,
            )
        }
        AppLog.info(LogCategory.SESSION, "Live mode ${if (enabled) "on" else "off"}")
    }

    private suspend fun stopLocked() {
        finishTurn(partial = true)
        eventsJob?.cancel()
        eventsJob = null
        levelJob?.cancel()
        levelJob = null
        transport?.close()
        transport = null
        config = null
        responding = false
        liveEnabled = false
        handledCalls.clear()
        _state.value = SessionUiState()
    }

    private fun beginTurn(voice: Boolean) {
        currentTurnVoice = voice
        responding = true
        assistantStarted = false
        buffer = StringBuilder()
        deltaChannel = DeltaChannel.NONE
        turnStartedAt = SystemClock.elapsedRealtime()
        firstDeltaAt = 0L
        turnUsage = null
        toolOutputsSent = false
        handledCalls.clear()
        transport?.setSpeakerMuted(!voice)
    }

    private suspend fun handleEvent(event: RealtimeEvent) = mutex.withLock {
        when (event) {
            is RealtimeEvent.SessionUpdated -> {
                val latency = SystemClock.elapsedRealtime() - connectStartedAt
                if (_state.value.connectMs < 0) {
                    _state.update { it.copy(connectMs = latency, connected = true) }
                }
                AppLog.info(LogCategory.SESSION, "Session configured", "id=${event.sessionId} ${latency}ms")
            }

            is RealtimeEvent.SpeechStarted -> {
                AppLog.debug(LogCategory.SESSION, "Speech started (barge-in)")
                transport?.setSpeakerMuted(true)
                if (responding) {
                    // The server has interrupt_response=true and cancels the
                    // response itself; sending response.cancel here races it.
                    finishTurn(partial = true)
                }
                _state.update {
                    it.copy(
                        status = LiveStatus.LISTENING,
                        userPartial = "",
                        assistantPartial = "",
                        speaking = false,
                    )
                }
            }

            is RealtimeEvent.SpeechStopped -> {
                AppLog.debug(LogCategory.SESSION, "Speech stopped")
                beginTurn(voice = true)
                _state.update { it.copy(status = LiveStatus.THINKING, speaking = false) }
            }

            is RealtimeEvent.UserTranscriptDelta -> {
                val partial = (event.text + event.stash).trim()
                _state.update {
                    it.copy(
                        userPartial = partial,
                        status = if (responding) it.status else LiveStatus.LISTENING,
                    )
                }
            }

            is RealtimeEvent.UserTranscriptCompleted -> {
                val transcript = event.transcript.trim()
                _state.update { it.copy(userPartial = transcript) }
                if (transcript.isNotBlank()) listener.onUserTranscriptFinal(transcript)
            }

            is RealtimeEvent.ResponseCreated -> {
                responding = true
                transport?.setSpeakerMuted(!currentTurnVoice)
                _state.update {
                    it.copy(status = LiveStatus.THINKING, speaking = false, error = null)
                }
                AppLog.debug(LogCategory.SESSION, "Response created", event.responseId)
            }

            is RealtimeEvent.AssistantTextDelta -> {
                if (deltaChannel == DeltaChannel.TRANSCRIPT) return@withLock
                deltaChannel = DeltaChannel.TEXT
                appendDelta(event.delta)
            }

            is RealtimeEvent.AssistantTranscriptDelta -> {
                if (deltaChannel == DeltaChannel.TEXT) return@withLock
                deltaChannel = DeltaChannel.TRANSCRIPT
                appendDelta(event.delta)
            }

            is RealtimeEvent.AssistantTextDone -> {
                if (deltaChannel != DeltaChannel.TRANSCRIPT) {
                    applySnapshot(event.text)
                }
            }

            is RealtimeEvent.AssistantTranscriptDone -> {
                if (event.text.isNotBlank() && deltaChannel != DeltaChannel.TEXT) {
                    applySnapshot(event.text)
                }
            }

            is RealtimeEvent.AssistantAudioDelta -> {
                // Text turns stay silent even if the server streams audio: the
                // transport is already muted for this turn.
                if (!currentTurnVoice) return@withLock
                markFirstDelta()
                _state.update {
                    it.copy(
                        status = LiveStatus.RESPONDING,
                        speaking = true,
                        assistantPartial = buffer.toString(),
                    )
                }
            }

            is RealtimeEvent.FunctionCall -> handleFunctionCall(event)

            is RealtimeEvent.ResponseDone -> {
                val usage = event.usage
                turnUsage = usage
                val metrics = buildMetrics(usage)
                val partial = event.status != null && event.status != "completed"
                finishTurn(partial = partial)
                val completed = event.status == null || event.status == "completed"
                val needsFollowUp = toolOutputsSent && completed
                toolOutputsSent = false
                if (needsFollowUp) {
                    val modalities = if (currentTurnVoice) listOf("text", "audio") else listOf("text")
                    transport?.send(SessionPayloads.responseCreate(modalities))
                    _state.update {
                        it.copy(
                            status = LiveStatus.THINKING,
                            speaking = false,
                            ttftMs = metrics.ttftMs,
                            turnCount = it.turnCount + 1,
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE,
                            speaking = false,
                            ttftMs = metrics.ttftMs,
                            turnCount = it.turnCount + 1,
                        )
                    }
                }
                usage?.let { listener.onUsage(it, metrics.costUsd) }
                AppLog.info(
                    LogCategory.SESSION,
                    "Response done",
                    "status=${event.status} ttft=${metrics.ttftMs}ms total=${metrics.totalMs}ms " +
                        "tokens=${usage?.totalTokens ?: 0}",
                )
            }

            is RealtimeEvent.ResponseCancelled -> {
                AppLog.debug(LogCategory.SESSION, "Response cancelled", event.reason)
                finishTurn(partial = true)
                _state.update {
                    it.copy(status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE)
                }
            }

            is RealtimeEvent.Error -> {
                AppLog.error(LogCategory.SESSION, "Server error", event.message)
                _state.update { it.copy(status = LiveStatus.ERROR, error = event.message) }
                listener.onError(event.message)
            }

            is RealtimeEvent.Failure -> {
                finishTurn(partial = true)
                _state.update {
                    it.copy(
                        status = LiveStatus.ERROR,
                        connected = false,
                        error = event.message,
                    )
                }
                listener.onError(event.message)
            }

            is RealtimeEvent.Closed -> {
                if (_state.value.active) {
                    finishTurn(partial = true)
                    _state.update {
                        it.copy(
                            status = LiveStatus.IDLE,
                            connected = false,
                            active = false,
                            liveEnabled = false,
                        )
                    }
                    if (!responding) {
                        AppLog.info(LogCategory.SESSION, "Socket closed", "code=${event.code}")
                    }
                }
            }

            is RealtimeEvent.Unknown -> {
                AppLog.verbose(LogCategory.PROTOCOL, "Unhandled event", event.type)
            }
        }
    }

    private suspend fun handleFunctionCall(event: RealtimeEvent.FunctionCall) {
        if (event.callId.isBlank() || event.name.isBlank()) return
        if (!handledCalls.add(event.callId)) return
        val activeTransport = transport ?: return
        if (!_state.value.connected) return
        AppLog.info(LogCategory.TOOLS, "Model requested ${event.name}", event.arguments)
        listener.onToolCall(event.name, event.arguments, null)
        _state.update { it.copy(status = LiveStatus.THINKING) }
        val result = tools.execute(event.name, event.arguments)
        listener.onToolCall(event.name, event.arguments, result)
        val responseAlreadyFinished = !responding
        activeTransport.send(SessionPayloads.functionOutputItem(event.callId, result))
        if (responseAlreadyFinished) {
            val modalities = if (currentTurnVoice) listOf("text", "audio") else listOf("text")
            activeTransport.send(SessionPayloads.responseCreate(modalities))
            toolOutputsSent = false
        } else {
            toolOutputsSent = true
        }
    }

    private fun appendDelta(delta: String) {
        if (delta.isEmpty()) return
        if (!assistantStarted) {
            assistantStarted = true
            listener.onAssistantStart(currentTurnVoice)
        }
        buffer.append(delta)
        markFirstDelta()
        _state.update {
            it.copy(
                status = LiveStatus.RESPONDING,
                assistantPartial = buffer.toString(),
                speaking = it.speaking || currentTurnVoice,
            )
        }
        listener.onAssistantDelta(buffer.toString())
    }

    private fun applySnapshot(text: String) {
        if (text.isEmpty()) return
        if (!assistantStarted) {
            assistantStarted = true
            listener.onAssistantStart(currentTurnVoice)
        }
        buffer = StringBuilder(text)
        markFirstDelta()
        _state.update {
            it.copy(
                status = LiveStatus.RESPONDING,
                assistantPartial = text,
                speaking = it.speaking || currentTurnVoice,
            )
        }
        listener.onAssistantDelta(text)
    }

    private fun markFirstDelta() {
        if (firstDeltaAt == 0L) {
            firstDeltaAt = SystemClock.elapsedRealtime()
        }
    }

    private fun buildMetrics(usage: TokenUsage?): TurnMetrics {
        val end = SystemClock.elapsedRealtime()
        val ttft = if (firstDeltaAt > 0L) firstDeltaAt - turnStartedAt else -1
        val cost = usage?.let {
            CostCalculator.cost(
                it,
                config?.priceInputPerMillion ?: 0.0,
                config?.priceOutputPerMillion ?: 0.0,
            )
        } ?: 0.0
        return TurnMetrics(
            ttftMs = ttft,
            totalMs = if (turnStartedAt > 0L) end - turnStartedAt else -1,
            usage = usage,
            costUsd = cost,
        )
    }

    private fun finishTurn(partial: Boolean) {
        if (!responding && !assistantStarted && buffer.isEmpty()) {
            responding = false
            return
        }
        val metrics = buildMetrics(turnUsage)
        val text = buffer.toString().trim()
        if (assistantStarted || text.isNotEmpty()) {
            listener.onAssistantFinished(text, metrics, partial)
        }
        assistantStarted = false
        buffer = StringBuilder()
        deltaChannel = DeltaChannel.NONE
        responding = false
        _state.update {
            it.copy(
                assistantPartial = "",
                speaking = false,
            )
        }
    }
}
