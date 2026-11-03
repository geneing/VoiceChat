package com.voicechat.assistant.realtime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio media bytes exchanged with the model in the current session. This is
 * media payload, not protocol/JSON overhead. Timestamps are monotonic
 * milliseconds and let the UI distinguish an active stream from VAD silence.
 */
data class AudioTraffic(
    val sentBytes: Long = 0,
    val receivedBytes: Long = 0,
    val lastSentAtMs: Long = 0,
    val lastReceivedAtMs: Long = 0,
)

class AudioTrafficMeter {

    private val _state = MutableStateFlow(AudioTraffic())
    val state: StateFlow<AudioTraffic> = _state.asStateFlow()

    private var sentBytes = 0L
    private var receivedBytes = 0L

    @Synchronized
    fun addSent(bytes: Int) {
        if (bytes <= 0) return
        sentBytes += bytes
        publish(sentAt = now())
    }

    @Synchronized
    fun addReceived(bytes: Int) {
        if (bytes <= 0) return
        receivedBytes += bytes
        publish(receivedAt = now())
    }

    /**
     * Cumulative counters from transports that report totals (AOQ/WebRTC).
     * Null leaves the corresponding counter unchanged.
     */
    @Synchronized
    fun updateTotals(sent: Long?, received: Long?) {
        val now = now()
        var sentAt = _state.value.lastSentAtMs
        var receivedAt = _state.value.lastReceivedAtMs
        if (sent != null) {
            if (sent > sentBytes) sentAt = now
            sentBytes = sent
        }
        if (received != null) {
            if (received > receivedBytes) receivedAt = now
            receivedBytes = received
        }
        _state.value = AudioTraffic(sentBytes, receivedBytes, sentAt, receivedAt)
    }

    @Synchronized
    fun reset() {
        sentBytes = 0
        receivedBytes = 0
        _state.value = AudioTraffic()
    }

    private fun publish(sentAt: Long? = null, receivedAt: Long? = null) {
        _state.value = AudioTraffic(
            sentBytes = sentBytes,
            receivedBytes = receivedBytes,
            lastSentAtMs = sentAt ?: _state.value.lastSentAtMs,
            lastReceivedAtMs = receivedAt ?: _state.value.lastReceivedAtMs,
        )
    }

    private fun now(): Long = System.nanoTime() / 1_000_000
}
