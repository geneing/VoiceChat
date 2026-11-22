package com.voicechat.assistant.realtime.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioTrack
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory

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

    // The server can generate a long answer faster than Android plays it.
    // At 24 kHz mono PCM16 this permits up to 5.76 MB of queued audio.
    private val bytesPerSecond = sampleRate * 2
    private val queue = PcmPlaybackQueue(bytesPerSecond * MAX_BUFFERED_SECONDS)
    val queuedDurationMs: Long get() = queue.queuedBytes * 1000L / bytesPerSecond

    enum class EnqueueResult { QUEUED, INACTIVE, FULL }
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var generation = 0L
    private var muted = true

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    fun start() {
        synchronized(this) {
            if (track != null) return
            track = buildTrack()
            applyPreferredDevice(track)
            thread = Thread({ loop() }, "pcm-player").also { it.start() }
            AppLog.info(LogCategory.AUDIO, "PCM playback started")
        }
    }

    @Synchronized
    fun enqueue(bytes: ByteArray): EnqueueResult {
        if (muted || track == null || bytes.isEmpty()) return EnqueueResult.INACTIVE
        return if (queue.offer(PcmPlaybackQueue.Chunk(bytes, generation))) {
            EnqueueResult.QUEUED
        } else {
            EnqueueResult.FULL
        }
    }

    @Synchronized
    fun setMuted(value: Boolean) {
        if (muted == value) return
        muted = value
        if (value) clear()
    }

    @Synchronized
    fun clear() {
        generation++
        queue.clear()
        runCatching {
            track?.pause()
            track?.flush()
        }
    }

    fun stop() {
        synchronized(this) {
            val audioTrack = track ?: return
            muted = true
            generation++
            queue.clear()
            thread?.interrupt()
            thread = null
            runCatching {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.stop()
            }
            audioTrack.release()
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
        try {
            while (!Thread.currentThread().isInterrupted) {
                val chunk = queue.take()
                try {
                    play(chunk)
                } catch (error: IllegalStateException) {
                    AppLog.warn(LogCategory.AUDIO, "PCM playback track unavailable", error.message)
                }
            }
        } catch (_: InterruptedException) {
            // stop() wakes the worker whether it is waiting for PCM or for space in the track.
        }
    }

    private fun play(chunk: PcmPlaybackQueue.Chunk) {
        var offset = 0
        while (offset < chunk.bytes.size) {
            val written = synchronized(this) {
                val audioTrack = track ?: return
                if (muted || chunk.generation != generation) return
                // A flush can run between writes, including while the track is full.
                if (audioTrack.playState != AudioTrack.PLAYSTATE_PLAYING) audioTrack.play()
                audioTrack.write(chunk.bytes, offset, chunk.bytes.size - offset, AudioTrack.WRITE_NON_BLOCKING)
            }
            when {
                written < 0 -> {
                    AppLog.warn(LogCategory.AUDIO, "PCM playback write failed", "code=$written")
                    return
                }
                written == 0 -> Thread.sleep(4)
                else -> offset += written
            }
        }
    }

    private companion object {
        const val MAX_BUFFERED_SECONDS = 120
    }
}
