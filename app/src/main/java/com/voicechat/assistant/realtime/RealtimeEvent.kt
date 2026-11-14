package com.voicechat.assistant.realtime

import com.voicechat.assistant.data.model.TokenUsage

sealed interface RealtimeEvent {

    data class SessionUpdated(val sessionId: String?) : RealtimeEvent

    data class SpeechStarted(val itemId: String?) : RealtimeEvent

    data class SpeechStopped(val itemId: String?) : RealtimeEvent

    data class UserTranscriptDelta(val text: String, val stash: String) : RealtimeEvent

    data class UserTranscriptCompleted(val transcript: String) : RealtimeEvent

    data class ResponseCreated(val responseId: String?) : RealtimeEvent

    data class ResponseDone(
        val responseId: String?,
        val status: String?,
        val usage: TokenUsage?,
        /** Types of the finished response's output items: `message`, `function_call`. */
        val outputTypes: List<String> = emptyList(),
    ) : RealtimeEvent

    data class ResponseCancelled(val reason: String?) : RealtimeEvent

    data class AssistantTextDelta(val delta: String) : RealtimeEvent

    data class AssistantTextDone(val text: String) : RealtimeEvent

    data class AssistantAudioDelta(val audio: ByteArray) : RealtimeEvent {
        override fun equals(other: Any?): Boolean = this === other
        override fun hashCode(): Int = System.identityHashCode(this)
    }

    data class AssistantTranscriptDelta(val delta: String) : RealtimeEvent

    data class AssistantTranscriptDone(val text: String) : RealtimeEvent

    data class FunctionCall(
        val callId: String,
        val name: String,
        val arguments: String,
    ) : RealtimeEvent

    /**
     * Transport-level failure. [recoverable] tells [RealtimeSession] whether
     * reconnecting can help; permanent problems (missing key, unsupported ABI)
     * are surfaced to the user instead of retried.
     */
    data class Failure(val message: String, val recoverable: Boolean = true) : RealtimeEvent

    data class Closed(val code: Int, val reason: String) : RealtimeEvent

    data class Error(val message: String) : RealtimeEvent

    data class Unknown(val type: String, val raw: String) : RealtimeEvent
}
