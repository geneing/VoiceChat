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
 */
class PcmRecorder(
    private val onChunk: (ByteArray) -> Unit,
    private val onLevel: (Float) -> Unit,
    private val onError: (String) -> Unit,
) {

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
        var record: AudioRecord? = null
        var aec: AcousticEchoCanceler? = null
        var noiseSuppressor: NoiseSuppressor? = null
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
                    onChunk(buffer.copyOf(read))
                    onLevel(levelOf(buffer, read))
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
            AppLog.info(LogCategory.AUDIO, "PCM capture stopped")
            onLevel(0f)
        }
    }

    private fun levelOf(buffer: ByteArray, length: Int): Float {
        var sum = 0.0
        var count = 0
        var index = 0
        while (index + 1 < length) {
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

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHUNK_BYTES = 1_600 // 50 ms
    }
}
