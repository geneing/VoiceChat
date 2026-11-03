package com.voicechat.assistant.data.model

import kotlinx.serialization.Serializable

enum class TransportKind(val label: String, val detail: String) {
    WEBSOCKET(
        "WebSocket",
        "Reliable control channel. Audio is streamed as PCM. Full feature set.",
    ),
    WEBRTC(
        "WebRTC",
        "Lowest-latency media over UDP with echo cancellation. Voice turns only.",
    ),
    AOQ(
        "AOQ",
        "Alibaba QUIC transport with SDK capture, echo cancellation and low latency. arm64 devices.",
    ),
}

enum class MessageRole { USER, ASSISTANT, TOOL, SYSTEM }

enum class MessageKind { TEXT, VOICE, TOOL, SYSTEM }

enum class AttachmentKind { IMAGE, DOCUMENT }

@Serializable
data class Attachment(
    val path: String,
    val mimeType: String,
    val name: String,
    val kind: AttachmentKind,
)

data class Chat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String,
)

data class Message(
    val id: String,
    val chatId: String,
    val role: MessageRole,
    val kind: MessageKind,
    val text: String,
    val attachments: List<Attachment>,
    val createdAt: Long,
    val streaming: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val ttftMs: Long = -1,
    val costUsd: Double = 0.0,
) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val totalTokens: Int = 0,
)

data class TurnMetrics(
    val ttftMs: Long = -1,
    val totalMs: Long = -1,
    val usage: TokenUsage? = null,
    val costUsd: Double = 0.0,
)

data class UsageTotals(
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val costUsd: Double = 0.0,
    val billingStartMs: Long = 0,
)

data class ConversationStats(
    val turns: Int = 0,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val costUsd: Double = 0.0,
    val lastTtftMs: Long = -1,
    val averageTtftMs: Long = -1,
)
