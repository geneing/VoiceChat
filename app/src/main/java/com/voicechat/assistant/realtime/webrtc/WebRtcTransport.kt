package com.voicechat.assistant.realtime.webrtc

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
import com.voicechat.assistant.realtime.TransportState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RTCStatsCollectorCallback
import org.webrtc.RTCStatsReport
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import java.util.ArrayDeque
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * WebRTC transport: signalling is HTTP (SDP offer/answer) and control events
 * flow over the `oai-events` / `txt` data channels. Media is handled by WebRTC's
 * audio device module (AEC, NS, low-latency playback).
 */
class WebRtcTransport(
    private val context: Context,
    private val httpClient: OkHttpClient,
) : RealtimeTransport {

    override val kind: TransportKind = TransportKind.WEBRTC

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

    private var statsJob: Job? = null

    private var factory: PeerConnectionFactory? = null
    private var audioModule: JavaAudioDeviceModule? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localTrack: AudioTrack? = null
    private var clientChannel: DataChannel? = null
    private val remoteTracks = mutableListOf<org.webrtc.AudioTrack>()
    private val pendingSends = ArrayDeque<String>()
    private val sendLock = Any()
    private val cleanupScope = CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + Dispatchers.IO,
    )

    private var iceGathering = CompletableDeferred<Unit>()
    private var connected = CompletableDeferred<Unit>()
    private var connecting = false

    override suspend fun connect(config: SessionConfig): Result<Unit> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("API key is not configured."))
        }
        connecting = true
        _state.value = TransportState.CONNECTING
        iceGathering = CompletableDeferred()
        connected = CompletableDeferred()
        val result = runCatching { establish(config) }
        connecting = false
        if (result.isSuccess) {
            _state.value = TransportState.CONNECTED
            startStatsPolling()
            AppLog.info(LogCategory.TRANSPORT, "WebRTC connected")
        } else {
            _state.value = TransportState.ERROR
            val message = result.exceptionOrNull()?.message ?: "WebRTC connection failed"
            AppLog.error(LogCategory.TRANSPORT, "WebRTC connection failed", message)
            emit(RealtimeEvent.Failure(message))
            teardown()
        }
        result.map { }
    }

    private suspend fun establish(config: SessionConfig) {
        WebRtcEngine.initialize(context)
        val (pcFactory, module) = WebRtcEngine.createFactory(context)
        factory = pcFactory
        audioModule = module
        module.setAudioRecordEnabled(false)
        module.setMicrophoneMute(true)

        val iceServers = listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302")
                .createIceServer(),
        )
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
        }
        val pc = pcFactory.createPeerConnection(rtcConfig, observer)
            ?: throw IOException("Could not create a peer connection")
        peerConnection = pc

        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googHighpassFilter", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googTypingNoiseDetection", "true"))
        }
        val source = pcFactory.createAudioSource(constraints)
        audioSource = source
        val track = pcFactory.createAudioTrack("audio0", source).apply { setEnabled(false) }
        localTrack = track
        if (pc.addTrack(track, listOf("voicechat")) == null) {
            throw IOException("Could not add the audio track")
        }

        val channel = pc.createDataChannel("oai-events", DataChannel.Init())
        observeChannel(channel, "oai-events")

        val offer = createOffer(pc)
        setLocalDescription(pc, offer)
        withTimeoutOrNull(3_000) { iceGathering.await() }
        val sdp = pc.localDescription?.description
            ?: throw IOException("Local SDP was not produced")

        val answer = postOffer(config, sdp)
        setRemoteDescription(pc, answer)
        val opened = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { connected.await() }
        if (opened == null) throw IOException("Timed out waiting for the WebRTC peer connection.")
    }

    private fun postOffer(config: SessionConfig, sdp: String): String {
        val url = ApiEndpoints.webRtcSignalingUrl(config.host, config.workspaceId, config.model)
        AppLog.info(
            LogCategory.TRANSPORT,
            "Posting SDP offer",
            "url=$url bytes=${sdp.length}",
        )
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val hint = if (response.code >= 500 && config.model.contains("audio")) {
                    " The server rejected WebRTC for this model; use WebSocket in Settings."
                } else {
                    ""
                }
                throw IOException(
                    "WebRTC signalling failed (HTTP ${response.code}): ${body.take(500)}$hint",
                )
            }
            if (body.isBlank()) throw IOException("Empty SDP answer from the server")
            AppLog.info(
                LogCategory.TRANSPORT,
                "Received SDP answer",
                "bytes=${body.length} preview=${body.take(120).replace("\r", "").replace("\n", " | ")}",
            )
            return normalizeSdp(body.trim())
        }
    }

    override fun send(eventJson: String): Boolean {
        val channel = clientChannel
        if (channel == null || channel.state() != DataChannel.State.OPEN) {
            synchronized(sendLock) {
                if (pendingSends.size > 64) pendingSends.pollFirst()
                pendingSends.addLast(eventJson)
            }
            return true
        }
        logOutgoing(eventJson)
        return channel.send(DataChannel.Buffer(ByteBuffer.wrap(eventJson.toByteArray()), false))
    }

    override fun sendAudio(pcm: ByteArray) {
        // Media travels over the audio track; the protocol does not accept
        // input_audio_buffer.append on WebRTC.
    }

    override fun setMicEnabled(enabled: Boolean) {
        localTrack?.setEnabled(enabled)
        audioModule?.setMicrophoneMute(!enabled)
        audioModule?.setAudioRecordEnabled(enabled)
        if (!enabled) _micLevel.value = 0f
        AppLog.debug(LogCategory.AUDIO, "WebRTC microphone ${if (enabled) "on" else "off"}")
    }

    override fun setSpeakerMuted(muted: Boolean) {
        audioModule?.setSpeakerMute(muted)
        remoteTracks.forEach { it.setEnabled(!muted) }
        AppLog.debug(LogCategory.AUDIO, if (muted) "Remote audio muted (barge-in)" else "Remote audio unmuted")    }

    override fun close() {
        connecting = false
        _state.value = TransportState.DISCONNECTED
        statsJob?.cancel()
        statsJob = null
        runCatching { clientChannel?.close() }
        clientChannel = null
        runCatching { peerConnection?.close() }
        cleanupScope.launch {
            teardown()
            cleanupScope.cancel()
        }
    }

    private fun startStatsPolling() {
        statsJob?.cancel()
        statsJob = cleanupScope.launch {
            while (isActive) {
                peerConnection?.let(::requestStats)
                delay(1_000)
            }
        }
    }

    private fun requestStats(pc: PeerConnection) {
        runCatching {
            pc.getStats(object : RTCStatsCollectorCallback {
                override fun onStatsDelivered(report: RTCStatsReport?) {
                    val statsMap = report?.statsMap ?: return
                    var sent: Long? = null
                    var received: Long? = null
                    statsMap.values.forEach { stat ->
                        val members = stat.members
                        val kind = members["kind"] ?: members["mediaType"]
                        if (kind != "audio") return@forEach
                        when (stat.type) {
                            "outbound-rtp" ->
                                sent = (members["bytesSent"] as? Number)?.toLong()

                            "inbound-rtp" ->
                                received = (members["bytesReceived"] as? Number)?.toLong()
                        }
                    }
                    traffic.updateTotals(sent, received)
                }
            })
        }
    }

    private fun teardown() {
        runCatching { peerConnection?.dispose() }
        peerConnection = null
        runCatching { localTrack?.dispose() }
        localTrack = null
        runCatching { audioSource?.dispose() }
        audioSource = null
        remoteTracks.forEach { runCatching { it.dispose() } }
        remoteTracks.clear()
        runCatching { factory?.dispose() }
        factory = null
        runCatching { audioModule?.release() }
        audioModule = null
        _micLevel.value = 0f
    }

    private fun flushPending() {
        val channel = clientChannel ?: return
        while (channel.state() == DataChannel.State.OPEN) {
            val next = synchronized(sendLock) { pendingSends.pollFirst() } ?: return
            logOutgoing(next)
            val sent = runCatching {
                channel.send(DataChannel.Buffer(ByteBuffer.wrap(next.toByteArray()), false))
            }.getOrDefault(false)
            if (!sent) return
        }
    }

    private fun observeChannel(channel: DataChannel, label: String) {
        AppLog.info(LogCategory.TRANSPORT, "Data channel: $label")
        channel.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(previousAmount: Long) = Unit

            override fun onStateChange() {
                val state = channel.state()
                AppLog.debug(LogCategory.TRANSPORT, "Data channel $label -> $state")
                if (label == "oai-events" && state == DataChannel.State.OPEN) {
                    clientChannel = channel
                    flushPending()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                val bytes = ByteArray(buffer.data.remaining())
                buffer.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                handleIncoming(text)
            }
        })
    }

    private fun handleIncoming(text: String) {
        logIncoming(text)
        val event = RealtimeEventParser.parse(text) ?: return
        if (!connected.isCompleted) connected.complete(Unit)
        emit(event)
    }

    private val observer = object : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState) {
            AppLog.debug(LogCategory.TRANSPORT, "Signaling: $state")
        }

        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
            AppLog.info(LogCategory.TRANSPORT, "ICE: $state")
            if (state == PeerConnection.IceConnectionState.FAILED) {
                if (!connected.isCompleted) {
                    connected.completeExceptionally(IOException("ICE connection failed"))
                }
                emit(RealtimeEvent.Failure("ICE connection failed"))
            }
        }

        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit

        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
            AppLog.debug(LogCategory.TRANSPORT, "ICE gathering: $state")
            if (state == PeerConnection.IceGatheringState.COMPLETE && !iceGathering.isCompleted) {
                iceGathering.complete(Unit)
            }
        }

        override fun onIceCandidate(candidate: IceCandidate) = Unit

        override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) = Unit

        override fun onAddStream(stream: MediaStream) = Unit

        override fun onRemoveStream(stream: MediaStream) = Unit

        override fun onDataChannel(channel: DataChannel) {
            observeChannel(channel, channel.label())
        }

        override fun onRenegotiationNeeded() = Unit

        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit

        override fun onTrack(transceiver: RtpTransceiver?) {
            val track = transceiver?.receiver?.track()
            if (track is org.webrtc.AudioTrack) {
                remoteTracks += track
                track.setEnabled(true)
                AppLog.info(LogCategory.TRANSPORT, "Remote audio track attached")
            }
        }

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            AppLog.info(LogCategory.TRANSPORT, "Peer connection: $newState")
            when (newState) {
                PeerConnection.PeerConnectionState.CONNECTED -> {
                    if (!connected.isCompleted) connected.complete(Unit)
                }

                PeerConnection.PeerConnectionState.FAILED -> {
                    if (!connected.isCompleted) {
                        connected.completeExceptionally(IOException("Peer connection failed"))
                    }
                }

                else -> Unit
            }
        }
    }

    private suspend fun createOffer(pc: PeerConnection): SessionDescription =
        suspendCancellableCoroutine { continuation ->
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(sdp: SessionDescription?) {
                    if (sdp == null) {
                        continuation.resumeWithException(IOException("Empty offer"))
                    } else {
                        continuation.resume(sdp)
                    }
                }

                override fun onCreateFailure(error: String?) {
                    continuation.resumeWithException(IOException("Offer failed: $error"))
                }

                override fun onSetSuccess() = Unit
                override fun onSetFailure(error: String?) = Unit
            }, MediaConstraints())
        }

    private fun setLocalDescription(pc: PeerConnection, sdp: SessionDescription) {
        pc.setLocalDescription(simpleSdpObserver("local"), sdp)
    }

    private suspend fun setRemoteDescription(pc: PeerConnection, sdp: String) =
        suspendCancellableCoroutine { continuation ->
            pc.setRemoteDescription(object : SdpObserver {
                override fun onSetSuccess() {
                    AppLog.debug(LogCategory.TRANSPORT, "Remote SDP set")
                    continuation.resume(Unit)
                }

                override fun onSetFailure(error: String?) {
                    continuation.resumeWithException(
                        IOException("Could not apply the SDP answer: ${error ?: "unknown"}"),
                    )
                }

                override fun onCreateSuccess(sdp: SessionDescription?) = Unit
                override fun onCreateFailure(error: String?) = Unit
            }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
        }

    /** libwebrtc expects CRLF line endings and a trailing newline. */
    private fun normalizeSdp(sdp: String): String =
        sdp.replace("\r\n", "\n")
            .replace("\r", "\n")
            .replace("\n", "\r\n") + "\r\n"

    private fun simpleSdpObserver(tag: String) = object : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription?) = Unit
        override fun onCreateFailure(error: String?) {
            AppLog.warn(LogCategory.TRANSPORT, "$tag create failure", error)
        }

        override fun onSetSuccess() {
            AppLog.debug(LogCategory.TRANSPORT, "$tag SDP set")
        }

        override fun onSetFailure(error: String?) {
            AppLog.warn(LogCategory.TRANSPORT, "$tag set failure", error)
        }
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
        val type = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(payload)?.groupValues?.get(1) ?: "?"
        if (!AppLog.protocolDump) {
            AppLog.verbose(LogCategory.PROTOCOL, "← $type")
            return
        }
        AppLog.debug(LogCategory.PROTOCOL, "← $type", payload.take(1_500))
    }

    private companion object {
        const val CONNECTION_TIMEOUT_MS = 20_000L
    }
}
