package com.voicechat.assistant.realtime.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechGateTest {

    private val config = VadConfig()

    @Test
    fun `silence is not forwarded once the gate has warmed up`() {
        val gate = SpeechGate(config)
        val silence = tone(SILENT_DB, CHUNK_MS)
        repeat(FRAMES_PER_SECOND * 2) {
            assertTrue(gate.process(silence).isEmpty())
        }
        assertEquals(0f, gate.streamedMs, 0.01f)
        assertTrue(gate.capturedMs > 0f)
        assertFalse(gate.speaking)
    }

    @Test
    fun `speech flushes the pre-roll so the first syllable survives`() {
        val gate = SpeechGate(config)
        repeat(FRAMES_PER_SECOND) { gate.process(tone(SILENT_DB, CHUNK_MS)) }
        var forwarded = 0f
        var chunks = 0
        while (forwarded < config.preRollMs && chunks < FRAMES_PER_SECOND * 5) {
            forwarded += gate.process(tone(SPEECH_DB, CHUNK_MS)).sumOf { it.size } / BYTES_PER_MS
            chunks++
        }
        // Onset costs onsetMs of speech, and all of it is replayed from the
        // pre-roll, so the first burst carries at least a second of audio.
        assertTrue("forwarded $forwarded ms", forwarded >= config.preRollMs)
        assertTrue(gate.speaking)
    }

    @Test
    fun `tail silence is appended and streaming then stops`() {
        val gate = SpeechGate(config)
        repeat(FRAMES_PER_SECOND) { gate.process(tone(SILENT_DB, CHUNK_MS)) }
        repeat(FRAMES_PER_SECOND) { gate.process(tone(SPEECH_DB, CHUNK_MS)) }
        assertTrue(gate.speaking)
        val speechMs = gate.streamedMs

        val quiet = tone(SILENT_DB, CHUNK_MS)
        repeat(((config.hangoverMs + config.tailSilenceMs) / CHUNK_MS) + 30) {
            gate.process(quiet)
        }
        assertFalse("gate should close after the tail", gate.speaking)
        // Everything after the speech run is the hangover plus the tail.
        val trailing = gate.streamedMs - speechMs
        assertEquals(
            (config.hangoverMs + config.tailSilenceMs).toFloat(),
            trailing,
            2 * CHUNK_MS.toFloat(),
        )
    }

    @Test
    fun `tail chunks are digital silence`() {
        val gate = SpeechGate(config)
        repeat(FRAMES_PER_SECOND) { gate.process(tone(SILENT_DB, CHUNK_MS)) }
        repeat(FRAMES_PER_SECOND) { gate.process(tone(SPEECH_DB, CHUNK_MS)) }
        val quiet = tone(AMBIENT_DB, CHUNK_MS)
        repeat((config.hangoverMs / CHUNK_MS) + 2) { gate.process(quiet) }
        val tail = gate.process(quiet).single()
        assertTrue("tail should be silence", tail.all { it == 0.toByte() })
    }

    @Test
    fun `noisy room raises the trigger`() {
        val gate = SpeechGate(config)
        repeat(FRAMES_PER_SECOND * 4) { gate.process(tone(LOUD_ROOM_DB, CHUNK_MS)) }
        assertTrue(
            "floor should follow the room, was ${gate.noiseFloorDb}",
            gate.noiseFloorDb > LOUD_ROOM_DB - 4f,
        )
        assertTrue("trigger should rise", gate.thresholdDb > config.minThresholdDb + 6f)
    }

    @Test
    fun `quiet room keeps the absolute trigger floor`() {
        val gate = SpeechGate(config)
        repeat(FRAMES_PER_SECOND * 2) { gate.process(tone(SILENT_DB, CHUNK_MS)) }
        assertEquals(config.minThresholdDb, gate.thresholdDb, 0.5f)
    }

    @Test
    fun `disabling the gate forwards every chunk`() {
        val gate = SpeechGate(config.copy(enabled = false))
        val silence = tone(SILENT_DB, CHUNK_MS)
        assertEquals(1, gate.process(silence).size)
        assertEquals(CHUNK_MS.toFloat(), gate.streamedMs, 0.01f)
        assertEquals(gate.capturedMs, gate.streamedMs, 0.01f)
    }

    @Test
    fun `speech resuming inside the tail is not replaced by padding`() {
        val gate = SpeechGate(config)
        repeat(FRAMES_PER_SECOND) { gate.process(tone(SILENT_DB, CHUNK_MS)) }
        repeat(FRAMES_PER_SECOND) { gate.process(tone(SPEECH_DB, CHUNK_MS)) }
        val quiet = tone(SILENT_DB, CHUNK_MS)
        repeat((config.hangoverMs / CHUNK_MS) + 4) { gate.process(quiet) }
        // Mid-tail the speaker continues: the gate must go back to streaming.
        var resumed = 0f
        repeat(FRAMES_PER_SECOND) {
            gate.process(tone(SPEECH_DB, CHUNK_MS)).forEach { resumed += it.size / BYTES_PER_MS }
        }
        assertTrue("resumed audio was dropped", resumed >= config.preRollMs)
        assertTrue(gate.speaking)
    }

    @Test
    fun `reset clears the learned floor and counters`() {
        val gate = SpeechGate(config)
        repeat(FRAMES_PER_SECOND * 4) { gate.process(tone(LOUD_ROOM_DB, CHUNK_MS)) }
        gate.reset()
        assertEquals(0f, gate.streamedMs, 0.01f)
        assertEquals(0f, gate.capturedMs, 0.01f)
        assertFalse(gate.speaking)
    }

    /** Square wave at [db] dBFS, alternating sign so it has real energy. */
    private fun tone(db: Float, ms: Int): ByteArray {
        val amplitude = if (db <= SILENT_DB) {
            0
        } else {
            (32_767.0 * Math.pow(10.0, db / 20.0)).toInt().coerceIn(0, 32_767)
        }
        val samples = ms * SAMPLE_RATE / 1_000
        val bytes = ByteArray(samples * 2)
        for (index in 0 until samples) {
            val value = if (index % 2 == 0) amplitude else -amplitude
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_MS = 50
        const val FRAMES_PER_SECOND = 1_000 / CHUNK_MS
        const val BYTES_PER_MS = SAMPLE_RATE * 2 / 1_000f
        const val SILENT_DB = -90f
        const val AMBIENT_DB = -70f
        const val LOUD_ROOM_DB = -36f
        const val SPEECH_DB = -20f
    }
}
