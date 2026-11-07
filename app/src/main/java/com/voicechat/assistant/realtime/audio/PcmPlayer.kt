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
 * Streaming 24 kHz PCM output with instant flush for barge-in and optional
 * pitch-preserving time-stretching for speech-rate control. Writing happens on
 * a dedicated thread; [clear] drops everything queued and flushes the track.
 *
 * The rate is applied with [TimeStretcher] on our own PCM, not with
 * `AudioTrack.playbackParams`: the target device rejects a speed change with
 * `IllegalArgumentException: arguments out of range` for every AudioTrack
 * configuration (verified on the Pixel 10).
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

    private val stretchLock = Any()
    private var stretcher: TimeStretcher? = null
    private var speed = 1f

    @Volatile
    private var preferredDevice: AudioDeviceInfo? = null

    @Volatile
    private var stretchLogged = false

    fun start() {
        synchronized(this) {
            if (running) return
            running = true
            track = buildTrack()
            applyPreferredDevice(track)
            track?.play()
            thread = Thread({ loop() }, "pcm-player").also { it.start() }
            AppLog.info(LogCategory.AUDIO, "PCM playback started", "speed=${speed}x")
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
        resetStretcher()
        stretchLogged = false
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
            resetStretcher()
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
     * Set the playback speed multiplier. Applied by stretching our own PCM with
     * [TimeStretcher] so the pitch is preserved; safe to call while a turn is
     * playing.
     */
    fun setSpeed(value: Float) {
        val target = value.coerceIn(MIN_SPEED, MAX_SPEED)
            .let { if (kotlin.math.abs(it - 1f) < 0.01f) 1f else it }
        synchronized(stretchLock) {
            if (target == speed) return
            speed = target
            stretcher = if (target == 1f) null else TimeStretcher(sampleRate, target)
        }
        AppLog.info(LogCategory.AUDIO, "Playback speed set", "${target}x")
    }

    private fun resetStretcher() {
        synchronized(stretchLock) { stretcher?.reset() }
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
            if (muted) continue
            val pcm = synchronized(stretchLock) { stretcher?.process(chunk) ?: chunk }
            if (pcm.isEmpty()) continue
            if (stretcher != null && !stretchLogged) {
                stretchLogged = true
                AppLog.info(
                    LogCategory.AUDIO,
                    "Time stretch engaged",
                    "${speed}x in=${chunk.size}B out=${pcm.size}B",
                )
            }
            runCatching {
                var offset = 0
                val audioTrack = track ?: return@runCatching
                while (offset < pcm.size && running) {
                    val written = audioTrack.write(pcm, offset, pcm.size - offset)
                    if (written <= 0) break
                    offset += written
                }
            }.onFailure {
                AppLog.warn(LogCategory.AUDIO, "Playback write failed: ${it.message}")
            }
        }
    }

    companion object {
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 2.5f
    }
}
