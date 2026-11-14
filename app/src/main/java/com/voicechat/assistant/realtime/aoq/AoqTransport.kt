package com.voicechat.assistant.realtime.aoq

import android.content.Context
import com.alibaba.aoq.clientsdk.AoqClientEngine
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioCaptureConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioCodecConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioPlaybackConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioVolume
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioVolumeIndicationConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqConnectConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqConnectionStatus
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqCreateConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqDataMsg
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqEncoderType
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqErrorCode
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioFrameData
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioObserverConfig
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioObserverMode
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioSource
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqRelayEndpoint
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqTrackParam
import com.alibaba.aoq.clientsdk.AoqClientEngine.AoqTrackType
import com.alibaba.aoq.clientsdk.AoqClientListener
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.realtime.AudioTraffic
import com.voicechat.assistant.realtime.AudioTrafficMeter
import com.voicechat.assistant.realtime.RealtimeEvent
import com.voicechat.assistant.realtime.RealtimeEventParser
import com.voicechat.assistant.realtime.RealtimeTransport
import com.voicechat.assistant.realtime.SessionConfig
import com.voicechat.assistant.realtime.TransportState
import com.voicechat.assistant.realtime.audio.PcmPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * AOQ (AI over QUIC) transport backed by Alibaba's native client SDK.
 *
 * The SDK owns capture, Opus encoding/decoding, playback and the QUIC relay.
 * Application events travel as Data messages, so the realtime payloads are the
 * same as the WebSocket transport. Authentication is a short-lived token from
 * [AoqTokenClient]; the API key never goes to the SDK.
 *
 * The native libraries ship for arm64-v8a and armeabi-v7a only. On x86_64 the
 * link fails cleanly and the UI offers WebSocket/WebRTC instead.
 */
class AoqTransport(
    context: Context,
    private val httpClient: OkHttpClient,
) : RealtimeTransport {

    override val kind: TransportKind = TransportKind.AOQ

    private val appContext = context.applicationContext

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

    /**
     * Renders received audio ourselves so the speech rate can be applied to the
     * PCM with [com.voicechat.assistant.realtime.audio.TimeStretcher]. Only used
     * in external playback mode; at normal speed the SDK keeps its own output
     * path (and its own AEC reference).
     */
    private val player = PcmPlayer(PLAYBACK_SAMPLE_RATE)

    /** Serialises blocking player start/stop off the caller's thread. */
    private val audioExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "aoq-audio")
    }

    @Volatile
    private var playbackSpeed = 1f

    @Volatile
    private var externalPlayback = false

    @Volatile
    private var observerEnabled = false

    @Volatile
    private var playbackFrames = 0L

    @Volatile
    private var firstPlaybackFrameAt = 0L

    @Volatile
    private var engine: AoqClientEngine? = null

    @Volatile
    private var connectDeferred: CompletableDeferred<Result<Unit>>? = null

    @Volatile
    private var micEnabled = false

    @Volatile
    private var speakerMuted = false

    @Volatile
    private var captureStarted = false

    @Volatile
    private var playerStarted = false

    @Volatile
    private var voipMode = true

    @Volatile
    private var mediaEnabled = false

    @Volatile
    private var closing = false

    override suspend fun connect(config: SessionConfig): Result<Unit> = withContext(Dispatchers.IO) {
        closeEngine()
        if (config.apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("AOQ requires an API key to allocate a token."),
            )
        }
        _state.value = TransportState.CONNECTING
        closing = false
        mediaEnabled = false
        speakerMuted = false

        val credentials = AoqTokenClient.allocate(httpClient, config).getOrElse { error ->
            _state.value = TransportState.ERROR
            emit(RealtimeEvent.Failure(error.message ?: "AOQ token allocation failed"))
            return@withContext Result.failure(error)
        }

        val workDir = File(appContext.filesDir, "aoq").apply { mkdirs() }
        ensureOpusPlugin(workDir)
        val createConfig = AoqCreateConfig().apply {
            this.workDir = workDir.absolutePath
            enableDumpAudio = false
            isBTScoMode = false
        }

        val created = try {
            AoqClientEngine.createEngine(appContext, createConfig, listener)
        } catch (error: LinkageError) {
            val message = "AOQ native libraries are not available for this CPU architecture."
            AppLog.error(LogCategory.TRANSPORT, message, error.message)
            _state.value = TransportState.ERROR
            emit(RealtimeEvent.Failure(message, recoverable = false))
            return@withContext Result.failure(IOException(message, error))
        } catch (error: RuntimeException) {
            _state.value = TransportState.ERROR
            emit(RealtimeEvent.Failure("Could not create the AOQ engine: ${error.message}"))
            return@withContext Result.failure(error)
        }
        if (created == null) {
            _state.value = TransportState.ERROR
            val error = IOException("AOQ engine could not be created.")
            emit(RealtimeEvent.Failure(error.message.orEmpty()))
            return@withContext Result.failure(error)
        }
        engine = created
        AppLog.info(LogCategory.TRANSPORT, "AOQ engine ${AoqClientEngine.getVersion()}")
        playbackSpeed = config.speechRate.toFloat().coerceIn(MIN_SPEED, MAX_SPEED)
        player.setSpeed(playbackSpeed)

        applyCodecConfigs(created)
        runCatching {
            created.enableLocalAudioVolumeIndication(
                AoqAudioVolumeIndicationConfig().apply {
                    interval = 200
                    smooth = 3
                },
            )
        }
        // Some realtime models reject media before session.updated.
        runCatching { created.enableSendMediaStream(AoqTrackType.AoqTrackTypeAudio, false) }
        runCatching { created.enableSendMediaStream(AoqTrackType.AoqTrackTypeVideo, false) }

        val deferred = CompletableDeferred<Result<Unit>>()
        connectDeferred = deferred
        val connectConfig = AoqConnectConfig().apply {
            token = credentials.token
            sid = credentials.sid
            certFingerprint = credentials.certFingerprint
            workspaceIdHash = credentials.workspaceIdHash
            relayEndpoints = credentials.relayEndpoints.map { credential ->
                AoqRelayEndpoint().apply {
                    routeIndex = credential.routeIndex
                    endpoint = credential.endpoint
                    port = credential.port
                }
            }
            publishTracks = listOf(
                track(AoqTrackType.AoqTrackTypeAudio),
                track(AoqTrackType.AoqTrackTypeData),
            )
            subscribeTracks = listOf(
                track(AoqTrackType.AoqTrackTypeAudio),
                track(AoqTrackType.AoqTrackTypeData),
            )
        }
        val code = created.connect(connectConfig)
        if (code < 0) {
            val message = "AOQ connect rejected (${errorName(code)})."
            AppLog.error(LogCategory.TRANSPORT, message)
            _state.value = TransportState.ERROR
            emit(RealtimeEvent.Failure(message))
            closeEngine()
            return@withContext Result.failure(IOException(message))
        }
        AppLog.info(LogCategory.TRANSPORT, "AOQ connecting", "sid=${credentials.sid}")

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) { deferred.await() }
            ?: Result.failure(IOException("Timed out while connecting AOQ."))
        if (connected.isFailure) {
            _state.value = TransportState.ERROR
            closeEngine()
            return@withContext connected
        }

        _state.value = TransportState.CONNECTED
        startPlayer()
        if (micEnabled) {
            startCapture()
            setAudioPublishing(true)
        } else {
            stopCapture()
            setAudioPublishing(false)
        }
        AppLog.info(LogCategory.TRANSPORT, "AOQ connected")
        Result.success(Unit)
    }

    override fun send(eventJson: String): Boolean {
        val current = engine ?: return false
        if (_state.value != TransportState.CONNECTED) return false
        logOutgoing(eventJson)
        val message = AoqDataMsg().apply { data = eventJson.toByteArray(Charsets.UTF_8) }
        val code = runCatching { current.sendDataMsg(message) }.getOrDefault(-1)
        if (code != 0) {
            AppLog.warn(LogCategory.TRANSPORT, "AOQ data send failed", errorName(code))
            return false
        }
        return true
    }

    override fun sendAudio(pcm: ByteArray) {
        // Capture, Opus encoding and publishing are handled by the SDK.
    }

    override fun setMicEnabled(enabled: Boolean) {
        micEnabled = enabled
        if (_state.value != TransportState.CONNECTED) return
        if (enabled) {
            startCapture()
            setAudioPublishing(true)
        } else {
            stopCapture()
            setAudioPublishing(false)
        }
    }

    override fun setSpeakerMuted(muted: Boolean) {
        if (muted == speakerMuted) return
        speakerMuted = muted
        if (externalPlayback) {
            // The SDK keeps decoding; muting drops the frames instead of tearing
            // the player down, so the next voice turn starts without a restart.
            player.setMuted(muted)
            AppLog.debug(
                LogCategory.AUDIO,
                if (muted) "AOQ external playback muted" else "AOQ external playback unmuted",
            )
            return
        }
        if (muted) stopPlayer() else if (_state.value == TransportState.CONNECTED) startPlayer()
        AppLog.debug(LogCategory.AUDIO, if (muted) "AOQ playback stopped" else "AOQ playback started")
    }

    /**
     * Speech rate for the received audio. The SDK's own renderer ignores speed,
     * so a rate other than 1 switches playback to [PcmPlayer], which stretches
     * the decoded frames with pitch preserved.
     */
    override fun setPlaybackSpeed(speed: Float) {
        val value = speed.coerceIn(MIN_SPEED, MAX_SPEED)
        if (value == playbackSpeed) return
        playbackSpeed = value
        player.setSpeed(value)
        AppLog.info(LogCategory.AUDIO, "AOQ speech rate set", "${value}x")
        if (playerStarted && !speakerMuted && usesExternalPlayback() != externalPlayback) {
            // Switching between SDK rendering and our own needs a player restart.
            stopPlayer()
            if (_state.value == TransportState.CONNECTED) startPlayer()
        }
    }

    override fun close() {
        closing = true
        closeEngine()
        audioExecutor.shutdown()
        _state.value = TransportState.DISCONNECTED
        _micLevel.value = 0f
    }

    private fun closeEngine() {
        val deferred = connectDeferred
        if (deferred != null && !deferred.isCompleted) {
            deferred.complete(Result.failure(IOException("AOQ transport closed.")))
        }
        connectDeferred = null
        captureStarted = false
        playerStarted = false
        mediaEnabled = false
        val current = engine
        engine = null
        stopExternalPlayback(current)
        if (current != null) {
            runCatching { current.stopAudioCapture() }
            runCatching { current.stopAudioPlayer() }
            runCatching { current.disconnect() }
        }
        runCatching { AoqClientEngine.destroy() }
    }

    private fun applyCodecConfigs(engine: AoqClientEngine) {
        val encoder = AoqAudioCodecConfig().apply {
            trackType = AoqTrackType.AoqTrackTypeAudio
            codecType = AoqEncoderType.AoqEncoderTypeAudioOpus
            sampleRate = 48_000
            channel = 1
            bitrate = 32_000
        }
        runCatching { engine.setAudioEncoderConfig(encoder) }
            .onFailure { AppLog.warn(LogCategory.AUDIO, "AOQ encoder config failed", it.message) }
        val decoder = AoqAudioCodecConfig().apply {
            trackType = AoqTrackType.AoqTrackTypeAudio
            codecType = AoqEncoderType.AoqEncoderTypeAudioOpus
            sampleRate = 48_000
            channel = 1
            bitrate = 32_000
        }
        runCatching { engine.setAudioDecoderConfig(decoder) }
            .onFailure { AppLog.warn(LogCategory.AUDIO, "AOQ decoder config failed", it.message) }
    }

    private fun track(type: AoqTrackType) = AoqTrackParam().apply {
        trackType = type
    }

    private fun startCapture() {
        val current = engine ?: return
        if (!captureStarted) {
            val config = AoqAudioCaptureConfig().apply {
                isExternal = false
                isVoipMode = voipMode
                channel = 1
            }
            val code = runCatching { current.startAudioCapture(config) }.getOrDefault(-1)
            if (code < 0) {
                emit(RealtimeEvent.Failure("AOQ microphone could not start (${errorName(code)})."))
                return
            }
            captureStarted = true
        }
        runCatching { current.muteAudioCapture(false) }
    }

    private fun stopCapture() {
        val current = engine ?: return
        runCatching { current.muteAudioCapture(true) }
        // Safe even when capture was never started; also covers SDK auto-capture.
        runCatching { current.stopAudioCapture() }
        captureStarted = false
        _micLevel.value = 0f
    }

    private fun setAudioPublishing(enabled: Boolean) {
        engine?.let { current ->
            runCatching {
                current.enableSendMediaStream(AoqTrackType.AoqTrackTypeAudio, enabled)
            }.onFailure {
                AppLog.warn(LogCategory.TRANSPORT, "Could not set AOQ media publishing", it.message)
            }
            AppLog.info(
                LogCategory.TRANSPORT,
                if (enabled) "AOQ media publishing enabled" else "AOQ media publishing paused",
            )
        }
    }

    private fun startPlayer() {
        val current = engine ?: return
        if (speakerMuted || playerStarted) return
        val externalOutput = hasExternalAudioOutput()
        // Media mode follows the platform route (Bluetooth A2DP / USB / speaker).
        // VoIP mode is only needed for the loudspeaker, where hardware AEC keeps
        // the assistant's own voice out of the microphone.
        voipMode = !externalOutput
        externalPlayback = usesExternalPlayback()
        if (externalPlayback) {
            val observer = runCatching { current.setAudioFrameObserver(playbackFrameListener) }
                .getOrDefault(-1)
            when {
                observer < 0 -> {
                    AppLog.warn(
                        LogCategory.AUDIO,
                        "AOQ playback frame listener rejected",
                        errorName(observer),
                    )
                    externalPlayback = false
                }

                // Without frames from the SDK nothing would reach the speaker.
                !enablePlaybackObserver(current) -> externalPlayback = false
            }
        }
        if (externalPlayback) {
            player.setSpeed(playbackSpeed)
            startExternalPlayer()
        }
        val config = AoqAudioPlaybackConfig().apply {
            isVoipMode = voipMode
            isDefaultSpeaker = !externalOutput
            isExternal = externalPlayback
            channel = 1
        }
        val code = runCatching { current.startAudioPlayer(config) }.getOrDefault(-1)
        if (code < 0) {
            stopExternalPlayback(current)
            emit(RealtimeEvent.Failure("AOQ audio playback could not start (${errorName(code)})."))
            return
        }
        playerStarted = true
        val speaker = if (voipMode) {
            runCatching { current.enableSpeakerphone(true) }.getOrDefault(-1)
        } else {
            -1
        }
        AppLog.info(
            LogCategory.AUDIO,
            "AOQ player started",
            "externalOutput=$externalOutput voip=$voipMode speakerphone=$speaker " +
                "enabled=${runCatching { current.isSpeakerphoneEnabled }.getOrDefault(false)} " +
                "outputs=[${describeOutputs()}]",
        )
    }

    /**
     * Platform output devices at the moment the player starts. Media mode leaves
     * routing to Android, so when a car head unit (USB/Android Auto) turns out to
     * be silent this is the only client-side record of what Android offered.
     */
    private fun describeOutputs(): String = runCatching {
        val manager = appContext.getSystemService(android.media.AudioManager::class.java)
            ?: return ""
        manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).joinToString(";") { device ->
            "${deviceKind(device.type)}#${device.id}"
        }
    }.getOrDefault("")

    /** Short name for the platform audio output types this app routes to. */
    private fun deviceKind(type: Int): String = when (type) {
        android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "earpiece"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
        android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "a2dp"
        android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "sco"
        android.media.AudioDeviceInfo.TYPE_BLE_HEADSET -> "ble_headset"
        android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER -> "ble_speaker"
        android.media.AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
        android.media.AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
        android.media.AudioDeviceInfo.TYPE_HEARING_AID -> "hearing_aid"
        else -> "type$type"
    }

    /**
     * True when a headset, Bluetooth speaker or USB audio device is connected.
     * In that case the SDK should keep the platform route instead of forcing the
     * phone loudspeaker.
     */
    private fun hasExternalAudioOutput(): Boolean = runCatching {
        val manager = appContext.getSystemService(android.media.AudioManager::class.java)
            ?: return false
        manager.getDevices(android.media.AudioManager.GET_DEVICES_OUTPUTS).any { device ->
            when (device.type) {
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                android.media.AudioDeviceInfo.TYPE_BLE_HEADSET,
                android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER,
                android.media.AudioDeviceInfo.TYPE_USB_HEADSET,
                android.media.AudioDeviceInfo.TYPE_USB_DEVICE,
                android.media.AudioDeviceInfo.TYPE_HEARING_AID,
                -> true

                else -> false
            }
        }
    }.getOrDefault(false)

    private fun stopPlayer() {
        val current = engine ?: return
        stopExternalPlayback(current)
        // Drop whatever is queued so a text turn's downlink audio never leaks
        // into the next spoken answer.
        runCatching { current.interruptAudioPlayer(AoqTrackType.AoqTrackTypeAudio, 0) }
        runCatching { current.stopAudioPlayer() }
        playerStarted = false
    }

    /**
     * True when the decoded frames should be rendered by [PcmPlayer] so the
     * speech rate can be applied. At exactly 1x the SDK's own renderer is kept,
     * which preserves its hardware AEC reference on the loudspeaker.
     */
    private fun usesExternalPlayback(): Boolean = abs(playbackSpeed - 1f) >= 0.01f

    private fun startExternalPlayer() {
        onAudioThread { player.start() }
    }

    private fun stopExternalPlayback(current: AoqClientEngine?) {
        externalPlayback = false
        playbackFrames = 0
        if (current != null) disablePlaybackObserver(current)
        onAudioThread { player.stop() }
    }

    /** Player lifecycle is blocking, so it never runs on the caller's thread. */
    private fun onAudioThread(block: () -> Unit) {
        runCatching {
            audioExecutor.execute { runCatching(block) }
        }
    }

    /** Subscribes to the SDK's decoded playback frames (48 kHz mono PCM). */
    private fun enablePlaybackObserver(current: AoqClientEngine): Boolean {
        if (observerEnabled) return true
        val config = AoqAudioObserverConfig().apply {
            sampleRate = PLAYBACK_SAMPLE_RATE
            channels = 1
            mode = AoqAudioObserverMode.AoqAudioObserverModeReadOnly
        }
        val code = runCatching {
            current.enableAudioFrameObserver(
                true,
                AoqAudioSource.AoqAudioSourcePlayback,
                config,
            )
        }.getOrDefault(-1)
        if (code < 0) {
            AppLog.warn(
                LogCategory.AUDIO,
                "AOQ external playback unavailable",
                "enableAudioFrameObserver=${errorName(code)}",
            )
            return false
        }
        observerEnabled = true
        playbackFrames = 0
        AppLog.info(
            LogCategory.AUDIO,
            "AOQ external playback enabled",
            "${PLAYBACK_SAMPLE_RATE}Hz speed=${playbackSpeed}x",
        )
        return true
    }

    private fun disablePlaybackObserver(current: AoqClientEngine) {
        if (!observerEnabled) return
        observerEnabled = false
        val config = AoqAudioObserverConfig().apply {
            sampleRate = PLAYBACK_SAMPLE_RATE
            channels = 1
        }
        runCatching {
            current.enableAudioFrameObserver(
                false,
                AoqAudioSource.AoqAudioSourcePlayback,
                config,
            )
        }
    }

    /**
     * Decoded remote audio. The SDK calls this on its own thread and
     * [AoqAudioFrameData.dataPtr] is only valid during the callback, so the
     * bytes are copied before they are queued.
     */
    private val playbackFrameListener = object : AoqClientListener.AoqAudioFrameListener {
        override fun onPlaybackAudioFrame(frame: AoqAudioFrameData) {
            if (!externalPlayback || speakerMuted) return
            val data = frame.dataPtr ?: return
            val size = frame.dataSize
            if (size <= 0 || size > data.size) return
            val count = playbackFrames + 1
            playbackFrames = count
            val now = android.os.SystemClock.elapsedRealtime()
            if (count == 1L) {
                firstPlaybackFrameAt = now
                AppLog.info(
                    LogCategory.AUDIO,
                    "AOQ playback frames arrived",
                    "sampleRate=${frame.samplesPerSec} channels=${frame.numOfChannels} " +
                        "bytesPerSample=${frame.bytesPerSample} bytes=$size",
                )
            }
            // Pacing check: ten 10 ms frames per second of audio means the SDK
            // hands over the stream in real time and cannot be sped up.
            if (count % 200 == 0L) {
                val wall = now - firstPlaybackFrameAt
                AppLog.info(
                    LogCategory.AUDIO,
                    "AOQ playback pacing",
                    "frames=$count audioMs=${count * size * 1000 / 2 / PLAYBACK_SAMPLE_RATE} " +
                        "wallMs=$wall ratio=${"%.2f".format(java.util.Locale.US, count * size * 1000.0 / 2 / PLAYBACK_SAMPLE_RATE / wall)}",
                )
            }
            player.enqueue(data.copyOf(size))
        }
    }

    private fun ensureOpusPlugin(workDir: File) {
        runCatching {
            val source = File(appContext.applicationInfo.nativeLibraryDir, "libPluginOpus.so")
            if (!source.exists()) {
                AppLog.debug(LogCategory.AUDIO, "Opus plugin is packaged but not extracted")
                return
            }
            val target = File(workDir, "libPluginOpus.so")
            if (!target.exists() || target.length() != source.length()) {
                source.copyTo(target, overwrite = true)
            }
        }.onFailure {
            AppLog.warn(LogCategory.AUDIO, "Could not stage the Opus plugin", it.message)
        }
    }

    private val listener = object : AoqClientListener() {

        override fun onConnectionStatusChange(status: AoqConnectionStatus) {
            AppLog.info(LogCategory.TRANSPORT, "AOQ state: $status")
            when (status) {
                AoqConnectionStatus.AoqConnectionStatusConnecting ->
                    _state.value = TransportState.CONNECTING

                AoqConnectionStatus.AoqConnectionStatusConnected -> {
                    _state.value = TransportState.CONNECTED
                    connectDeferred?.complete(Result.success(Unit))
                }

                AoqConnectionStatus.AoqConnectionStatusFailed -> {
                    _state.value = TransportState.ERROR
                    val error = IOException("AOQ connection failed.")
                    connectDeferred?.complete(Result.failure(error))
                    emit(RealtimeEvent.Failure(error.message.orEmpty()))
                }

                AoqConnectionStatus.AoqConnectionStatusDisconnected -> {
                    _state.value = TransportState.DISCONNECTED
                    if (!closing) emit(RealtimeEvent.Closed(0, "AOQ disconnected"))
                }
            }
        }

        override fun onDataMsg(msg: AoqDataMsg) {
            val bytes = msg.data ?: return
            if (bytes.isEmpty()) return
            val text = String(bytes, Charsets.UTF_8)
            val type = Regex("\"type\"\\s*:\\s*\"([^\"]+)\"").find(text)?.groupValues?.get(1) ?: "?"
            if (AppLog.protocolDump) {
                AppLog.debug(LogCategory.PROTOCOL, "← $type", text.take(1_500))
            } else {
                AppLog.verbose(LogCategory.PROTOCOL, "← $type")
            }
            if (type == "session.updated") {
                // Text mode must not publish the microphone; live mode does.
                setAudioPublishing(micEnabled)
            }
            RealtimeEventParser.parse(text)?.let(::emit)
        }

        override fun onError(code: Int, message: String?) {
            val text = "AOQ error ${errorName(code)}: ${message.orEmpty()}"
            AppLog.error(LogCategory.TRANSPORT, text)
            if (connectDeferred?.isCompleted == false) {
                connectDeferred?.complete(Result.failure(IOException(text)))
            }
            emit(RealtimeEvent.Failure(text))
        }

        override fun onWarning(code: Int, message: String?) {
            AppLog.warn(LogCategory.TRANSPORT, "AOQ warning ${errorName(code)}", message)
        }

        override fun onLocalAudioVolumeIndication(volume: AoqAudioVolume) {
            _micLevel.value = (volume.volume.coerceIn(0, 100)) / 100f
        }

        override fun onAudioDeviceRouteChanged(route: Int) {
            AppLog.info(
                LogCategory.AUDIO,
                "AOQ audio route changed",
                "route=$route outputs=[${describeOutputs()}]",
            )
        }

        override fun onStats(stats: com.alibaba.aoq.clientsdk.AoqClientEngine.AoqStats) {
            val subscribed = stats.audioSubscribeStats?.firstOrNull()
            val published = stats.audioPublishStats?.firstOrNull()
            val network = stats.networkStats
            val sentBytes = stats.audioPublishStats?.sumOf { it.bytes }
            val receivedBytes = stats.audioSubscribeStats?.sumOf { it.bytes }
            traffic.updateTotals(sentBytes, receivedBytes)
            AppLog.debug(
                LogCategory.AUDIO,
                "AOQ stats",
                "subBytes=${subscribed?.bytes ?: 0} subBitrate=${subscribed?.bitrate ?: 0} " +
                    "playVolume=${subscribed?.playVolume ?: -1} " +
                    "pubBytes=${published?.bytes ?: 0} encVolume=${published?.encodeVolume ?: -1} " +
                    "rtt=${network?.rtt ?: -1} loss=${network?.loss ?: -1}",
            )
        }

        override fun onAudioDeviceStateChanged(state: com.alibaba.aoq.clientsdk.AoqClientEngine.AoqAudioDeviceState) {
            AppLog.debug(
                LogCategory.AUDIO,
                "AOQ audio device: ${state.state} reason=${state.reason}",
            )
        }

        override fun onAudioDeviceInterrupted(interrupted: Boolean) {
            AppLog.info(LogCategory.AUDIO, "AOQ audio interrupted=$interrupted")
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

    private fun errorName(code: Int): String =
        runCatching { AoqErrorCode.fromValue(code)?.name }.getOrNull() ?: code.toString()

    private companion object {
        const val CONNECT_TIMEOUT_MS = 25_000L

        /** Rate of the PCM the SDK decoder hands to the observer. */
        const val PLAYBACK_SAMPLE_RATE = 48_000

        /** Matches the range PcmPlayer and TimeStretcher can render. */
        const val MIN_SPEED = 0.5f
        const val MAX_SPEED = 2.5f
    }
}
