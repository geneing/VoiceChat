package com.voicechat.agent.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioRecord
import android.media.MediaRecorder
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import android.media.AudioFormat as AndroidAudioFormat

/**
 * [PcmRecorderFactory] backed by platform `AudioRecord`.
 *
 * Capture uses [MediaRecorder.AudioSource.VOICE_RECOGNITION] (the source
 * `docs/decisions.md` §2.1 expects for on-device recognition). No software gain,
 * AGC, or noise suppressor is applied here: the platform's own processing flags
 * are left at their defaults until Pixel 10 measurements justify otherwise
 * (`docs/voice-quality-and-latency.md`).
 *
 * The default format is the recognizer-expected 16 kHz mono 16-bit PCM. If the
 * device cannot provide it, [create] throws
 * [ErrorCode.AUDIO_DEVICE_UNAVAILABLE] rather than silently recording at another
 * rate.
 *
 * [create] re-checks `RECORD_AUDIO` directly, in addition to the capture loop's
 * check: opening an `AudioRecord` without the permission would crash, so the
 * device boundary refuses explicitly with
 * [ErrorCode.AUDIO_PERMISSION_DENIED].
 */
class AndroidPcmRecorderFactory(
    private val context: Context,
    private val format: AudioFormat = AudioFormat.MONO_16_KHZ,
    private val audioSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION,
    private val frameSizeSamples: Int = MicrophoneCaptureConfig.DEFAULT_FRAME_SIZE_SAMPLES,
    private val minBufferSizeProvider: (Int, Int, Int) -> Int = { sampleRate, channelMask, encoding ->
        AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
    },
    private val recordFactory: ((Int, Int, Int, Int, Int) -> AudioRecord)? = null,
) : PcmRecorderFactory {
    override fun create(): PcmRecorderEngine {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            AppLog.w { "capture: RECORD_AUDIO not granted at recorder create" }
            throw VoiceAgentException(VoiceAgentError(ErrorCode.AUDIO_PERMISSION_DENIED, "RECORD_AUDIO is not granted"))
        }
        val channelMask = inputChannelMask(format.channelCount)
        val bufferSizeBytes =
            negotiateBufferSizeBytes(
                sampleRateHz = format.sampleRateHz,
                channelMask = channelMask,
                frameSizeSamples = frameSizeSamples,
                minBufferSizeProvider = minBufferSizeProvider,
            )
        AppLog.d {
            "capture: negotiating AudioRecord source=${audioSourceName(audioSource)} " +
                "format=${format.sampleRateHz}Hz/${format.channelCount}ch bufferBytes=$bufferSizeBytes"
        }
        val record =
            try {
                recordFactory?.invoke(audioSource, format.sampleRateHz, channelMask, ENCODING_PCM_16_BIT, bufferSizeBytes)
                    ?: AudioRecord(audioSource, format.sampleRateHz, channelMask, ENCODING_PCM_16_BIT, bufferSizeBytes)
            } catch (e: IllegalArgumentException) {
                throw VoiceAgentException(VoiceAgentError(ErrorCode.AUDIO_CAPTURE_FAILED, "AudioRecord rejected the 16 kHz mono format"), e)
            } catch (e: UnsupportedOperationException) {
                throw VoiceAgentException(
                    VoiceAgentError(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, "AudioRecord is unavailable on this device"),
                    e,
                )
            }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { record.release() }
            throw VoiceAgentException(
                VoiceAgentError(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, "AudioRecord did not initialize for 16 kHz mono PCM"),
            )
        }
        return AudioRecordEngine(record, format)
    }

    /** Human-readable source name used only in capture diagnostics. */
    fun audioSourceLabel(): String = audioSourceName(audioSource)

    private companion object {
        const val ENCODING_PCM_16_BIT = AndroidAudioFormat.ENCODING_PCM_16BIT
        const val BYTES_PER_SAMPLE = 2
        const val BUFFER_FRAME_FACTOR = 4
    }
}

/**
 * Validates that the platform can supply 16 kHz mono 16-bit PCM and returns a
 * buffer size in bytes.
 *
 * Exposed (internal) so the negotiation can be tested on the JVM with a fake
 * minimum-buffer provider; [AndroidPcmRecorderFactory] passes
 * `AudioRecord.getMinBufferSize`.
 */
internal fun negotiateBufferSizeBytes(
    sampleRateHz: Int,
    channelMask: Int,
    frameSizeSamples: Int,
    minBufferSizeProvider: (Int, Int, Int) -> Int,
): Int {
    val minBytes = minBufferSizeProvider(sampleRateHz, channelMask, AndroidAudioFormat.ENCODING_PCM_16BIT)
    if (minBytes <= 0) {
        throw VoiceAgentException(
            VoiceAgentError(
                ErrorCode.AUDIO_DEVICE_UNAVAILABLE,
                "16 kHz mono 16-bit PCM is not supported (getMinBufferSize=$minBytes)",
            ),
        )
    }
    val frameBytes = frameSizeSamples * 2
    return maxOf(minBytes, frameBytes * 4)
}

/** Maps a channel count to the platform input channel mask, or fails typed. */
internal fun inputChannelMask(channelCount: Int): Int =
    when (channelCount) {
        1 -> {
            AndroidAudioFormat.CHANNEL_IN_MONO
        }

        else -> {
            throw VoiceAgentException(
                VoiceAgentError(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, "capture is mono only, not $channelCount channels"),
            )
        }
    }

/** Privacy-safe label for an `AudioRecord` audio source. */
internal fun audioSourceName(audioSource: Int): String =
    when (audioSource) {
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> "SOURCE_$audioSource"
    }

/**
 * [PcmRecorderEngine] over one `AudioRecord`.
 *
 * [stop] and [release] are synchronized because shutdown can race an in-flight
 * [read] running on the capture dispatcher.
 */
private class AudioRecordEngine(
    private val record: AudioRecord,
    override val format: AudioFormat,
) : PcmRecorderEngine {
    private var released = false

    override fun start() {
        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            throw VoiceAgentException(VoiceAgentError(ErrorCode.AUDIO_CAPTURE_FAILED, "AudioRecord.startRecording failed"), e)
        }
        if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            throw VoiceAgentException(VoiceAgentError(ErrorCode.AUDIO_CAPTURE_FAILED, "AudioRecord did not enter the recording state"))
        }
    }

    override fun read(
        buffer: ShortArray,
        offset: Int,
        size: Int,
    ): Int = record.read(buffer, offset, size)

    @Synchronized
    override fun stop() {
        if (released) return
        runCatching {
            if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
        }
    }

    @Synchronized
    override fun release() {
        if (released) return
        released = true
        runCatching { record.release() }
    }
}
