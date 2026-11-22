package com.voicechat.assistant.realtime.ws

import android.content.Context
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.realtime.ApiEndpoints
import com.voicechat.assistant.realtime.AudioTraffic
import com.voicechat.assistant.realtime.AudioTrafficMeter
import com.voicechat.assistant.realtime.RealtimeEvent
import com.voicechat.assistant.realtime.RealtimeEventParser
import com.voicechat.assistant.realtime.RealtimeTransport
import com.voicechat.assistant.realtime.SessionConfig
import com.voicechat.assistant.realtime.SessionPayloads
import com.voicechat.assistant.realtime.TransportState
import com.voicechat.assistant.realtime.audio.AudioFocus
import com.voicechat.assistant.realtime.audio.PcmPlayer
import com.voicechat.assistant.realtime.audio.PcmRecorder
import com.voicechat.assistant.realtime.audio.VadConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.Base64

/**
 * DashScope realtime over WebSocket. Owns mic capture and PCM playback because
 * the protocol streams raw 16 kHz input and 24 kHz output.
 */
class WebSocketTransport(
    private val httpClient: OkHttpClient,
    context: Context,
) : RealtimeTransport {

    override val kind: TransportKind = TransportKind.WEBSOCKET

    private val _events = MutableSharedFlow<RealtimeEvent>(
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: SharedFlow<RealtimeEvent> = _events.asSharedFlow()

    private val _state = MutableStateFlow(TransportState.DISCONNECTED)
    override val state: StateFlow<TransportState> = _state.asStateFlow()

    private val _micLevel = MutableStateFlow(0f)
    override val micLevel: StateFlow<Float> = _micLevel.asStateFlow()

    private val traffic = AudioTrafficMeter()
    override val audioTraffic: StateFlow<AudioTraffic> = traffic.state

    private val player = PcmPlayer(SAMPLE_RATE_OUT)
    private val playbackLock = Any()
    private val audioGate = ResponseAudioGate()
    private var ignoredAudioLogged = false
    private var responseAudioBytes = 0L
    private var playbackTruncated = false
    private val focus = AudioFocus(context.applicationContext)

    /** Serialises blocking AudioRecord/AudioTrack lifecycle work off the main thread. */
    private val audioExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ws-audio").apply { isDaemon = true }
    }

    private fun onAudioThread(block: () -> Unit) {
        runCatching { audioExecutor.execute(block) }
    }

    private val recorder = PcmRecorder(
        onChunk = { sendAudio(it) },
        onLevel = { _micLevel.value = it },
        onError = { message ->
            // A capture failure is not a link problem: retrying would just
            // restart the same failing microphone.
            emit(RealtimeEvent.Failure(message, recoverable = false))
            _state.value = TransportState.ERROR
        },
    )

    init {
        // A headset that connects mid-session must take over capture.
        focus.onInputRouteChanged = { device ->
            onAudioThread {
                if (micEnabled) recorder.setPreferredInput(device)
            }
        }
        // Playback follows the same communication route as the microphone.
        focus.onOutputRouteChanged = { device ->
            onAudioThread { player.setPreferredDevice(device) }
        }
    }

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var micEnabled = false

    @Volatile
    private var vadConfig = VadConfig()

    override suspend fun connect(config: SessionConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("API key is not configured."))
        }
        vadConfig = VadConfig(
            enabled = config.onDeviceVad,
            sensitivityDb = config.vadSensitivityDb,
            hangoverMs = config.voiceGateHangoverMs(),
        )
        _state.value = TransportState.CONNECTING
        val opened = CompletableDeferred<Result<Unit>>()
        val url = ApiEndpoints.webSocketUrl(config.host, config.workspaceId, config.model)
        AppLog.info(LogCategory.TRANSPORT, "Connecting WebSocket", url)
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .header("X-DashScope-DataInspection", "enable")
            .build()
        try {
            socket = httpClient.newWebSocket(request, listener(opened))
        } catch (error: IllegalArgumentException) {
            _state.value = TransportState.ERROR
            return@withContext Result.failure(error)
        }
        val result = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { opened.await() }
            ?: Result.failure(IOException("Timed out while opening the realtime socket."))
        if (result.isSuccess) {
            _state.value = TransportState.CONNECTED
            player.start()
            focus.acquire()
            AppLog.info(LogCategory.TRANSPORT, "WebSocket connected")
        } else {
            _state.value = TransportState.ERROR
            cleanupAudio()
        }
        result
    }

    override fun send(eventJson: String): Boolean {
        val current = socket ?: return false
        logOutgoing(eventJson)
        return current.send(eventJson)
    }

    override fun sendAudio(pcm: ByteArray) {
        if (_state.value != TransportState.CONNECTED || socket == null) return
        val base64 = Base64.getEncoder().encodeToString(pcm)
        if (socket?.send(SessionPayloads.audioAppend(base64)) == true) {
            traffic.addSent(pcm.size)
        }
    }

    override fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        if (!enabled) synchronized(playbackLock) { audioGate.speechStopped() }
        onAudioThread {
            if (enabled) {
                if (_state.value == TransportState.CONNECTED) {
                    focus.enterVoiceMode()
                    recorder.vadConfig = vadConfig
                    recorder.start(focus.preferredInputDevice())
                    focus.acquire()
                }
            } else {
                recorder.stop()
                focus.exitVoiceMode()
            }
        }
    }

    override fun setSpeakerMuted(muted: Boolean) {
        player.setMuted(muted)
        AppLog.debug(LogCategory.AUDIO, if (muted) "Playback muted" else "Playback unmuted")
    }

    override fun interruptPlayback() {
        synchronized(playbackLock) {
            audioGate.interrupt()
            player.setMuted(true)
        }
    }

    override fun close() {
        val current = socket
        socket = null
        _state.value = TransportState.DISCONNECTED
        interruptPlayback()
        runCatching { current?.close(1000, "client closed") }
        onAudioThread {
            cleanupAudio()
            audioExecutor.shutdown()
        }
    }

    private fun cleanupAudio() {
        recorder.stop()
        player.stop()
        focus.release()
        _micLevel.value = 0f
    }

    private fun listener(opened: CompletableDeferred<Result<Unit>>) = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            AppLog.info(LogCategory.TRANSPORT, "Socket open (${response.code})")
            opened.complete(Result.success(Unit))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val event = RealtimeEventParser.parse(text) ?: return
            val accepted = handlePlayback(event)
            logIncoming(text)
            if (!accepted) return
            emit(event)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val message = buildString {
                append(t.message ?: t::class.java.simpleName)
                if (response != null) append(" (HTTP ${response.code})")
            }
            AppLog.error(LogCategory.TRANSPORT, "Socket failure", message)
            _state.value = TransportState.ERROR
            if (!opened.isCompleted) opened.complete(Result.failure(IOException(message)))
            emit(RealtimeEvent.Failure(message))
            cleanupAudio()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.info(LogCategory.TRANSPORT, "Socket closed", "code=$code reason=$reason")
            _state.value = TransportState.DISCONNECTED
            emit(RealtimeEvent.Closed(code, reason))
            if (!opened.isCompleted) {
                opened.complete(
                    Result.failure(IOException("Closed before open (code=$code): $reason")),
                )
            }
            cleanupAudio()
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            AppLog.debug(LogCategory.TRANSPORT, "Socket closing", "code=$code reason=$reason")
            webSocket.close(1000, null)
        }
    }

    // Handle these on the ordered socket callback instead of waiting behind session tools.
    private fun handlePlayback(event: RealtimeEvent): Boolean = synchronized(playbackLock) {
        when (event) {
            is RealtimeEvent.SpeechStarted -> {
                if (micEnabled) {
                    audioGate.speechStarted()
                    player.clear()
                }
            }
            is RealtimeEvent.SpeechStopped -> audioGate.speechStopped()
            is RealtimeEvent.ResponseCreated -> {
                ignoredAudioLogged = false
                responseAudioBytes = 0
                playbackTruncated = false
                if (audioGate.begin(event.responseId)) {
                    player.clear()
                    AppLog.warn(LogCategory.AUDIO, "Discarded queued PCM for a superseded response", event.responseId)
                }
            }
            is RealtimeEvent.ResponseDone -> {
                val ended = audioGate.end(event.responseId)
                if (ended && responseAudioBytes > 0) {
                    val audioMs = responseAudioBytes * 1000 / (SAMPLE_RATE_OUT * 2)
                    AppLog.debug(
                        LogCategory.AUDIO,
                        "PCM response summary",
                        "id=${event.responseId} received=${audioMs}ms buffered=${player.queuedDurationMs}ms " +
                            "truncated=$playbackTruncated",
                    )
                }
                if (ended && event.status != null && event.status != "completed") player.clear()
            }
            is RealtimeEvent.ResponseCancelled -> {
                if (audioGate.end(event.responseId)) player.clear()
            }
            is RealtimeEvent.AssistantAudioDelta -> {
                traffic.addReceived(event.audio.size)
                if (!audioGate.accepts(event.responseId)) {
                    if (!ignoredAudioLogged) {
                        ignoredAudioLogged = true
                        AppLog.debug(LogCategory.AUDIO, "Ignored PCM outside the active response", event.responseId)
                    }
                    return@synchronized false
                }
                responseAudioBytes += event.audio.size
                if (!playbackTruncated && player.enqueue(event.audio) == PcmPlayer.EnqueueResult.FULL) {
                    // Keep the queued prefix intact. Resuming later in the same stream
                    // would splice speech across a gap and sound like crossed voices.
                    playbackTruncated = true
                    AppLog.warn(
                        LogCategory.AUDIO,
                        "PCM backlog limit reached; truncating the rest of the response",
                        "id=${event.responseId} buffered=${player.queuedDurationMs}ms",
                    )
                }
            }
            else -> Unit
        }
        true
    }

    private fun emit(event: RealtimeEvent) {
        _events.tryEmit(event)
    }

    private fun logOutgoing(payload: String) {
        if (!AppLog.protocolDump) return
        val type = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
        AppLog.debug(LogCategory.PROTOCOL, "→ ${type ?: "client.event"}", payload.take(1_500))
    }

    private fun logIncoming(payload: String) {
        val type = runCatching {
            Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1)
        }.getOrNull() ?: "?"
        // Audio deltas are frequent; logging each chunk drowns the useful trace.
        if (type == "response.audio.delta") return
        if (!AppLog.protocolDump) {
            AppLog.verbose(LogCategory.PROTOCOL, "← $type")
            return
        }
        AppLog.debug(LogCategory.PROTOCOL, "← $type", payload.take(1_500))
    }

    companion object {
        const val SAMPLE_RATE_OUT = 24_000
        private const val CONNECT_TIMEOUT_MS = 20_000L
    }
}
