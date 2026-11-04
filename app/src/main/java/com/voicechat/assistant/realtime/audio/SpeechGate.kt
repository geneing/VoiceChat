package com.voicechat.assistant.realtime.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * On-device voice gate configuration. The gate decides which captured 16 kHz
 * frames are worth sending to the server.
 */
data class VadConfig(
    val enabled: Boolean = true,
    /** Audio kept from before the onset so the first syllable is not clipped. */
    val preRollMs: Int = 1_000,
    /** How much sustained speech opens the gate. */
    val onsetMs: Int = 100,
    /** Quiet time before the gate stops forwarding real audio. */
    val hangoverMs: Int = 1_400,
    /** Digital silence appended after speech so the server closes the turn. */
    val tailSilenceMs: Int = 1_500,
    /** How far above the ambient noise floor speech must sit, in dB. */
    val sensitivityDb: Float = 9f,
    /** Trigger floor for a silent room, dBFS. */
    val minThresholdDb: Float = -50f,
    /** Learning window at capture start, before the gate may open. */
    val warmupMs: Int = 600,
)

/**
 * Energy-based voice activity detector that runs entirely on the device.
 *
 * Android exposes no public VAD API, so this derives speech from the capture
 * stream: a smoothed frame level is compared against a trigger level that
 * follows the measured ambient noise floor, which is what keeps the gate usable
 * in a quiet bedroom and on a busy street. Frames before the onset are kept in
 * a pre-roll buffer so the first syllable survives, and a stretch of digital
 * silence is appended after the last syllable so the server's own turn detector
 * still sees a clean end of speech.
 *
 * Pure Kotlin on purpose: the state machine is unit tested without an emulator.
 */
class SpeechGate(
    private val config: VadConfig,
    private val bytesPerSecond: Int = DEFAULT_BYTES_PER_SECOND,
) {

    enum class State { IDLE, SPEAKING, TAIL }

    private val preRoll = ArrayDeque<ByteArray>()
    private var preRollMs = 0f
    var state = State.IDLE
        private set
    private var speechRunMs = 0f
    private var quietRunMs = 0f
    private var tailRemainingMs = 0f
    private var smoothedDb = QUIET_DB
    private var warmupRemainingMs = 0f

    /** Learned ambient level, dBFS. */
    var noiseFloorDb = QUIET_DB
        private set

    /** Level a frame must reach to be treated as speech, dBFS. */
    val thresholdDb: Float
        get() = max(noiseFloorDb + config.sensitivityDb, config.minThresholdDb)

    /** Captured audio since the last reset, in milliseconds. */
    var capturedMs = 0f
        private set

    /** Audio actually forwarded to the transport, in milliseconds. */
    var streamedMs = 0f
        private set

    val speaking: Boolean get() = state == State.SPEAKING || state == State.TAIL

    init {
        reset()
    }

    fun reset() {
        preRoll.clear()
        preRollMs = 0f
        state = State.IDLE
        speechRunMs = 0f
        quietRunMs = 0f
        tailRemainingMs = 0f
        smoothedDb = QUIET_DB
        noiseFloorDb = QUIET_DB
        warmupRemainingMs = if (config.enabled) config.warmupMs.toFloat() else 0f
        capturedMs = 0f
        streamedMs = 0f
    }

    /**
     * Classifies one captured chunk and returns the audio to send for it. An
     * empty list means the chunk is dropped: silence, room noise, or the pause
     * between turns.
     */
    fun process(chunk: ByteArray): List<ByteArray> {
        if (chunk.isEmpty()) return emptyList()
        val durationMs = durationMsOf(chunk)
        capturedMs += durationMs
        if (!config.enabled) {
            streamedMs += durationMs
            return listOf(chunk)
        }
        val level = levelDbOf(chunk)
        smoothedDb = smoothedDb * SMOOTHING_KEEP + level * (1f - SMOOTHING_KEEP)
        return when (state) {
            State.IDLE -> onIdle(chunk, durationMs)
            State.SPEAKING -> onSpeaking(chunk, durationMs)
            State.TAIL -> onTail(chunk, durationMs)
        }
    }

    private fun onIdle(chunk: ByteArray, durationMs: Float): List<ByteArray> {
        pushPreRoll(chunk, durationMs)
        adaptNoise(tracker = WARMUP_TRACKER)
        if (warmupRemainingMs > 0f) {
            warmupRemainingMs -= durationMs
            return emptyList()
        }
        if (smoothedDb < thresholdDb) {
            speechRunMs = 0f
            return emptyList()
        }
        speechRunMs += durationMs
        if (speechRunMs < config.onsetMs) return emptyList()
        val pending = preRoll.toList()
        streamedMs += preRollMs
        preRoll.clear()
        preRollMs = 0f
        state = State.SPEAKING
        speechRunMs = 0f
        quietRunMs = 0f
        return pending
    }

    private fun onSpeaking(chunk: ByteArray, durationMs: Float): List<ByteArray> {
        adaptNoise(tracker = SPEAKING_TRACKER)
        val endThreshold = thresholdDb - END_HYSTERESIS_DB
        if (smoothedDb >= endThreshold) {
            quietRunMs = 0f
            streamedMs += durationMs
            return listOf(chunk)
        }
        quietRunMs += durationMs
        if (quietRunMs < config.hangoverMs) {
            streamedMs += durationMs
            return listOf(chunk)
        }
        state = State.TAIL
        quietRunMs = 0f
        tailRemainingMs = config.tailSilenceMs.toFloat()
        return onTail(chunk, durationMs)
    }

    /**
     * Emits the trailing silence that lets the server close the turn, but keeps
     * listening: a sentence that resumes inside the tail must not be replaced by
     * padding, so the tail is abandoned and the buffered audio is replayed.
     */
    private fun onTail(chunk: ByteArray, durationMs: Float): List<ByteArray> {
        pushPreRoll(chunk, durationMs)
        adaptNoise(tracker = WARMUP_TRACKER)
        if (smoothedDb >= thresholdDb) {
            speechRunMs += durationMs
            if (speechRunMs >= config.onsetMs) {
                val pending = preRoll.toList()
                streamedMs += preRollMs
                preRoll.clear()
                preRollMs = 0f
                state = State.SPEAKING
                speechRunMs = 0f
                quietRunMs = 0f
                tailRemainingMs = 0f
                return pending
            }
        } else {
            speechRunMs = 0f
        }

        val sendMs = minOf(durationMs, tailRemainingMs)
        tailRemainingMs -= sendMs
        if (tailRemainingMs <= 0f) {
            tailRemainingMs = 0f
            state = State.IDLE
        }
        val bytes = (sendMs * bytesPerSecond / 1_000f).toInt().let { it - (it % 2) }
        if (bytes <= 0) return emptyList()
        streamedMs += sendMs
        return listOf(ByteArray(bytes))
    }

    private fun pushPreRoll(chunk: ByteArray, durationMs: Float) {
        preRoll.addLast(chunk)
        preRollMs += durationMs
        // Keep at least one chunk so an onset never flushes an empty buffer.
        while (preRoll.size > 1 && preRollMs > config.preRollMs) {
            preRollMs -= durationMsOf(preRoll.removeFirst())
        }
    }

    /**
     * Follows the room quickly downwards and slowly upwards. While the gate is
     * closed this tracks the ambient level; while it is open the floor is almost
     * frozen so a long sentence cannot lift the trigger above the speaker.
     */
    private fun adaptNoise(tracker: Float) {
        val rate = if (smoothedDb < noiseFloorDb) FAST_DOWN_TRACKER else tracker
        noiseFloorDb += (smoothedDb - noiseFloorDb) * rate
        noiseFloorDb = noiseFloorDb.coerceIn(MIN_FLOOR_DB, MAX_FLOOR_DB)
    }

    private fun durationMsOf(chunk: ByteArray): Float = chunk.size * 1_000f / bytesPerSecond

    private fun levelDbOf(chunk: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < chunk.size) {
            val sample = ((chunk[index].toInt() and 0xFF) or (chunk[index + 1].toInt() shl 8))
                .toShort()
                .toInt()
            sum += sample.toDouble() * sample
            count++
            index += 2
        }
        if (count == 0) return QUIET_DB
        val rms = sqrt(sum / count)
        if (rms < 1.0) return QUIET_DB
        return (20.0 * log10(rms / 32_768.0)).toFloat()
    }

    companion object {
        const val DEFAULT_BYTES_PER_SECOND = 32_000

        private const val QUIET_DB = -90f
        private const val MIN_FLOOR_DB = -90f
        private const val MAX_FLOOR_DB = -18f
        private const val SMOOTHING_KEEP = 0.7f
        private const val FAST_DOWN_TRACKER = 0.25f
        private const val WARMUP_TRACKER = 0.15f
        private const val SPEAKING_TRACKER = 0.0008f
        private const val END_HYSTERESIS_DB = 3f
    }
}
