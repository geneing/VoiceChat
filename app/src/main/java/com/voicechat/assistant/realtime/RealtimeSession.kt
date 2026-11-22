package com.voicechat.assistant.realtime

import android.os.SystemClock
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.IssueReportCommand
import com.voicechat.assistant.tutor.ConversationMode
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.CostCalculator
import com.voicechat.assistant.data.model.SearchProvider
import com.voicechat.assistant.data.model.TokenUsage
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.data.model.TurnMetrics
import com.voicechat.assistant.realtime.handoff.HandoffConfig
import com.voicechat.assistant.realtime.handoff.HandoffException
import com.voicechat.assistant.realtime.handoff.HandoffPhase
import com.voicechat.assistant.realtime.handoff.HandoffPrompt
import com.voicechat.assistant.realtime.handoff.HandoffRouter
import com.voicechat.assistant.realtime.handoff.HandoffTrace
import com.voicechat.assistant.realtime.handoff.HandoffUiState
import com.voicechat.assistant.realtime.handoff.NewsPrompt
import com.voicechat.assistant.realtime.handoff.ReasoningClient
import com.voicechat.assistant.realtime.handoff.ReasoningResult
import com.voicechat.assistant.realtime.handoff.SpeculativeHandoff
import com.voicechat.assistant.realtime.tools.SearchRecency
import com.voicechat.assistant.realtime.tools.ToolDefinitions
import com.voicechat.assistant.realtime.tools.ToolRegistry
import com.voicechat.assistant.realtime.tools.DeviceActions
import com.voicechat.assistant.realtime.tools.isPhoneConfirmation
import com.voicechat.assistant.realtime.tools.string
import com.voicechat.assistant.realtime.tools.toolError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.time.LocalDate
import kotlin.coroutines.coroutineContext

enum class LiveStatus { IDLE, CONNECTING, RECONNECTING, LISTENING, THINKING, RESPONDING, ERROR }

data class SessionUiState(
    val active: Boolean = false,
    val connected: Boolean = false,
    val liveEnabled: Boolean = false,
    val status: LiveStatus = LiveStatus.IDLE,
    val transport: TransportKind? = null,
    val model: String? = null,
    val voice: String? = null,
    val assistantPartial: String = "",
    val micLevel: Float = 0f,
    val speaking: Boolean = false,
    val connectMs: Long = -1,
    val ttftMs: Long = -1,
    val error: String? = null,
    val turnCount: Int = 0,
    val audioSentBytes: Long = 0,
    val audioReceivedBytes: Long = 0,
    val realtimeUsage: TokenUsage? = null,
    val sendingAudio: Boolean = false,
    val receivingAudio: Boolean = false,
    val speechActive: Boolean = false,
    /** True while a dropped connection is being restored automatically. */
    val reconnecting: Boolean = false,
    /** 1-based number of the recovery attempt in flight. */
    val reconnectAttempt: Int = 0,
    /** Non-null while the app is consulting the reasoning model. */
    val handoff: HandoffUiState? = null,
) {
    val isBusy: Boolean
        get() = status == LiveStatus.THINKING || status == LiveStatus.RESPONDING
}

interface SessionListener {
    /** The current recognition preview. Empty text clears an unfinished preview. */
    fun onUserTranscriptPartial(text: String)
    fun onUserTranscriptFinal(text: String)
    fun onIssueReportRequested(description: String)
    fun onModeSwitchRequested(mode: ConversationMode) {}
    fun onAssistantStart(voice: Boolean)
    fun onAssistantDelta(text: String)
    fun onAssistantFinished(text: String, metrics: TurnMetrics, partial: Boolean)
    fun onToolCall(name: String, arguments: String, result: String?)
    fun onError(message: String)
    fun onUsage(usage: TokenUsage, costUsd: Double)

    /** A deep-reasoning handoff finished (or failed) and is worth recording. */
    fun onHandoff(trace: HandoffTrace) {}
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
    /** Consulted for `deep_reasoning`; `null` disables the handoff entirely. */
    private val reasoner: ReasoningClient? = null,
    /**
     * The facts remembered from earlier chats, read once when a session opens.
     *
     * A lambda rather than a field in [SessionConfig]: memory changes while a
     * conversation runs (a `remember_fact` call writes a row), and a changing
     * config would make `ensureSession` restart the session and lose its
     * context. Refresh happens on the next open, which is also when the server
     * conversation is new anyway.
     */
    private val memory: suspend () -> String = { "" },
) {

    private val _state = MutableStateFlow(SessionUiState())
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private val mutex = Mutex()
    private var transport: RealtimeTransport? = null
    private var modeSwitchPending = false
    private var eventsJob: Job? = null
    private var levelJob: Job? = null
    private var trafficJob: Job? = null
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
    /** True once the current turn executed or dispatched a tool call. */
    private var turnUsedTool = false
    /** True once a turn that ended on a promise has already been retried. */
    private var nudgedThisTurn = false
    private val handledCalls = mutableSetOf<String>()
    private val drivingJobs = mutableMapOf<String, Job>()
    private var pendingPhoneAction: DeviceActions.Pending? = null
    private var confirmedPhoneAction = false

    /** Non-null while the automatic reconnect loop is running. */
    private var recoveryJob: Job? = null
    /** Wakes the recovery loop early, e.g. when the network becomes available. */
    private var recoveryWake: Channel<Unit>? = null
    @Volatile
    private var recovering = false
    /** True while the current transport is being closed on purpose. */
    @Volatile
    private var closingTransport = false
    /**
     * Recovery ran out of attempts while the device had no network. Kept so a
     * later network change revives the session without the user tapping Retry.
     */
    @Volatile
    private var awaitingNetwork = false

    /** Conversation history handed to the reasoning model as context. */
    private val transcript = ArrayDeque<HandoffPrompt.Turn>()
    private var handoffJob: Job? = null
    private var handoffStartedAt = 0L
    private var handoffCancelled = false
    /** Digest request in flight; `get_news` runs off the session mutex. */
    private var newsJob: Job? = null
    private var newsCancelled = false
    /** True while a typed turn is being reasoned about, before it is sent. */
    private var preReasoning = false
    /** Warm reasoning call started from a hard voice transcript. */
    private val speculation = SpeculativeHandoff()
    private var speculativeJob: Job? = null

    @Volatile
    private var handoffOutcome: Result<ReasoningResult>? = null

    private enum class DeltaChannel { NONE, TEXT, TRANSCRIPT }

    private companion object {
        val TOOL_NAME = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
        val JSON = Json { ignoreUnknownKeys = true }
        const val MAX_TRANSCRIPT_TURNS = 16

        /**
         * Per-response instruction for a turn that ended on "I'll check that".
         * It repeats the tool rule so the retry either calls the tool or
         * answers; it never adds a user item to the conversation.
         */
        const val DEAD_TURN_NUDGE =
            "Your previous response only announced an action and then ended. " +
                "Do not announce anything now. If the answer needs a tool, call it " +
                "immediately in this response; otherwise answer the user's question " +
                "directly and completely."
    }

    private data class PendingHandoff(
        val config: HandoffConfig,
        val apiKey: String,
        val context: List<HandoffPrompt.Turn>,
    )

    suspend fun start(config: SessionConfig, live: Boolean): Result<Unit> {
        cancelRecovery("starting a new session")
        var failure: Throwable? = null
        for (attempt in 1..ReconnectPolicy.INITIAL_ATTEMPTS) {
            val result = mutex.withLock { startOnce(config, live, attempt) }
            if (result.isSuccess) return result
            failure = result.exceptionOrNull()
            AppLog.warn(
                LogCategory.TRANSPORT,
                "Connect attempt $attempt failed",
                failure?.message,
            )
            if (attempt == ReconnectPolicy.INITIAL_ATTEMPTS ||
                ReconnectPolicy.isPermanent(failure?.message)
            ) {
                break
            }
            val wait = ReconnectPolicy.delayMs(attempt)
            _state.update {
                it.copy(
                    active = true,
                    connected = false,
                    status = LiveStatus.CONNECTING,
                    reconnecting = true,
                    reconnectAttempt = attempt + 1,
                    error = null,
                )
            }
            delay(wait)
        }
        val message = failure?.message ?: "Connection failed"
        if (!ReconnectPolicy.isPermanent(message)) {
            // A DNS or interface glitch while opening the session is usually
            // transient. Give up on this turn but keep the session alive and let
            // the recovery loop (and the network callback through
            // nudgeReconnect) restore it, instead of leaving voice mode dead
            // until the user finds the microphone button again.
            _state.update {
                it.copy(
                    active = true,
                    connected = false,
                    status = LiveStatus.RECONNECTING,
                    error = message,
                    reconnecting = true,
                    reconnectAttempt = 0,
                )
            }
            AppLog.warn(
                LogCategory.TRANSPORT,
                "Initial connect failed; retrying in the background",
                message,
            )
            scheduleRecovery(message)
            listener.onError(message)
            return Result.failure(failure ?: IllegalStateException(message))
        }
        _state.update {
            it.copy(
                active = true,
                connected = false,
                status = LiveStatus.ERROR,
                error = message,
                reconnecting = false,
                reconnectAttempt = 0,
            )
        }
        listener.onError(message)
        return Result.failure(failure ?: IllegalStateException(message))
    }

    private suspend fun startOnce(config: SessionConfig, live: Boolean, attempt: Int): Result<Unit> {
        val current = transport
        if (current != null && this.config == config && current.state.value == TransportState.CONNECTED) {
            applyLiveMode(live)
            return Result.success(Unit)
        }
        closeTransportLocked()
        this.config = config
        liveEnabled = live
        cancelSpeculation("new connection")
        _state.value = SessionUiState(
            active = true,
            connected = false,
            liveEnabled = live,
            status = LiveStatus.CONNECTING,
            transport = config.transport,
            model = config.model,
            voice = config.voice,
        )
        return openTransport(config, live, attempt)
    }

    /** Creates a transport, opens it and configures the session. */
    private suspend fun openTransport(
        config: SessionConfig,
        live: Boolean,
        attempt: Int,
    ): Result<Unit> {
        connectStartedAt = SystemClock.elapsedRealtime()
        val newTransport = transportFactory(config.transport)
        transport = newTransport
        closingTransport = false
        attachCollectors(newTransport)
        val timeoutMs = ReconnectPolicy.connectTimeoutMs(attempt)
        val result = withTimeoutOrNull(timeoutMs) { newTransport.connect(config) }
            ?: Result.failure(IOException("Timed out while opening the session (${timeoutMs}ms)."))
        if (result.isFailure) {
            eventsJob?.cancel()
            eventsJob = null
            levelJob?.cancel()
            levelJob = null
            trafficJob?.cancel()
            trafficJob = null
            closingTransport = true
            runCatching { newTransport.close() }
            transport = null
            _state.update { it.copy(connected = false) }
            return result
        }
        newTransport.setMicEnabled(live)
        // A restored session is a fresh server conversation; hand the turns the
        // client still remembers back as part of the instructions.
        val payloadConfig = if (transcript.isEmpty()) {
            config
        } else {
            config.copy(
                instructions = SessionPayloads.resumeInstructions(config.instructions, transcript.toList()),
            )
        }
        val memoryBlock = readMemory()
        if (!newTransport.send(SessionPayloads.sessionUpdate(payloadConfig, memoryBlock = memoryBlock))) {
            AppLog.warn(LogCategory.SESSION, "session.update could not be sent")
        }
        _state.update {
            it.copy(
                active = true,
                connected = true,
                liveEnabled = live,
                status = if (live) LiveStatus.LISTENING else LiveStatus.IDLE,
                reconnecting = false,
                reconnectAttempt = 0,
                error = null,
                speaking = false,
                speechActive = false,
            )
        }
        AppLog.info(
            LogCategory.SESSION,
            "Session started",
            "model=${config.model} transport=${config.transport} live=$live " +
                "restoredTurns=${transcript.size} tools=${toolNames(config.toolsJson)}",
        )
        return Result.success(Unit)
    }

    /**
     * Memory is optional context: a database problem must never stop a session
     * from opening.
     */
    private suspend fun readMemory(): String = try {
        memory()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        AppLog.warn(LogCategory.MEMORY, "Could not read device memory", e.message)
        ""
    }

    private fun attachCollectors(newTransport: RealtimeTransport) {
        eventsJob = scope.launch {
            newTransport.events.collect { event -> handleEvent(event) }
        }
        levelJob = scope.launch {
            newTransport.micLevel.collect { level ->
                _state.update { if (it.liveEnabled) it.copy(micLevel = level) else it.copy(micLevel = 0f) }
            }
        }
        trafficJob = scope.launch {
            while (isActive) {
                val traffic = newTransport.audioTraffic.value
                val now = System.nanoTime() / 1_000_000
                _state.update {
                    it.copy(
                        audioSentBytes = traffic.sentBytes,
                        audioReceivedBytes = traffic.receivedBytes,
                        sendingAudio = traffic.lastSentAtMs > 0 && now - traffic.lastSentAtMs < 600,
                        receivingAudio = traffic.lastReceivedAtMs > 0 &&
                            now - traffic.lastReceivedAtMs < 600,
                    )
                }
                delay(200)
            }
        }
    }

    /**
     * Restores a dropped connection: keeps the transcript and live mode, and
     * retries with a short backoff so a flaky link does not end the chat.
     */
    private fun scheduleRecovery(reason: String) {
        if (recovering) return
        val currentConfig = config ?: return
        if (!_state.value.active) return
        recovering = true
        awaitingNetwork = false
        val wake = Channel<Unit>(Channel.CONFLATED)
        recoveryWake = wake
        recoveryJob = scope.launch {
            var lastError = reason
            var restored = false
            for (attempt in 1..ReconnectPolicy.RECOVERY_ATTEMPTS) {
                val wait = ReconnectPolicy.delayMs(attempt)
                _state.update {
                    it.copy(
                        connected = false,
                        status = LiveStatus.RECONNECTING,
                        reconnecting = true,
                        reconnectAttempt = attempt,
                        error = null,
                        speaking = false,
                        speechActive = false,
                    )
                }
                AppLog.warn(
                    LogCategory.TRANSPORT,
                    "Connection lost; retrying",
                    "attempt=$attempt/${ReconnectPolicy.RECOVERY_ATTEMPTS} in ${wait}ms: $reason",
                )
                // A network callback can wake the loop before the backoff ends.
                withTimeoutOrNull(wait) { wake.receive() }
                // Read the mode at attempt time: the user may have stopped or
                // (re)started voice mode while the link was down. Every recovery
                // attempt is a retry, so it uses the short connect timeout.
                val result = mutex.withLock { reopenLocked(currentConfig, liveEnabled) }
                if (result.isSuccess) {
                    AppLog.info(
                        LogCategory.TRANSPORT,
                        "Connection recovered",
                        "attempt=$attempt",
                    )
                    restored = true
                    break
                }
                lastError = result.exceptionOrNull()?.message ?: lastError
                if (ReconnectPolicy.isPermanent(lastError)) break
            }
            recovering = false
            recoveryWake = null
            recoveryJob = null
            if (!restored) {
                AppLog.error(LogCategory.TRANSPORT, "Automatic recovery failed", lastError)
                // Keep the session alive instead of tearing voice mode down: a
                // DNS or interface outage is usually transient, and the network
                // callback revives the loop (nudgeReconnect) when it ends.
                awaitingNetwork = true
                _state.update {
                    it.copy(
                        active = true,
                        connected = false,
                        status = LiveStatus.ERROR,
                        error = lastError,
                        reconnecting = false,
                        reconnectAttempt = 0,
                        speechActive = false,
                    )
                }
                listener.onError("Connection lost: $lastError")
                AppLog.info(
                    LogCategory.TRANSPORT,
                    "Waiting for the network",
                    "recovery resumes when an interface comes back",
                )
            }
        }
    }

    private suspend fun reopenLocked(config: SessionConfig, live: Boolean): Result<Unit> {
        listener.onUserTranscriptPartial("")
        finishTurn(partial = true)
        cancelSpeculation("connection lost")
        cancelNews("connection lost")
        cancelDrivingTools(clearConfirmation = true)
        eventsJob?.cancel()
        eventsJob = null
        levelJob?.cancel()
        levelJob = null
        trafficJob?.cancel()
        trafficJob = null
        closingTransport = true
        runCatching { transport?.close() }
        transport = null
        responding = false
        handledCalls.clear()
        _state.update {
            it.copy(
                connected = false,
                status = LiveStatus.RECONNECTING,
                audioSentBytes = 0,
                audioReceivedBytes = 0,
                realtimeUsage = null,
            )
        }
        // attempt = 2 selects the retry-sized connect timeout.
        return openTransport(config, live, attempt = 2)
    }

    /** Cancels a running recovery loop, e.g. when the user stops the session. */
    private fun cancelRecovery(reason: String) {
        awaitingNetwork = false
        val job = recoveryJob ?: return
        recoveryJob = null
        recoveryWake = null
        recovering = false
        job.cancel()
        AppLog.debug(LogCategory.TRANSPORT, "Recovery cancelled", reason)
    }

    /**
     * Wakes the recovery loop so it retries immediately, for example when the
     * device moves back onto Wi-Fi or a tunnel finally has signal. If recovery
     * already gave up during a network outage, this restarts it.
     */
    fun nudgeReconnect() {
        if (recovering) {
            recoveryWake?.trySend(Unit)
            return
        }
        // `awaitingNetwork` is only set once the transport has been torn down, so
        // a restart is always safe here. The old `closingTransport` gate kept a
        // phone that got its network back dead until the user tapped the
        // microphone again: that flag is still true from the last failed open.
        if (awaitingNetwork && _state.value.active && config != null) {
            awaitingNetwork = false
            AppLog.info(LogCategory.TRANSPORT, "Network is back; retrying the session")
            scheduleRecovery("network available")
        }
    }

    /**
     * The device moved to a different default network (Wi-Fi to mobile or back).
     * The socket was dialled on the previous interface and its route is gone, so
     * the session is redialled on the new one instead of waiting for the
     * keep-alive to time out. The transcript recap keeps the conversation going
     * and live mode is restored with the new transport.
     */
    fun onNetworkChanged() {
        if (recovering) {
            recoveryWake?.trySend(Unit)
            return
        }
        if (awaitingNetwork) {
            nudgeReconnect()
            return
        }
        if (!_state.value.active || config == null) return
        AppLog.warn(LogCategory.TRANSPORT, "Network switch; reconnecting on the new interface")
        scheduleRecovery("network switched")
    }


    /**
     * Answer typed turns directly with the reasoning model and add the finished
     * exchange to the realtime conversation for future voice turns.
     */
    suspend fun sendText(
        text: String,
        images: List<String> = emptyList(),
        typedReasoning: HandoffConfig? = null,
        userInput: String = text,
    ): Result<Unit> {
        if (typedReasoning == null || reasoner == null) return Result.failure(IllegalStateException(
            "The reasoning model is not configured."
        ))
        if (images.isNotEmpty() && !typedReasoning.modelInfo.supportsImages) return Result.failure(IllegalArgumentException(
            "The configured reasoning model cannot receive image attachments."
        ))
        val pending: PendingHandoff? = mutex.withLock {
            val activeTransport = transport
            if (activeTransport == null || activeTransport.state.value != TransportState.CONNECTED) {
                return Result.failure(IllegalStateException("Session is not connected."))
            }
            if (responding) {
                activeTransport.send(SessionPayloads.cancelResponse())
                finishTurn(partial = true)
            }
            if (liveEnabled) {
                // Typing is an explicit switch to text mode. Voice input must
                // only start from the sound-wave button, so stop listening
                // (and AOQ mic publishing) for this turn.
                applyLiveMode(false)
            }
            beginTurn(voice = false)
            observePhoneConfirmation(userInput)
            cancelSpeculation("typed turn")
            _state.update {
                it.copy(
                    status = LiveStatus.THINKING,
                    assistantPartial = "",
                    speechActive = false,
                )
            }
            PendingHandoff(typedReasoning, config?.apiKey.orEmpty(), transcript.toList())
        }
        val handoffConfig = pending ?: return Result.failure(IllegalStateException("Reasoning is unavailable."))
        val reasonerClient = reasoner
            ?: return Result.failure(IllegalStateException("Reasoning is unavailable."))

        // The reasoning request runs outside the session mutex: barge-in, the
        // stop button and status updates must keep working while it streams.
        handoffStartedAt = SystemClock.elapsedRealtime()
        handoffCancelled = false
        handoffOutcome = null
        preReasoning = true
        _state.update { it.copy(handoff = HandoffUiState(model = handoffConfig.config.resolvedModel)) }
        AppLog.info(
            LogCategory.HANDOFF,
            "Pre-reasoning typed turn",
            "model=${handoffConfig.config.model} chars=${text.length}",
        )
        handoffJob = scope.launch {
            val context = HandoffPrompt.messages(handoffConfig.context, text, handoffConfig.config.contextTurns).last().text
            val instructions = config?.instructions.orEmpty() + "\n\n" + readMemory() +
                "\nAnswer the user's latest typed message directly. Use the conversation for context. " +
                "Do not pretend to operate device tools. When search is available use it for current facts."
            val result = reasonerClient.complete(
                apiKey = handoffConfig.apiKey,
                config = handoffConfig.config,
                system = instructions,
                user = context,
                images = images,
            )
            billReasoning(handoffConfig.config, result)
            handoffOutcome = result
        }
        handoffJob?.join()
        handoffJob = null
        preReasoning = false
        val outcome = handoffOutcome
        handoffOutcome = null
        if (handoffCancelled || outcome == null) {
            _state.update { it.copy(handoff = null) }
            return Result.failure(CancellationException("Deep reasoning cancelled."))
        }
        val answer = outcome.getOrElse { error ->
            mutex.withLock {
                responding = false
                _state.update { it.copy(handoff = null, status = LiveStatus.IDLE) }
            }
            return Result.failure(error)
        }
        return mutex.withLock {
            val active = transport ?: return@withLock Result.failure(IllegalStateException("Session is not connected."))
            rememberTurn("user", userInput)
            rememberTurn("assistant", answer.answer)
            if (!active.send(SessionPayloads.typedExchangeContext(userInput, answer.answer))) {
                AppLog.warn(LogCategory.SESSION, "Could not sync typed exchange to voice session")
                listener.onError("The typed answer was saved, but could not be added to the current voice session.")
            }
            responding = false
            listener.onAssistantStart(false)
            listener.onAssistantFinished(answer.answer, TurnMetrics(
                ttftMs = -1,
                totalMs = answer.elapsedMs,
                usage = answer.usage,
                costUsd = CostCalculator.cost(answer.usage, handoffConfig.config.priceInputPerMillion, handoffConfig.config.priceOutputPerMillion),
            ), false)
            _state.update { it.copy(handoff = null, status = LiveStatus.IDLE) }
            Result.success(Unit)
        }
    }

    private fun dispatchTextTurn(
        text: String,
        images: List<String>,
        trace: HandoffTrace? = null,
    ): Result<Unit> {
        val activeTransport = transport
        if (activeTransport == null || activeTransport.state.value != TransportState.CONNECTED) {
            _state.update { it.copy(handoff = null) }
            return Result.failure(IllegalStateException("Session is not connected."))
        }
        val wireText = if (trace != null && !trace.failed && trace.answer.isNotBlank()) {
            HandoffPrompt.questionWithReference(text, trace.answer)
        } else {
            text
        }
        rememberTurn("user", text)
        SessionPayloads.textTurn(wireText, images).forEach { event ->
            if (!activeTransport.send(event)) {
                AppLog.warn(LogCategory.SESSION, "Could not send text turn event")
            }
        }
        _state.update { it.copy(handoff = null, status = LiveStatus.THINKING) }
        AppLog.info(
            LogCategory.SESSION,
            "Text turn sent",
            "chars=${text.length} images=${images.size} handoff=${trace != null}",
        )
        return Result.success(Unit)
    }

    suspend fun setLiveMode(enabled: Boolean) = mutex.withLock {
        applyLiveMode(enabled)
    }

    suspend fun interrupt() = mutex.withLock {
        val activeTransport = transport ?: return@withLock
        activeTransport.interruptPlayback()
        cancelSpeculation("interrupted")
        if (preReasoning) {
            // Nothing has been sent to the server for this turn yet, so there is
            // no response to cancel: just stop paying for the reasoning call.
            cancelHandoff("interrupted")
            cancelNews("interrupted")
            cancelDrivingTools(clearConfirmation = true)
            finishTurn(partial = true)
            _state.update {
                it.copy(status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE, speaking = false)
            }
            return@withLock
        }
        if (responding) {
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

    /** A new live lesson opens proactively, without adding an artificial user turn. */
    suspend fun startTutorLesson() = mutex.withLock {
        if (config?.spanishTutor != true || !liveEnabled || !_state.value.connected || responding) return@withLock
        beginTurn(voice = true)
        transport?.send(SessionPayloads.responseNudge(listOf("text", "audio"),
            "Begin or resume the Spanish lesson from the learning record. Choose a fresh tiny scene " +
                "and ask one lesson question at the learner's current level. Use basic Spanish or " +
                "English for procedural clarifications. Do not ask the learner to choose a topic or exercise."))
    }

    /** Non-suspending cleanup for ViewModel/process teardown. */
    fun shutdown() {
        cancelRecovery("shutdown")
        cancelHandoff("shutdown")
        cancelNews("shutdown")
        cancelDrivingTools(clearConfirmation = true)
        cancelSpeculation("shutdown")
        reasoner?.cancelAll()
        eventsJob?.cancel()
        eventsJob = null
        levelJob?.cancel()
        levelJob = null
        trafficJob?.cancel()
        trafficJob = null
        closingTransport = true
        runCatching { transport?.close() }
        transport = null
        config = null
        responding = false
        transcript.clear()
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
        val wasLive = liveEnabled
        liveEnabled = enabled
        if (!enabled) {
            listener.onUserTranscriptPartial("")
            cancelSpeculation("live mode off")
            cancelNews("live mode off")
            cancelDrivingTools(clearConfirmation = true)
            if (wasLive) {
                // Stop PCM before stopping capture releases Android's communication route.
                transport?.interruptPlayback()
                cancelHandoff("live mode off")
                if (currentTurnVoice && responding) {
                    transport?.send(SessionPayloads.cancelResponse())
                    finishTurn(partial = true)
                }
                currentTurnVoice = false
            }
        }
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
                speechActive = false,
            )
        }
        AppLog.info(LogCategory.SESSION, "Live mode ${if (enabled) "on" else "off"}")
    }

    private suspend fun stopLocked() {
        cancelRecovery("session stopped")
        closeTransportLocked()
        // An explicit stop ends the conversation: the server context is gone and
        // a later start must not hand old turns back.
        transcript.clear()
        _state.value = SessionUiState()
        modeSwitchPending = false
    }

    /** Tears the transport down but keeps the transcript so recovery can restore it. */
    private fun closeTransportLocked() {
        listener.onUserTranscriptPartial("")
        cancelHandoff("session stopped")
        cancelNews("session stopped")
        cancelDrivingTools(clearConfirmation = true)
        cancelSpeculation("session stopped")
        finishTurn(partial = true)
        eventsJob?.cancel()
        eventsJob = null
        levelJob?.cancel()
        levelJob = null
        trafficJob?.cancel()
        trafficJob = null
        closingTransport = true
        runCatching { transport?.close() }
        transport = null
        config = null
        responding = false
        liveEnabled = false
        handledCalls.clear()
    }

    private fun beginTurn(voice: Boolean) {
        cancelDrivingTools()
        confirmedPhoneAction = false
        currentTurnVoice = voice
        responding = true
        assistantStarted = false
        buffer = StringBuilder()
        deltaChannel = DeltaChannel.NONE
        turnStartedAt = SystemClock.elapsedRealtime()
        firstDeltaAt = 0L
        turnUsage = null
        toolOutputsSent = false
        turnUsedTool = false
        nudgedThisTurn = false
        handledCalls.clear()
        transport?.setSpeakerMuted(!voice)
    }

    /**
     * Re-arms the turn state for a corrective `response.create` without
     * counting a new user turn: the retry keeps [nudgedThisTurn] so a second
     * empty promise cannot loop.
     */
    private fun armRetryTurn() {
        responding = true
        assistantStarted = false
        buffer = StringBuilder()
        deltaChannel = DeltaChannel.NONE
        turnStartedAt = SystemClock.elapsedRealtime()
        firstDeltaAt = 0L
        turnUsage = null
        transport?.setSpeakerMuted(!currentTurnVoice)
    }

    private suspend fun handleEvent(event: RealtimeEvent) = mutex.withLock {
        if (modeSwitchPending) return@withLock
        when (event) {
            is RealtimeEvent.SessionUpdated -> {
                val latency = SystemClock.elapsedRealtime() - connectStartedAt
                if (_state.value.connectMs < 0) {
                    _state.update { it.copy(connectMs = latency, connected = true) }
                }
                AppLog.info(LogCategory.SESSION, "Session configured", "id=${event.sessionId} ${latency}ms")
            }

            is RealtimeEvent.SpeechStarted -> {
                if (!liveEnabled) return@withLock
                AppLog.debug(LogCategory.SESSION, "Speech started (barge-in)")
                cancelHandoff("barge-in")
                cancelNews("barge-in")
                cancelDrivingTools()
                confirmedPhoneAction = false
                cancelSpeculation("barge-in")
                transport?.setSpeakerMuted(true)
                listener.onUserTranscriptPartial("")
                if (responding) {
                    // The server has interrupt_response=true and cancels the
                    // response itself; sending response.cancel here races it.
                    finishTurn(partial = true)
                }
                _state.update {
                    it.copy(
                        status = LiveStatus.LISTENING,
                        assistantPartial = "",
                        speaking = false,
                        speechActive = true,
                    )
                }
            }

            is RealtimeEvent.SpeechStopped -> {
                if (!liveEnabled) return@withLock
                AppLog.debug(LogCategory.SESSION, "Speech stopped")
                beginTurn(voice = true)
                _state.update {
                    it.copy(status = LiveStatus.THINKING, speaking = false, speechActive = false)
                }
            }

            is RealtimeEvent.UserTranscriptDelta -> {
                val partial = (event.text + event.stash).trim()
                if (liveEnabled) listener.onUserTranscriptPartial(partial)
                _state.update {
                    it.copy(
                        status = if (responding) it.status else LiveStatus.LISTENING,
                    )
                }
            }

            is RealtimeEvent.UserTranscriptCompleted -> {
                val transcript = event.transcript.trim()
                if (transcript.isNotBlank()) {
                    if (liveEnabled) observePhoneConfirmation(transcript)
                    val issue = IssueReportCommand.description(transcript)
                    val mode = ConversationMode.command(transcript)
                    if (mode != null) {
                        val alreadyActive = (mode == ConversationMode.SPANISH_TUTOR) == (config?.spanishTutor == true)
                        if (alreadyActive) {
                            listener.onUserTranscriptFinal(transcript)
                            return@withLock
                        }
                        modeSwitchPending = true
                        transport?.setSpeakerMuted(true)
                        transport?.send(SessionPayloads.cancelResponse())
                        listener.onUserTranscriptPartial("")
                        listener.onModeSwitchRequested(mode)
                    } else if (issue != null) {
                        listener.onUserTranscriptPartial("")
                        listener.onIssueReportRequested(issue)
                    } else {
                        rememberTurn("user", transcript)
                        startSpeculation(transcript)
                        prefetchNews(transcript)
                        listener.onUserTranscriptFinal(transcript)
                    }
                } else {
                    listener.onUserTranscriptPartial("")
                }
            }

            is RealtimeEvent.ResponseCreated -> {
                responding = true
                transport?.setSpeakerMuted(!currentTurnVoice || !liveEnabled)
                _state.update {
                    it.copy(status = LiveStatus.THINKING, speaking = false, speechActive = false, error = null)
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
                val completed = event.status == null || event.status == "completed"
                val partial = !completed
                // Captured before finishTurn clears it: the dead-turn guard needs
                // the text this response actually produced.
                val spoken = buffer.toString().trim()
                finishTurn(partial = partial)
                // A turn that ended without a handoff leaves the warm call
                // unused; stop paying for it.
                if (handoffJob?.isActive != true) cancelSpeculation("turn finished")
                if (!completed && drivingJobs.isNotEmpty()) cancelDrivingTools(clearConfirmation = true)
                val awaitingTools = drivingJobs.isNotEmpty()
                val needsFollowUp = toolOutputsSent && completed && !awaitingTools
                if (!awaitingTools) toolOutputsSent = false
                if (needsFollowUp) {
                    transport?.send(SessionPayloads.responseCreate(modalities()))
                    _state.update {
                        it.copy(
                            status = LiveStatus.THINKING,
                            speaking = false,
                            speechActive = false,
                            ttftMs = metrics.ttftMs,
                            turnCount = it.turnCount + 1,
                        )
                    }
                } else if (awaitingTools) {
                    _state.update { it.copy(status = LiveStatus.THINKING, speaking = false) }
                } else if (completed &&
                    !turnUsedTool &&
                    spoken.isNotEmpty() &&
                    TurnProgress.promisesAction(spoken)
                ) {
                    // The model promised a lookup and ended the response without
                    // calling anything. Nothing else will arrive, so retry once
                    // instead of leaving the user in silence.
                    retryDeadTurn(spoken)
                } else {
                    _state.update {
                        it.copy(
                            status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE,
                            speaking = false,
                            speechActive = false,
                            ttftMs = metrics.ttftMs,
                            turnCount = it.turnCount + 1,
                        )
                    }
                }
                if (usage != null) {
                    _state.update {
                        val previous = it.realtimeUsage ?: TokenUsage()
                        it.copy(
                            realtimeUsage = TokenUsage(
                                inputTokens = previous.inputTokens + usage.inputTokens,
                                outputTokens = previous.outputTokens + usage.outputTokens,
                                totalTokens = previous.totalTokens + usage.totalTokens,
                            ),
                        )
                    }
                    listener.onUsage(usage, metrics.costUsd)
                }
                AppLog.info(
                    LogCategory.SESSION,
                    "Response done",
                    "status=${event.status} items=[${event.outputTypes.joinToString(",")}] " +
                        "ttft=${metrics.ttftMs}ms total=${metrics.totalMs}ms " +
                        "tokens=${usage?.totalTokens ?: 0}",
                )
            }

            is RealtimeEvent.ResponseCancelled -> {
                AppLog.debug(LogCategory.SESSION, "Response cancelled", event.reason)
                if (drivingJobs.isNotEmpty()) cancelDrivingTools(clearConfirmation = true)
                finishTurn(partial = true)
                _state.update {
                    it.copy(status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE, speechActive = false)
                }
            }

            is RealtimeEvent.Error -> {
                AppLog.error(LogCategory.SESSION, "Server error", event.message)
                _state.update { it.copy(status = LiveStatus.ERROR, error = event.message) }
                listener.onError(event.message)
            }

            is RealtimeEvent.Failure -> {
                cancelDrivingTools(clearConfirmation = true)
                listener.onUserTranscriptPartial("")
                finishTurn(partial = true)
                // No response.done will arrive for this turn, so stop the warm
                // reasoning call here instead of leaving it streaming.
                cancelSpeculation("connection failed")
                val canRecover = event.recoverable &&
                    _state.value.active &&
                    config != null &&
                    !closingTransport
                if (canRecover) {
                    AppLog.warn(LogCategory.SESSION, "Connection problem", event.message)
                    scheduleRecovery(event.message)
                } else {
                    _state.update {
                        it.copy(
                            status = LiveStatus.ERROR,
                            connected = false,
                            error = event.message,
                            speechActive = false,
                            reconnecting = false,
                        )
                    }
                    listener.onError(event.message)
                }
            }

            is RealtimeEvent.Closed -> {
                cancelDrivingTools(clearConfirmation = true)
                listener.onUserTranscriptPartial("")
                if (_state.value.active) {
                    finishTurn(partial = true)
                    cancelSpeculation("connection closed")
                    if (config != null && !closingTransport) {
                        // The server dropped the socket (idle timeout, network
                        // change, session limit): restore it instead of ending
                        // the conversation.
                        AppLog.warn(
                            LogCategory.SESSION,
                            "Socket closed unexpectedly",
                            "code=${event.code} reason=${event.reason}",
                        )
                        scheduleRecovery("socket closed (${event.code})")
                    } else {
                        _state.update {
                            it.copy(
                                status = LiveStatus.IDLE,
                                connected = false,
                                active = false,
                                liveEnabled = false,
                                speechActive = false,
                                reconnecting = false,
                            )
                        }
                        AppLog.info(LogCategory.SESSION, "Socket closed", "code=${event.code}")
                    }
                }
            }

            is RealtimeEvent.Unknown -> {
                AppLog.verbose(LogCategory.PROTOCOL, "Unhandled event", event.type)
            }
        }
    }

    /**
     * A completed response that only promised to act. Retry it once with a
     * per-response instruction; a second promise is reported to the user
     * instead of retried, so a broken model can never leave a silent turn.
     */
    private fun retryDeadTurn(spoken: String) {
        if (nudgedThisTurn) {
            AppLog.error(LogCategory.SESSION, "Turn ended on a promise twice", spoken.take(200))
            listener.onError("The assistant ended that turn without answering. Please ask again.")
            _state.update {
                it.copy(
                    status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE,
                    speaking = false,
                    speechActive = false,
                )
            }
            return
        }
        nudgedThisTurn = true
        val sent = transport?.send(
            SessionPayloads.responseNudge(modalities(), DEAD_TURN_NUDGE),
        ) == true
        if (!sent) {
            AppLog.error(LogCategory.SESSION, "Promise turn could not be retried", spoken.take(200))
            listener.onError("The assistant did not answer. Please ask again.")
            _state.update {
                it.copy(status = if (liveEnabled) LiveStatus.LISTENING else LiveStatus.IDLE)
            }
            return
        }
        armRetryTurn()
        _state.update {
            it.copy(status = LiveStatus.THINKING, speaking = false, speechActive = false)
        }
        AppLog.warn(LogCategory.SESSION, "Turn ended on a promise; retrying", spoken.take(200))
    }

    private suspend fun handleFunctionCall(event: RealtimeEvent.FunctionCall) {
        if (event.callId.isBlank() || event.name.isBlank()) return
        if (!handledCalls.add(event.callId)) return
        if (!_state.value.connected) return
        turnUsedTool = true
        val driving = event.name in ToolDefinitions.drivingNames || event.name == "get_weather"
        AppLog.info(LogCategory.TOOLS, "Model requested ${event.name}", if (driving) "" else event.arguments)
        if (config?.toolsJson?.contains("\"name\":\"${event.name}\"") != true) return
        if (event.name == "switch_conversation_mode") {
            val mode = when (handoffArgument(event.arguments, "mode")) {
                "assistant" -> ConversationMode.ASSISTANT
                "spanish_tutor" -> ConversationMode.SPANISH_TUTOR
                else -> null
            }
            val alreadyActive = (mode == ConversationMode.SPANISH_TUTOR) == (config?.spanishTutor == true)
            if (mode != null && !alreadyActive) {
                modeSwitchPending = true
                transport?.setSpeakerMuted(true)
                listener.onModeSwitchRequested(mode)
                return
            }
            val output = if (mode == null) "{\"ok\":false,\"error\":\"Use assistant or spanish_tutor.\"}"
                else "{\"ok\":true,\"already_active\":true}"
            transport?.send(SessionPayloads.functionOutputItem(event.callId, output))
            toolOutputsSent = true
            if (!responding) {
                transport?.send(SessionPayloads.responseCreate(modalities()))
                toolOutputsSent = false
            }
            return
        }
        if (driving) {
            runDrivingTool(event)
            return
        }
        if (event.name == ToolDefinitions.HANDOFF_NAME) {
            // A handoff runs for tens of seconds; it must never hold the mutex
            // that barge-in, interrupt() and event handling need.
            startHandoff(event)
            return
        }
        if (event.name == ToolDefinitions.NEWS_NAME) {
            // Same reason: the digest model plus the app search can take a while.
            startNews(event)
            return
        }
        val activeTransport = transport ?: return
        listener.onToolCall(event.name, event.arguments, null)
        _state.update { it.copy(status = LiveStatus.THINKING) }
        val result = tools.execute(
            event.name,
            event.arguments,
            config?.searchProvider ?: SearchProvider.EXA,
            spanishTutor = config?.spanishTutor == true,
        )
        listener.onToolCall(event.name, event.arguments, result)
        val responseAlreadyFinished = !responding
        activeTransport.send(SessionPayloads.functionOutputItem(event.callId, result))
        if (responseAlreadyFinished) {
            activeTransport.send(SessionPayloads.responseCreate(modalities()))
            toolOutputsSent = false
        } else {
            toolOutputsSent = true
        }
    }

    private fun runDrivingTool(event: RealtimeEvent.FunctionCall) {
        val activeTransport = transport ?: return
        val provider = config?.searchProvider ?: SearchProvider.EXA
        listener.onToolCall(event.name, "", null)
        _state.update { it.copy(status = LiveStatus.THINKING) }
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val output = when (event.name) {
                "call_phone", "prepare_whatsapp_message", "confirm_phone_action" -> mutex.withLock {
                    if (transport !== activeTransport) return@launch
                    phoneTool(event)
                }
                else -> tools.execute(event.name, event.arguments.ifBlank { "{}" }, provider)
            }
            mutex.withLock {
                if (drivingJobs.remove(event.callId) == null || transport !== activeTransport) return@withLock
                listener.onToolCall(event.name, "", output)
                activeTransport.send(SessionPayloads.functionOutputItem(event.callId, output))
                if (event.name == "confirm_phone_action" && "\"status\":\"call_requested\"" in output && liveEnabled) {
                    // Give the call the microphone and communication route.
                    applyLiveMode(false)
                    return@withLock
                }
                toolOutputsSent = true
                if (!responding && drivingJobs.isEmpty()) {
                    activeTransport.send(SessionPayloads.responseCreate(modalities()))
                    toolOutputsSent = false
                    _state.update { it.copy(status = LiveStatus.THINKING) }
                }
            }
        }
        drivingJobs[event.callId] = job
        job.start()
    }

    private suspend fun phoneTool(event: RealtimeEvent.FunctionCall): String {
        if (event.name == "confirm_phone_action") {
            val action = pendingPhoneAction
            if (!confirmedPhoneAction || action == null) {
                return toolError("No pending action has been explicitly confirmed by the user on a later turn.")
            }
            pendingPhoneAction = null
            confirmedPhoneAction = false
            return tools.completePhone(action)
        }
        val args = runCatching { Json.parseToJsonElement(event.arguments) as JsonObject }.getOrNull()
            ?: return toolError("No recipient was provided.")
        val message = if (event.name == "prepare_whatsapp_message") args.string("message") else null
        val result = try {
            tools.resolvePhone(args.string("target").take(120), message)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return toolError("Android couldn't look up that contact.")
        }
        val action = result.pending ?: return toolError(result.error)
        pendingPhoneAction = action
        confirmedPhoneAction = false
        return action.prompt()
    }

    private fun observePhoneConfirmation(input: String) {
        val action = pendingPhoneAction ?: return
        confirmedPhoneAction = isPhoneConfirmation(input, whatsapp = action.message != null)
        if (!confirmedPhoneAction) pendingPhoneAction = null
    }

    private fun cancelDrivingTools(clearConfirmation: Boolean = false) {
        drivingJobs.values.forEach { it.cancel() }
        drivingJobs.clear()
        if (clearConfirmation) {
            pendingPhoneAction = null
            confirmedPhoneAction = false
        }
    }

    /**
     * Runs the reasoning model for a `deep_reasoning` tool call and answers the
     * call when it returns. Launched off the session mutex on purpose.
     */
    private fun startHandoff(event: RealtimeEvent.FunctionCall) {
        val handoffConfig = config?.handoff
        val reasonerClient = reasoner
        if (handoffConfig == null || reasonerClient == null) {
            replyToHandoff(
                event.callId,
                HandoffPrompt.toolError(
                    handoffConfig?.resolvedModel ?: "qwen",
                    "Deep reasoning is not available in this session.",
                    0,
                ),
            )
            return
        }
        if (handoffJob?.isActive == true) {
            replyToHandoff(
                event.callId,
                HandoffPrompt.toolError(
                    handoffConfig.resolvedModel,
                    "Another deep reasoning request is still running.",
                    0,
                ),
            )
            return
        }
        val task = handoffArgument(event.arguments, "task")
            ?.takeIf { it.isNotBlank() }
            ?: transcript.lastOrNull { it.role == "user" }?.text
            ?: "Answer the user's last request."
        val depth = handoffArgument(event.arguments, "depth") ?: "detailed"
        val context = transcript.toList()
        val apiKey = config?.apiKey.orEmpty()
        handoffStartedAt = SystemClock.elapsedRealtime()
        handoffCancelled = false
        handoffOutcome = null
        _state.update { it.copy(status = LiveStatus.THINKING, handoff = HandoffUiState(handoffConfig.resolvedModel)) }
        AppLog.info(
            LogCategory.HANDOFF,
            "Handoff started",
            "model=${handoffConfig.resolvedModel} depth=$depth task=${task.take(200)}",
        )
        handoffJob = scope.launch {
            val result = awaitReasoning(
                client = reasonerClient,
                handoff = handoffConfig,
                apiKey = apiKey,
                context = context,
                task = task,
                depth = depth,
            )
            if (!isActive) return@launch
            handoffOutcome = result
            mutex.withLock { finishHandoffLocked(event.callId, handoffConfig, task, result) }
            handoffJob = null
        }
    }

    /**
     * Waits for the warm call when one answers this task, otherwise starts a new
     * one. The warm call is billed where it was started, so only a fresh call is
     * billed here.
     */
    private suspend fun awaitReasoning(
        client: ReasoningClient,
        handoff: HandoffConfig,
        apiKey: String,
        context: List<HandoffPrompt.Turn>,
        task: String,
        depth: String,
    ): Result<ReasoningResult> {
        when (val warm = speculation.take(task)) {
            is SpeculativeHandoff.WarmTake.Reuse -> {
                AppLog.info(
                    LogCategory.HANDOFF,
                    "Using warm reasoning call",
                    "task=${task.take(120)}",
                )
                return try {
                    warm.deferred.await()
                } catch (error: CancellationException) {
                    coroutineContext.ensureActive()
                    Result.failure(HandoffException("Deep reasoning was cancelled.", cancelled = true))
                }
            }
            SpeculativeHandoff.WarmTake.Mismatch -> cancelSpeculation("task mismatch")
            SpeculativeHandoff.WarmTake.None -> Unit
        }
        val result = client.answer(
            apiKey = apiKey,
            config = handoff,
            transcript = context,
            task = task,
            depth = depth,
        ) { chars -> reportReasoningProgress(chars) }
        billReasoning(handoff, result)
        return result
    }

    private suspend fun finishHandoffLocked(
        callId: String,
        handoffConfig: HandoffConfig,
        task: String,
        result: Result<ReasoningResult>,
    ) {
        if (handoffCancelled) {
            _state.update { it.copy(handoff = null) }
            return
        }
        handoffOutcome = null
        _state.update { it.copy(handoff = null) }
        val activeTransport = transport ?: return
        val trace = buildTrace(handoffConfig, task, result)
        // A spoken turn only needs the summary: sending the full answer invites
        // the realtime model to read it aloud. The full text stays in the chat.
        val includeAnswer = !currentTurnVoice
        activeTransport.send(
            SessionPayloads.functionOutputItem(callId, HandoffPrompt.toolOutput(trace, includeAnswer)),
        )
        if (!responding) {
            activeTransport.send(SessionPayloads.responseCreate(modalities()))
            toolOutputsSent = false
            _state.update { it.copy(status = LiveStatus.THINKING) }
        } else {
            toolOutputsSent = true
        }
        reportHandoff(trace)
    }

    /** Answers a handoff call that cannot run, off the event-processing thread. */
    private fun replyToHandoff(callId: String, output: String) {
        scope.launch {
            mutex.withLock {
                val activeTransport = transport ?: return@withLock
                activeTransport.send(SessionPayloads.functionOutputItem(callId, output))
                if (!responding) {
                    activeTransport.send(SessionPayloads.responseCreate(modalities()))
                } else {
                    toolOutputsSent = true
                }
            }
        }
    }

    private fun cancelHandoff(reason: String) {
        val job = handoffJob ?: return
        handoffCancelled = true
        handoffJob = null
        handoffOutcome = null
        // Cancelling the job aborts its HTTP call too.
        job.cancel()
        _state.update { it.copy(handoff = null) }
        AppLog.info(LogCategory.HANDOFF, "Handoff cancelled", reason)
    }

    /**
     * Runs `get_news`: the app collects dated items (when the mode is APP),
     * then the large text model writes one digest. Launched off the session
     * mutex, like the handoff, so barge-in stays responsive.
     */
    private fun startNews(event: RealtimeEvent.FunctionCall) {
        val news = config?.news
        val reasonerClient = reasoner
        val apiKey = config?.apiKey.orEmpty()
        val registry = tools
        val provider = config?.searchProvider ?: SearchProvider.EXA
        if (newsJob?.isActive == true) {
            replyToNews(
                event.callId,
                NewsPrompt.toolOutput("", "", LocalDate.now(), "A news request is already running."),
            )
            return
        }
        newsCancelled = false
        AppLog.info(
            LogCategory.TOOLS,
            "News digest started",
            "model=${news?.model?.resolvedModel ?: "none"}",
        )
        newsJob = scope.launch {
            val today = LocalDate.now()
            val reference = if (registry != null) {
                runCatching { registry.newsReference(provider) }.getOrNull()
            } else {
                null
            }
            val result = if (news != null && reasonerClient != null) {
                reasonerClient
                    .complete(
                        apiKey = apiKey,
                        config = news.model,
                        system = NewsPrompt.system(today),
                        user = NewsPrompt.user(today, reference),
                    )
                    .also { attempt ->
                        if (attempt.isSuccess) billReasoning(news.model, attempt)
                    }
            } else {
                Result.failure(HandoffException("The news model is not configured."))
            }
            if (!isActive) return@launch
            mutex.withLock {
                finishNewsLocked(event.callId, news, result, reference, today)
            }
            newsJob = null
        }
    }

    private fun finishNewsLocked(
        callId: String,
        news: NewsConfig?,
        result: Result<ReasoningResult>,
        reference: String?,
        today: LocalDate,
    ) {
        if (newsCancelled) return
        val activeTransport = transport ?: return
        val output = result.fold(
            onSuccess = { digest ->
                NewsPrompt.toolOutput(digest.answer, digest.model, today)
            },
            onFailure = { error ->
                AppLog.warn(LogCategory.TOOLS, "News digest failed", error.message)
                // The app search may still have dated items: hand those to the
                // realtime model the way the plain news tool used to.
                if (!reference.isNullOrBlank() && !reference.contains("\"error\"")) {
                    buildJsonObject {
                        put("ok", true)
                        put("as_of", today.toString())
                        put("fallback", "digest model unavailable; summarize these dated items")
                        put("items", Json.parseToJsonElement(reference))
                    }.toString()
                } else {
                    NewsPrompt.toolOutput(
                        "",
                        news?.model?.resolvedModel ?: "news",
                        today,
                        error.message ?: "News digest failed.",
                    )
                }
            },
        )
        activeTransport.send(SessionPayloads.functionOutputItem(callId, output))
        if (!responding) {
            activeTransport.send(SessionPayloads.responseCreate(modalities()))
            toolOutputsSent = false
            _state.update { it.copy(status = LiveStatus.THINKING) }
        } else {
            toolOutputsSent = true
        }
    }

    /** Answers a news call that cannot run, off the event-processing thread. */
    private fun replyToNews(callId: String, output: String) {
        scope.launch {
            mutex.withLock {
                val activeTransport = transport ?: return@withLock
                activeTransport.send(SessionPayloads.functionOutputItem(callId, output))
                if (!responding) {
                    activeTransport.send(SessionPayloads.responseCreate(modalities()))
                } else {
                    toolOutputsSent = true
                }
            }
        }
    }

    private fun cancelNews(reason: String) {
        val job = newsJob ?: return
        newsCancelled = true
        newsJob = null
        // Cancelling the job aborts its HTTP call too.
        job.cancel()
        AppLog.info(LogCategory.TOOLS, "News digest cancelled", reason)
    }

    /**
     * Warms the news sections while the realtime model decides to call the
     * tool. A warm cache turns the app search inside `get_news` into a lookup.
     * Repeat asks inside the cache window cost nothing.
     */
    private fun prefetchNews(transcript: String) {
        val current = config ?: return
        val registry = tools ?: return
        if (current.news == null) return
        if (!SearchRecency.isNewsQuery(transcript)) return
        scope.launch(Dispatchers.IO) {
            registry.warmNews(current.searchProvider)
        }
    }

    /**
     * Warms a reasoning call for a hard final voice transcript. The realtime
     * model keeps deciding whether to escalate; this only removes the wait when
     * it does.
     */
    private fun startSpeculation(text: String) {
        val handoff = config?.handoff ?: return
        val reasonerClient = reasoner ?: return
        if (!currentTurnVoice) return
        if (handoffJob?.isActive == true || speculativeJob?.isActive == true) return
        if (!HandoffRouter.shouldSpeculate(text, handoff)) return
        val deferred = speculation.start(text) ?: return
        val apiKey = config?.apiKey.orEmpty()
        val context = transcript.toList()
        AppLog.info(
            LogCategory.HANDOFF,
            "Warming a reasoning call",
            "model=${handoff.resolvedModel} task=${text.take(120)}",
        )
        speculativeJob = scope.launch {
            val result = reasonerClient.answer(
                apiKey = apiKey,
                config = handoff,
                transcript = context,
                task = text,
            ) { chars -> reportReasoningProgress(chars) }
            // The tokens are spent whether or not the model asks for the result.
            billReasoning(handoff, result)
            deferred.complete(result)
        }
    }

    private fun cancelSpeculation(reason: String) {
        speculativeJob?.cancel()
        speculativeJob = null
        if (speculation.running) {
            speculation.cancel()
            AppLog.debug(LogCategory.HANDOFF, "Warm reasoning call dropped", reason)
        }
    }

    /** Bills a reasoning call exactly once, where it returns. */
    private fun billReasoning(handoff: HandoffConfig, result: Result<ReasoningResult>) {
        val answer = result.getOrNull() ?: return
        if (answer.usage.totalTokens <= 0) return
        listener.onUsage(
            answer.usage,
            CostCalculator.cost(
                answer.usage,
                handoff.priceInputPerMillion,
                handoff.priceOutputPerMillion,
            ),
        )
    }

    private fun reportReasoningProgress(chars: Int) {
        _state.update { current ->
            val handoff = current.handoff ?: return@update current
            current.copy(
                handoff = handoff.copy(
                    phase = HandoffPhase.RUNNING,
                    reasoningChars = chars,
                    elapsedMs = SystemClock.elapsedRealtime() - handoffStartedAt,
                ),
            )
        }
    }

    private fun buildTrace(
        handoffConfig: HandoffConfig,
        task: String,
        result: Result<ReasoningResult>,
    ): HandoffTrace {
        val elapsed = SystemClock.elapsedRealtime() - handoffStartedAt
        return result.fold(
            onSuccess = { answer ->
                HandoffTrace(
                    model = answer.model,
                    task = task,
                    answer = answer.answer,
                    summary = answer.summary,
                    reasoning = answer.reasoning,
                    usage = answer.usage,
                    reasoningTokens = answer.reasoningTokens,
                    elapsedMs = answer.elapsedMs,
                    costUsd = CostCalculator.cost(
                        answer.usage,
                        handoffConfig.priceInputPerMillion,
                        handoffConfig.priceOutputPerMillion,
                    ),
                )
            },
            onFailure = { error ->
                HandoffTrace(
                    model = handoffConfig.resolvedModel,
                    task = task,
                    answer = "",
                    summary = "",
                    reasoning = "",
                    usage = TokenUsage(),
                    reasoningTokens = 0,
                    elapsedMs = elapsed,
                    costUsd = 0.0,
                    error = error.message ?: "Deep reasoning failed.",
                )
            },
        )
    }

    private fun reportHandoff(trace: HandoffTrace) {
        listener.onHandoff(trace)
        AppLog.info(
            LogCategory.HANDOFF,
            if (trace.failed) "Handoff failed" else "Handoff delivered",
            "model=${trace.model} ${trace.elapsedMs}ms tokens=${trace.usage.totalTokens} " +
                "reasoning=${trace.reasoningTokens}" +
                (trace.error?.let { " error=$it" } ?: ""),
        )
    }

    private fun rememberTurn(role: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        transcript.addLast(HandoffPrompt.Turn(role, clean))
        while (transcript.size > MAX_TRANSCRIPT_TURNS) transcript.removeFirst()
    }

    private fun modalities(): List<String> =
        if (currentTurnVoice) listOf("text", "audio") else listOf("text")

    private fun handoffArgument(arguments: String, key: String): String? = runCatching {
        JSON.parseToJsonElement(arguments).jsonObject[key]?.jsonPrimitive?.contentOrNull
    }.getOrNull()

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

    /** Tool names offered in `session.update`, for diagnostics. */
    private fun toolNames(toolsJson: String?): String {
        if (toolsJson.isNullOrBlank()) return "none"
        return TOOL_NAME.findAll(toolsJson).map { it.groupValues[1] }.toList()
            .joinToString(",")
            .ifBlank { "none" }
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
            rememberTurn("assistant", text)
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
                handoff = null,
            )
        }
    }
}
