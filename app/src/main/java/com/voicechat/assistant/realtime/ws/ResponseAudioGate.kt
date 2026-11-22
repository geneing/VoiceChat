package com.voicechat.assistant.realtime.ws

/** Tracks which response may supply PCM. The WebSocket transport serializes access. */
internal class ResponseAudioGate {
    private var responseId: String? = null
    private var accepting = false
    private var speechActive = false

    /** Returns true when the new response replaces a stream that is still open. */
    fun begin(id: String?): Boolean {
        if (id != null && id == responseId) return false
        val replaced = accepting
        responseId = id
        accepting = !speechActive
        return replaced
    }

    fun accepts(id: String?): Boolean = accepting && matches(id)

    /** Ending normally closes admission and lets PCM already queued play out. */
    fun end(id: String?): Boolean {
        if (!accepts(id)) return false
        interrupt()
        return true
    }

    fun speechStarted() {
        speechActive = true
        interrupt()
    }

    fun speechStopped() {
        speechActive = false
    }

    fun interrupt() {
        accepting = false
    }

    private fun matches(id: String?) = responseId == null || id == null || id == responseId
}
