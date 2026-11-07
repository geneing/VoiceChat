package com.voicechat.assistant.realtime

import com.voicechat.assistant.data.model.TransportKind
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class TransportState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

/** Transport-agnostic contract used by [RealtimeSession]. */
interface RealtimeTransport {

    val kind: TransportKind

    val events: SharedFlow<RealtimeEvent>

    val state: StateFlow<TransportState>

    val micLevel: StateFlow<Float>

    val audioTraffic: StateFlow<AudioTraffic>

    suspend fun connect(config: SessionConfig): Result<Unit>

    fun send(eventJson: String): Boolean

    fun sendAudio(pcm: ByteArray)

    fun setMicEnabled(enabled: Boolean)

    fun setSpeakerMuted(muted: Boolean)

    /**
     * Playback speed multiplier for transports that render audio themselves.
     * SDK-owned audio paths (WebRTC, AOQ) ignore it.
     */
    fun setPlaybackSpeed(speed: Float) = Unit

    fun close()
}
