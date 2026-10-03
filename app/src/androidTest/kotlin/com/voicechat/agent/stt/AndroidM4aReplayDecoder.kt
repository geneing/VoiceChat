package com.voicechat.agent.stt

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.media.AudioFormat as AndroidAudioFormat

/** Android platform decoder used by paced device replay tests; no media is packaged in the APK. */
internal object AndroidM4aReplayDecoder {
    data class PcmAudio(
        val samples: ShortArray,
    ) {
        val durationMillis: Long get() = (samples.size.toLong() * 1_000L) / SAMPLE_RATE_HZ
    }

    class DecodeException(
        val code: String,
    ) : Exception()

    fun decode(file: File): PcmAudio {
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(file.absolutePath)
            val audioTrack =
                (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                } ?: throw DecodeException("M4A_NO_AUDIO_TRACK")
            val sourceFormat = extractor.getTrackFormat(audioTrack)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: throw DecodeException("M4A_CODEC_UNAVAILABLE")
            extractor.selectTrack(audioTrack)
            val sourceDurationMillis =
                if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) {
                    (sourceFormat.getLong(MediaFormat.KEY_DURATION) / 1_000L).coerceAtLeast(0L)
                } else {
                    0L
                }
            val codec =
                try {
                    MediaCodec.createDecoderByType(mime)
                } catch (_: Exception) {
                    throw DecodeException("M4A_CODEC_UNAVAILABLE")
                }
            decoder = codec
            try {
                codec.configure(sourceFormat, null, null, 0)
                codec.start()
            } catch (_: Exception) {
                throw DecodeException("M4A_CODEC_UNAVAILABLE")
            }

            var outputSampleRate = sourceFormat.integerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: 0
            var outputChannels = sourceFormat.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: 0
            var outputPcmEncoding = AndroidAudioFormat.ENCODING_PCM_16BIT
            var inputEnded = false
            var outputEnded = false
            val output = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            val timeoutMillis =
                (sourceDurationMillis * 4L + DECODER_TIMEOUT_GRACE_MILLIS)
                    .coerceIn(MIN_DECODER_TIMEOUT_MILLIS, MAX_DECODER_TIMEOUT_MILLIS)
            val decodeStartedAt = SystemClock.elapsedRealtime()

            while (!outputEnded) {
                if (SystemClock.elapsedRealtime() - decodeStartedAt > timeoutMillis) {
                    throw DecodeException("M4A_DECODE_TIMEOUT")
                }
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(CODEC_BUFFER_TIMEOUT_MICROS)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: throw DecodeException("M4A_DECODE_FAILED")
                        inputBuffer.clear()
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            val presentationTime = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, presentationTime, 0)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, CODEC_BUFFER_TIMEOUT_MICROS)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        Unit
                    }

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputSampleRate = format.integerOrNull(MediaFormat.KEY_SAMPLE_RATE) ?: outputSampleRate
                        outputChannels = format.integerOrNull(MediaFormat.KEY_CHANNEL_COUNT) ?: outputChannels
                        outputPcmEncoding = format.integerOrNull(MediaFormat.KEY_PCM_ENCODING) ?: outputPcmEncoding
                        validateOutputFormat(outputSampleRate, outputChannels, outputPcmEncoding)
                    }

                    else -> {
                        if (outputIndex < 0) continue
                        if (info.size > 0) {
                            val buffer =
                                codec.getOutputBuffer(outputIndex)
                                    ?: throw DecodeException("M4A_DECODE_FAILED")
                            validateOutputFormat(outputSampleRate, outputChannels, outputPcmEncoding)
                            val bytesPerSample = bytesPerSample(outputPcmEncoding)
                            if (output.size().toLong() + info.size > MAX_DECODED_BYTES) {
                                throw DecodeException("M4A_DECODED_AUDIO_TOO_LARGE")
                            }
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val pcmBytes = ByteArray(info.size)
                            buffer.get(pcmBytes)
                            if (pcmBytes.size % (bytesPerSample * outputChannels) != 0) {
                                throw DecodeException("M4A_DECODED_FRAME_MISALIGNED")
                            }
                            output.write(pcmBytes)
                        }
                        outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                    }
                }
            }

            if (output.size() == 0) throw DecodeException("M4A_NO_DECODED_AUDIO")
            validateOutputFormat(outputSampleRate, outputChannels, outputPcmEncoding)
            val samples = resampleToMono16Khz(output.toByteArray(), outputSampleRate, outputChannels, outputPcmEncoding)
            if (samples.isEmpty()) throw DecodeException("M4A_NO_DECODED_AUDIO")
            return PcmAudio(samples)
        } catch (failure: DecodeException) {
            throw failure
        } catch (_: Exception) {
            throw DecodeException("M4A_DECODE_FAILED")
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun validateOutputFormat(
        sampleRate: Int,
        channels: Int,
        pcmEncoding: Int,
    ) {
        if (sampleRate <= 0 || channels !in 1..MAX_AUDIO_CHANNELS) {
            throw DecodeException("M4A_OUTPUT_FORMAT_UNSUPPORTED")
        }
        if (pcmEncoding !in SUPPORTED_PCM_ENCODINGS) {
            throw DecodeException("M4A_PCM_ENCODING_UNSUPPORTED")
        }
    }

    private fun resampleToMono16Khz(
        pcmBytes: ByteArray,
        inputSampleRateHz: Int,
        channelCount: Int,
        pcmEncoding: Int,
    ): ShortArray {
        val bytesPerSample = bytesPerSample(pcmEncoding)
        val inputFrameCount = pcmBytes.size / (bytesPerSample * channelCount)
        val outputSampleCount = (inputFrameCount.toLong() * SAMPLE_RATE_HZ / inputSampleRateHz).toInt()
        if (outputSampleCount.toLong() * 2L > MAX_DECODED_BYTES) throw DecodeException("M4A_DECODED_AUDIO_TOO_LARGE")
        val output = ShortArray(outputSampleCount)
        val buffer = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)

        fun monoSample(frame: Int): Double {
            var total = 0.0
            for (channel in 0 until channelCount) {
                val sampleIndex = frame * channelCount + channel
                val sample =
                    if (pcmEncoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) {
                        buffer.getFloat(sampleIndex * 4).toDouble().coerceIn(-1.0, 1.0) * Short.MAX_VALUE
                    } else {
                        buffer.getShort(sampleIndex * 2).toDouble()
                    }
                total += sample
            }
            return total / channelCount
        }

        for (index in output.indices) {
            val sourcePosition = index.toDouble() * inputSampleRateHz / SAMPLE_RATE_HZ
            val left = sourcePosition.toInt().coerceAtMost(inputFrameCount - 1)
            val right = (left + 1).coerceAtMost(inputFrameCount - 1)
            val fraction = sourcePosition - left
            output[index] =
                (monoSample(left) * (1.0 - fraction) + monoSample(right) * fraction)
                    .toInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
        }
        return output
    }

    private fun bytesPerSample(pcmEncoding: Int): Int = if (pcmEncoding == AndroidAudioFormat.ENCODING_PCM_FLOAT) 4 else 2

    private fun MediaFormat.integerOrNull(key: String): Int? = if (containsKey(key)) getInteger(key) else null

    private const val SAMPLE_RATE_HZ = 16_000
    private const val CODEC_BUFFER_TIMEOUT_MICROS = 10_000L
    private const val DECODER_TIMEOUT_GRACE_MILLIS = 10_000L
    private const val MIN_DECODER_TIMEOUT_MILLIS = 30_000L
    private const val MAX_DECODER_TIMEOUT_MILLIS = 120_000L
    private const val MAX_DECODED_BYTES = 64 * 1024 * 1024
    private const val MAX_AUDIO_CHANNELS = 8
    private val SUPPORTED_PCM_ENCODINGS =
        setOf(AndroidAudioFormat.ENCODING_PCM_16BIT, AndroidAudioFormat.ENCODING_PCM_FLOAT)
}
