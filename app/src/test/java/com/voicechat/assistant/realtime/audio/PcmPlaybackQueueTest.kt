package com.voicechat.assistant.realtime.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmPlaybackQueueTest {
    @Test
    fun `a burst exceeding the old packet limit plays every chunk in arrival order`() {
        val queue = PcmPlaybackQueue(maxBytes = 24_000 * 2 * 120)
        // Simulate twelve seconds of 20 ms chunks arriving ahead of playback.
        val chunks = List(600) { PcmPlaybackQueue.Chunk(ByteArray(960), generation = 1) }

        chunks.forEach { assertTrue(queue.offer(it)) }
        assertEquals(576_000, queue.queuedBytes)
        chunks.forEach { assertSame(it, queue.take()) }
        assertEquals(0, queue.queuedBytes)
    }

    @Test
    fun `overflow preserves the queued prefix and consumption restores capacity`() {
        val queue = PcmPlaybackQueue(maxBytes = 10)
        val first = PcmPlaybackQueue.Chunk(ByteArray(4), generation = 1)
        val second = PcmPlaybackQueue.Chunk(ByteArray(6), generation = 1)
        val next = PcmPlaybackQueue.Chunk(ByteArray(2), generation = 1)

        assertTrue(queue.offer(first))
        assertTrue(queue.offer(second))
        assertFalse(queue.offer(next))
        assertEquals(10, queue.queuedBytes)
        assertSame(first, queue.take())
        assertTrue(queue.offer(next))
        assertSame(second, queue.take())
        assertSame(next, queue.take())
    }

    @Test
    fun `clear discards the old generation and releases its byte budget`() {
        val queue = PcmPlaybackQueue(maxBytes = 8)
        assertTrue(queue.offer(PcmPlaybackQueue.Chunk(ByteArray(8), generation = 1)))
        queue.clear()

        assertEquals(0, queue.queuedBytes)
        val fresh = PcmPlaybackQueue.Chunk(ByteArray(8), generation = 2)
        assertTrue(queue.offer(fresh))
        assertSame(fresh, queue.take())
    }
}
