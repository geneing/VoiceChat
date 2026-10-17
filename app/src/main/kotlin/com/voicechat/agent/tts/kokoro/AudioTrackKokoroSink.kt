package com.voicechat.agent.tts.kokoro

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import com.voicechat.agent.tts.TtsOutputRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Streaming AudioTrack sink for the graph's native 24 kHz mono float output.
 *
 * `ENCODING_PCM_FLOAT` preserves the graph's output format (`docs/decisions.md`
 * §2.2). Writes and playback-head waits stay on IO so a long utterance cannot
 * block Compose or the capture callback; playback completion is measured from
 * samples played, as required by `docs/voice-quality-and-latency.md` §Latency strategy.
 */
class AudioTrackKokoroSink :
    KokoroAudioSink,
    KokoroAudioRouteProvider {
    private val lock = Any()
    private val closed = AtomicBoolean(false)
    private val stopGeneration = AtomicLong()

    @Volatile private var track: AudioTrack? = null

    override suspend fun play(
        samples: FloatArray,
        onAudible: () -> Unit,
    ): Boolean {
        val generation = stopGeneration.get()
        return withContext(Dispatchers.IO) {
            if (closed.get()) throw IllegalStateException("Kokoro audio sink is closed")
            if (samples.isEmpty()) return@withContext true

            val player = ensureTrack()
            if (generation != stopGeneration.get()) return@withContext false
            val initialPlaybackHead = player.playbackHeadPosition
            player.play()

            var offset = 0
            var notifiedAudible = false
            while (offset < samples.size) {
                if (closed.get() || generation != stopGeneration.get()) return@withContext false
                val written = player.write(samples, offset, samples.size - offset, AudioTrack.WRITE_BLOCKING)
                if (written < 0) {
                    if (generation != stopGeneration.get()) return@withContext false
                    throw IOException("AudioTrack float write failed ($written)")
                }
                if (written == 0) {
                    delay(2)
                    continue
                }
                offset += written
                if (!notifiedAudible) {
                    notifiedAudible = true
                    onAudible()
                }
            }

            val targetPlaybackHead = initialPlaybackHead + samples.size
            while (player.playbackHeadPosition < targetPlaybackHead) {
                if (closed.get() || generation != stopGeneration.get()) return@withContext false
                delay(4)
            }
            generation == stopGeneration.get() && !closed.get()
        }
    }

    override suspend fun stop() {
        stopGeneration.incrementAndGet()
        withContext(Dispatchers.IO) {
            val player = track ?: return@withContext
            runCatching { player.pause() }
            runCatching { player.flush() }
            // AudioTrack has no reset() API; stop resets the streaming playback head
            // after pause/flush so the next utterance begins from a clean position.
            runCatching { player.stop() }
        }
    }

    override fun outputRoute(): TtsOutputRoute? =
        runCatching { track?.routedDevice?.type?.toTtsOutputRoute() }
            .getOrNull()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        stopGeneration.incrementAndGet()
        synchronized(lock) {
            val player = track
            track = null
            if (player != null) {
                runCatching { player.pause() }
                runCatching { player.flush() }
                runCatching { player.stop() }
                runCatching { player.release() }
            }
        }
    }

    private fun ensureTrack(): AudioTrack =
        synchronized(lock) {
            if (closed.get()) throw IllegalStateException("Kokoro audio sink is closed")
            track?.let { return@synchronized it }

            val minimum = AudioTrack.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL_MASK, ENCODING)
            val bufferBytes = maxOf(minimum.takeIf { it > 0 } ?: FALLBACK_BUFFER_BYTES, TARGET_BUFFER_BYTES)
            val created =
                AudioTrack
                    .Builder()
                    .setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    ).setAudioFormat(
                        AudioFormat
                            .Builder()
                            .setEncoding(ENCODING)
                            .setSampleRate(SAMPLE_RATE_HZ)
                            .setChannelMask(CHANNEL_MASK)
                            .build(),
                    ).setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferBytes)
                    .build()
            if (created.state != AudioTrack.STATE_INITIALIZED) {
                created.release()
                throw IOException("AudioTrack could not initialize for Kokoro playback")
            }
            track = created
            created
        }

    private fun Int.toTtsOutputRoute(): TtsOutputRoute =
        when (this) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> TtsOutputRoute.BUILTIN_SPEAKER
            AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> TtsOutputRoute.BUILTIN_EARPIECE
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> TtsOutputRoute.WIRED_HEADSET
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> TtsOutputRoute.WIRED_HEADPHONES
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> TtsOutputRoute.BLUETOOTH_SCO
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> TtsOutputRoute.BLUETOOTH_A2DP
            AudioDeviceInfo.TYPE_BLE_HEADSET -> TtsOutputRoute.BLE_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE -> TtsOutputRoute.USB_DEVICE
            AudioDeviceInfo.TYPE_USB_HEADSET -> TtsOutputRoute.USB_HEADSET
            AudioDeviceInfo.TYPE_HDMI -> TtsOutputRoute.HDMI
            AudioDeviceInfo.TYPE_TELEPHONY -> TtsOutputRoute.TELEPHONY
            else -> TtsOutputRoute.UNKNOWN
        }

    private companion object {
        const val SAMPLE_RATE_HZ = KokoroArtifact.SAMPLE_RATE_HZ
        const val CHANNEL_MASK = AudioFormat.CHANNEL_OUT_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_FLOAT
        const val FALLBACK_BUFFER_BYTES = SAMPLE_RATE_HZ * java.lang.Float.BYTES / 2
        const val TARGET_BUFFER_BYTES = SAMPLE_RATE_HZ * java.lang.Float.BYTES / 5
    }
}
