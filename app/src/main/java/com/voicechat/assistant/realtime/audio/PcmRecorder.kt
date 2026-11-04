package com.voicechat.assistant.realtime.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import kotlin.math.sqrt

/**
 * 16 kHz / mono / 16-bit PCM capture for the WebSocket transport. Chunks are
 * ~50 ms so barge-in and VAD latency stay low without flooding the socket.
 *
 * Capture is continuous, but [onChunk] only receives audio that belongs to a
 * spoken turn: [SpeechGate] drops silence and room noise so the server is not
 * billed for it. [onLevel] still fires for every chunk so the waveform keeps
 * moving while the gate is closed.
 */
class PcmRecorder(
    private val onChunk: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {

    /** Applied when capture starts; changing it while running has no effect. */
    @Volatile
    var vadConfig: VadConfig = VadConfig()


    private var thread: Thread? = null

    @Volatile
    private var running = false

    @Volatile
    private var smoothedLevel = 0f

    @SuppressLint("MissingPermission")
    fun start() {
        synchronized(this) {
            if (running) return
            running = true
            thread = Thread({ loop() }, "pcm-recorder").also { it.start() }
        }
    }

    fun stop() {
        synchronized(this) {
            running = false
            thread?.join(700)
            thread = null
        }
        onLevel(0f)
    }

    private fun loop() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferSize = maxOf(minBuffer * 2, 16_384)
        val gate = SpeechGate(vadConfig, SAMPLE_RATE * BYTES_PER_SAMPLE)
        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var noiseSuppressor: NoiseSuppressor? = null
        var lastLoggedThreshold = gate.thresholdDb
        var chunksSinceLevelLog = 0
        var gateState = gate.state

        try {
            record = AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferSize)
                .build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                onError("Microphone could not be initialised.")
                return
            }
            // VOICE_COMMUNICATION plus these effects is the platform echo path.
            // Some devices only have software AEC; the effect is null then.
            val sessionId = record.audioSessionId
            if (AcousticEchoCanceler.isAvailable()) {
                aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
            AppLog.info(
                LogCategory.AUDIO,
                "PCM capture effects",
                "aec=${aec?.enabled == true} ns=${noiseSuppressor?.enabled == true}",
            )
            record.startRecording()
            AppLog.info(LogCategory.AUDIO, "PCM capture started")
            val buffer = ByteArray(CHUNK_BYTES)
            while (running) {
                val read = record.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val chunk = buffer.copyOf(read)
                    onLevel(levelOf(chunk))
                    gate.process(chunk).forEach(onChunk)
                    if (gate.state != gateState) {
                        gateState = gate.state
                        AppLog.debug(
                            LogCategory.AUDIO,
                            "Voice gate $gateState",
                            "floor=${formatDb(gate.noiseFloorDb)} trigger=${formatDb(gate.thresholdDb)}",
                        )
                    }
                    // Surface large ambient shifts so a noisy room is visible
                    // in the logs without streaming every threshold change.
                    chunksSinceLevelLog++
                    if (chunksSinceLevelLog >= LEVEL_LOG_CHUNKS) {
                        chunksSinceLevelLog = 0
                        val threshold = gate.thresholdDb
                        if (kotlin.math.abs(threshold - lastLoggedThreshold) >= LEVEL_LOG_DELTA_DB) {
                            lastLoggedThreshold = threshold
                            AppLog.info(
                                LogCategory.AUDIO,
                                "Voice gate retuned",
                                "floor=${formatDb(gate.noiseFloorDb)} trigger=${formatDb(threshold)}",
                            )
                        }
                    }
                } else if (read < 0) {
                    onError("Microphone read failed ($read).")
                    break
                }
            }
        } catch (error: SecurityException) {
            onError("Microphone permission is required for voice mode.")
            AppLog.error(LogCategory.AUDIO, "Microphone permission missing", error.message)
        } catch (error: IllegalStateException) {
            onError("Microphone is unavailable: ${error.message}")
            AppLog.error(LogCategory.AUDIO, "Microphone failure", error.message)
        } finally {
            runCatching {
                record?.stop()
                record?.release()
            }
            runCatching { aec?.release() }
            runCatching { noiseSuppressor?.release() }
            val sent = gate.streamedMs
            val captured = gate.capturedMs
            val kept = if (captured > 0f) (sent / captured * 100f) else 100f
            AppLog.info(
                LogCategory.AUDIO,
                "PCM capture stopped",
                "sent=${sent.toInt()}ms of ${captured.toInt()}ms (${kept.toInt()}%) " +
                    "floor=${formatDb(gate.noiseFloorDb)} trigger=${formatDb(gate.thresholdDb)}",
            )
            onLevel(0f)
        }
    }

    private fun levelOf(buffer: ByteArray): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < buffer.size) {
            val sample = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
            val signed = sample.toShort().toInt()
            sum += signed.toDouble() * signed
            count++
            index += 2
        }
        if (count == 0) return smoothedLevel
        val rms = sqrt(sum / count)
        val normalized = (rms / 8_000.0).coerceIn(0.0, 1.0).toFloat()
        smoothedLevel = smoothedLevel * 0.7f + normalized * 0.3f
        return smoothedLevel
    }

    private fun formatDb(value: Float): String =
        String.format(java.util.Locale.US, "%.0fdB", value)

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val BYTES_PER_SAMPLE = 2
        private const val CHUNK_BYTES = 1_600 // 50 ms
        private const val LEVEL_LOG_CHUNKS = 40 // ~2 s
        private const val LEVEL_LOG_DELTA_DB = 6f
    }
}
