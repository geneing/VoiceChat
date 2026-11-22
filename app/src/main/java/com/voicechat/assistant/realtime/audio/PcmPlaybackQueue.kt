package com.voicechat.assistant.realtime.audio

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A bounded FIFO measured in PCM bytes so bursts of small packets don't cut speech. */
internal class PcmPlaybackQueue(private val maxBytes: Int) {
    data class Chunk(val bytes: ByteArray, val generation: Long)

    private val lock = ReentrantLock()
    private val available = lock.newCondition()
    private val chunks = ArrayDeque<Chunk>()
    private var byteCount = 0
    val queuedBytes: Int get() = lock.withLock { byteCount }

    fun offer(chunk: Chunk): Boolean = lock.withLock {
        // Bound object overhead too, even if a producer sends tiny chunks.
        if (chunk.bytes.size > maxBytes - byteCount || chunks.size == MAX_CHUNKS) return false
        chunks.addLast(chunk)
        byteCount += chunk.bytes.size
        available.signal()
        true
    }

    fun take(): Chunk = lock.withLock {
        while (chunks.isEmpty()) available.await()
        chunks.removeFirst().also { byteCount -= it.bytes.size }
    }

    fun clear() = lock.withLock {
        chunks.clear()
        byteCount = 0
    }

    private companion object {
        const val MAX_CHUNKS = 8192
    }
}
