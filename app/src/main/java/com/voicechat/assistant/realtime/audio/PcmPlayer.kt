package com.voicechat.assistant.realtime.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streaming 24 kHz PCM output with instant flush for barge-in. Writing happens
 * on a dedicated thread; [clear] drops everything queued and flushes the track.
 *
 * Playback runs at the received sample rate. There is no local rate control:
 * `AudioTrack.playbackParams` is rejected with `IllegalArgumentException:
 * arguments out of range` for every configuration on the target device, and
 * the speech pace comes from the prompt instead.
 */
class PcmPlayer(private val sampleRate: Int = 24_000) {

    private val queue = LinkedBlockingQueue<ByteArray>(96)
    private var thread: Thread? = null
    @Volatile
    private var track: AudioTrack? = null

    @Volatile
    private var running = false

    @Volatile
    private var muted = false

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    fun start() {
        synchronized(this) {
            if (running) return
            running = true
            track = buildTrack()
            applyPreferredDevice(track)
            track?.play()
            thread = Thread({ loop() }, "pcm-player").also { it.start() }
            AppLog.info(LogCategory.AUDIO, "PCM playback started")
        }
    }

    fun enqueue(bytes: ByteArray) {
        if (muted || !running) return
        if (!queue.offer(bytes)) {
            queue.poll()
            queue.offer(bytes)
        }
    }

    fun setMuted(value: Boolean) {
        muted = value
        if (value) clear()
    }

    fun clear() {
        queue.clear()
        runCatching {
            track?.pause()
            track?.flush()
            track?.play()
        }
    }

    fun stop() {
        synchronized(this) {
            running = false
            queue.clear()
            thread?.join(700)
            thread = null
            runCatching {
                track?.pause()
                track?.flush()
                track?.stop()
                track?.release()
            }
            track = null
        }
        AppLog.info(LogCategory.AUDIO, "PCM playback stopped")
    }

    /**
     * Pins playback to the communication device (headset) the route manager
     * selected. Without this the platform can keep assistant audio on the phone
     * speaker while the microphone is on the headset.
     */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        if (preferredDevice?.id == device?.id) return
        preferredDevice = device
        synchronized(this) { applyPreferredDevice(track) }
    }

    private fun applyPreferredDevice(audioTrack: AudioTrack?) {
        if (audioTrack == null) return
        val device = preferredDevice
        val applied = runCatching { audioTrack.setPreferredDevice(device) }.getOrDefault(false)
        AppLog.info(
            LogCategory.AUDIO,
            if (applied) "Playback device selected" else "Playback device rejected",
            device?.let { "${it.typeName()} (${it.productName})" } ?: "system default",
        )
    }

    private fun buildTrack(): AudioTrack {
        val minimum = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val minBuffer = minimum.takeIf { it > 0 } ?: sampleRate * 2 / 4
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(minBuffer, sampleRate * 2 / 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun loop() {
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (muted || chunk.isEmpty()) continue
            runCatching {
                var offset = 0
                val audioTrack = track ?: return@runCatching
                while (offset < chunk.size && running) {
                    val written = audioTrack.write(chunk, offset, chunk.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }.onFailure {
                AppLog.warn(LogCategory.AUDIO, "Playback write failed: ${it.message}")
            }
        }
    }
}
