package com.voicechat.agent.debug

import android.content.Context
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug-only [AudioInput] that replays a staged 16 kHz mono PCM16 WAV into the
 * real voice loop instead of the microphone.
 *
 * It exists so the whole `capture → VAD/onset → Parakeet-EOU STT → LLM → TTS`
 * pipeline can be exercised on device from a recorded WAV with the UI running and
 * the talk control tapped, without speaking into the device (see
 * `docs/parakeet-eou-streaming.md`). It is compiled into the **debug** source set
 * only.
 *
 * **Fidelity to live capture.** Frames are 320 samples (20 ms) at 16 kHz, exactly
 * like [com.voicechat.agent.audio.MicrophoneAudioInput], and are emitted in real
 * time so the bounded chunk queue never drops audio. After the file's speech ends
 * the input keeps emitting silence indefinitely, so — like a live microphone —
 * capture never ends on its own; the mounted voice session only stops when the
 * user stops it. That silence is what lets the VAD silence cap (and the model's
 * `<EOU>`) commit the turn in the ordinary way instead of through the
 * capture-ended path.
 *
 * It never reads a microphone and never requests the permission.
 */
internal class DebugFileAudioInput(
    private val file: File,
    private val frameSizeSamples: Int = DEFAULT_FRAME_SIZE_SAMPLES,
    private val sampleRateHz: Int = DEFAULT_SAMPLE_RATE_HZ,
) : AudioInput {
    override val format: AudioFormat = AudioFormat(sampleRateHz = sampleRateHz, channelCount = 1)

    @Volatile
    private var closed = false

    override fun frames(): Flow<AudioFrame> =
        flow {
            val samples = DebugPcmWav.readMono16k(file)
            AppLog.i {
                "debug-audio: replaying ${file.name} samples=${samples.size} " +
                    "durationMs=${samples.size * 1000L / sampleRateHz}"
            }
            val frameNanos = frameSizeSamples.toLong() * NANOS_PER_SECOND / sampleRateHz
            var nextFrameAt = System.nanoTime()
            var offset = 0
            while (currentCoroutineContext().isActive && !closed) {
                val sampleCount =
                    if (offset < samples.size) {
                        val end = minOf(offset + frameSizeSamples, samples.size)
                        val count = end - offset
                        offset = end
                        count
                    } else {
                        0 // trailing / idle silence keeps the capture alive like a mic
                    }
                val buffer =
                    if (sampleCount > 0) {
                        samples.copyOfRange(offset - sampleCount, offset)
                    } else {
                        ShortArray(frameSizeSamples)
                    }
                val capturedAt = System.nanoTime()
                emit(AudioFrame(format, buffer, capturedAt))
                nextFrameAt += frameNanos
                val remaining = nextFrameAt - System.nanoTime()
                if (remaining > 0L) {
                    delay(remaining / NANOS_PER_MILLI)
                }
            }
        }

    override suspend fun close() {
        closed = true
    }

    private companion object {
        const val DEFAULT_FRAME_SIZE_SAMPLES = 320
        const val DEFAULT_SAMPLE_RATE_HZ = 16_000
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** Builds the debug audio source when a WAV has been staged, else `null` (mic). */
internal object DebugFileAudioInputFactory {
    /** App-private subdirectory the staging helper writes into. */
    const val DIRECTORY_NAME: String = "debug-audio"

    /** Fixed staged file name. */
    const val FILE_NAME: String = "input.wav"

    /**
     * @return a factory for a staged-WAV source, or `null` when nothing is staged
     *   so production microphone capture is left untouched.
     */
    fun factoryIfPresent(context: Context): (() -> AudioInput)? {
        val file = File(File(context.filesDir, DIRECTORY_NAME), FILE_NAME)
        if (!file.isFile) return null
        AppLog.voice(LogLevel.INFO) {
            "debug-audio: staged WAV found (${file.length()} bytes); using it as the voice input source"
        }
        return { DebugFileAudioInput(file) }
    }
}

/** Minimal RIFF/WAVE PCM16 reader; the staged fixtures are 16 kHz mono. */
private object DebugPcmWav {
    fun readMono16k(file: File): ShortArray {
        val bytes = file.readBytes()
        require(bytes.size >= 44) { "$file is not a RIFF/WAVE file" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "$file is not RIFF" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "$file is not WAVE" }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var channels = -1
        var sampleRate = -1
        var bitsPerSample = -1
        var dataOffset = -1
        var dataLength = -1
        var cursor = 12
        while (cursor + 8 <= bytes.size) {
            val id = String(bytes, cursor, 4, Charsets.US_ASCII)
            val size = buffer.getInt(cursor + 4)
            val body = cursor + 8
            when (id) {
                "fmt " -> {
                    channels = buffer.getShort(body + 2).toInt()
                    sampleRate = buffer.getInt(body + 4)
                    bitsPerSample = buffer.getShort(body + 14).toInt()
                }

                "data" -> {
                    dataOffset = body
                    dataLength = size
                }
            }
            cursor = body + size + (size and 1)
        }
        require(channels == 1) { "$file must be mono, was $channels channels" }
        require(sampleRate == 16_000) { "$file must be 16 kHz, was $sampleRate Hz" }
        require(bitsPerSample == 16) { "$file must be 16-bit PCM, was $bitsPerSample" }
        require(dataOffset >= 0 && dataLength > 0) { "$file has no PCM data chunk" }
        val usable = minOf(dataLength, bytes.size - dataOffset) and 0x7FFFFFFE
        val samples = ShortArray(usable / 2)
        val data = ByteBuffer.wrap(bytes, dataOffset, usable).order(ByteOrder.LITTLE_ENDIAN)
        for (index in samples.indices) samples[index] = data.short
        return samples
    }
}
