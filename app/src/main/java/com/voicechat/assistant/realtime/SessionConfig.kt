package com.voicechat.assistant.realtime

import com.voicechat.assistant.data.model.TransportKind
import java.net.URLEncoder

/**
 * URL construction is deliberately pure so it can be unit tested and reused by
 * both transports.
 */
object ApiEndpoints {

    const val WS_PATH = "/api-ws/v1/realtime"
    const val WEBRTC_PATH = "/api/v1/webrtc/realtime"

    fun host(configHost: String, workspaceId: String): String {
        var host = configHost.trim()
        host = host.removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("wss://")
            .removePrefix("ws://")
            .trimEnd('/')
        if (host.contains('/')) host = host.substringBefore('/')
        val workspace = workspaceId.trim().trimEnd('.')
        if (workspace.isBlank()) return host.ifBlank { "dashscope.aliyuncs.com" }
        if (host.isBlank()) return if (host.isEmpty()) "dashscope.aliyuncs.com" else host
        if (host == workspace || host.startsWith("$workspace.")) return host
        return "$workspace.$host"
    }

    fun webSocketUrl(
        host: String,
        workspaceId: String,
        model: String,
    ): String = "wss://${host(host, workspaceId)}$WS_PATH?model=${encode(model)}"

    fun webRtcSignalingUrl(
        host: String,
        workspaceId: String,
        model: String,
    ): String = "https://${host(host, workspaceId)}$WEBRTC_PATH?model=${encode(model)}"

    fun aoqBootstrapUrl(
        host: String,
        workspaceId: String,
        model: String,
    ): String = "https://${host(host, workspaceId)}$WEBRTC_PATH?model=${encode(model)}"

    fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun urlFor(config: SessionConfig): String = when (config.transport) {
        TransportKind.WEBSOCKET -> webSocketUrl(config.host, config.workspaceId, config.model)
        TransportKind.WEBRTC -> webRtcSignalingUrl(config.host, config.workspaceId, config.model)
        TransportKind.AOQ -> aoqBootstrapUrl(config.host, config.workspaceId, config.model)
    }
}

data class SessionConfig(
    val model: String,
    val voice: String,
    val instructions: String,
    val transport: TransportKind,
    val apiKey: String,
    val host: String,
    val workspaceId: String,
    val aoqTokenUrl: String = "",
    val turnDetection: String = "semantic_vad",
    val vadThreshold: Float = 0.5f,
    val silenceMs: Int = 600,
    val speechRate: Double = 0.0,
    val inputTranscription: Boolean = true,
    val transcriptionModel: String = "qwen3-asr-flash-realtime",
    val toolsJson: String? = null,
    val nativeSearch: Boolean = false,
    val searchSources: Boolean = true,
    val priceInputPerMillion: Double = 0.0,
    val priceOutputPerMillion: Double = 0.0,
    val languageHint: String = "",
) {
    val endpoint: String get() = ApiEndpoints.urlFor(this)
}
