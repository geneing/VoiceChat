package com.voicechat.assistant.realtime.ws

import android.content.Context
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.realtime.ApiEndpoints
import com.voicechat.assistant.realtime.RealtimeEvent
import com.voicechat.assistant.realtime.RealtimeEventParser
import com.voicechat.assistant.realtime.RealtimeTransport
import com.voicechat.assistant.realtime.SessionConfig
import com.voicechat.assistant.realtime.SessionPayloads
import com.voicechat.assistant.realtime.TransportState
import com.voicechat.assistant.realtime.audio.AudioFocus
import com.voicechat.assistant.realtime.audio.PcmPlayer
import com.voicechat.assistant.realtime.audio.PcmRecorder
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

    private val player = PcmPlayer(SAMPLE_RATE_OUT)
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
            emit(RealtimeEvent.Failure(message))
            _state.value = TransportState.ERROR
        },
    )

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var micEnabled = false

    override suspend fun connect(config: SessionConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("API key is not configured."))
        }
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
        socket?.send(SessionPayloads.audioAppend(base64))
    }

    override fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        onAudioThread {
            if (enabled) {
                if (_state.value == TransportState.CONNECTED) {
                    recorder.start()
                    focus.acquire()
                }
            } else {
                recorder.stop()
            }
        }
    }

    override fun setSpeakerMuted(muted: Boolean) {
        player.setMuted(muted)
        AppLog.debug(LogCategory.AUDIO, if (muted) "Playback muted" else "Playback unmuted")
    }

    override fun close() {
        val current = socket
        socket = null
        _state.value = TransportState.DISCONNECTED
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
            logIncoming(text)
            val event = RealtimeEventParser.parse(text) ?: return
            when (event) {
                is RealtimeEvent.AssistantAudioDelta -> player.enqueue(event.audio)
                is RealtimeEvent.ResponseCreated -> player.setMuted(false)
                else -> Unit
            }
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

    private fun emit(event: RealtimeEvent) {
        _events.tryEmit(event)
    }    private fun logOutgoing(payload: String) {
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
