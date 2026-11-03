package com.voicechat.assistant.realtime.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Streaming 24 kHz PCM output with instant flush for barge-in. Writing happens
 * on a dedicated thread; [clear] drops everything queued and flushes the track.
 */
class PcmPlayer(private val sampleRate: Int = 24_000) {

    private val queue = LinkedBlockingQueue<ByteArray>(96)
    private var thread: Thread? = null
    private var track: AudioTrack? = null

    @Volatile
    private var running = false

    @Volatile
    private var muted = false

    fun start() {
        synchronized(this) {
            if (running) return
            running = true
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
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
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()
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

    private fun loop() {
        while (running) {
            val chunk = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
            if (muted) continue
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
