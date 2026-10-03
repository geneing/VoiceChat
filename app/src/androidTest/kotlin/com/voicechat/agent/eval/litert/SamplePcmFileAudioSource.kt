/*
 * Derived from Google AI Edge's LiteRT speech-recognition sample, pinned at
 * scripts/litert-sample-source.commit. Original sample source is Apache-2.0:
 * https://github.com/google-ai-edge/litert-samples/tree/main/samples/litert/speech_recognition
 *
 * Copyright 2026 Google LLC.
 * Licensed under the Apache License, Version 2.0.
 */
package com.voicechat.agent.eval.litert

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Reads the sample's RIFF/WAVE PCM16 windows and normalizes samples to [-1, 1]. */
internal class SamplePcmFileAudioSource(
    private val inputStream: InputStream,
    private val samplingRate: Int,
    private val interval: Duration,
    private val overlap: Duration,
) {
    init {
        require(interval > overlap) { "interval must be strictly longer than overlap" }
    }

    private val samplesPerInterval = sampleCount(interval)
    private val overlapSamples = sampleCount(overlap)
    private val step = samplesPerInterval - overlapSamples

    fun windows(): Sequence<FloatArray> =
        sequence {
            val pcm = locatePcmData(inputStream)
            val bytesPerWindow = samplesPerInterval * BYTES_PER_SAMPLE
            val bytesPerStep = step * BYTES_PER_SAMPLE
            val floats = FloatArray(samplesPerInterval)
            val bytes = ByteArray(bytesPerWindow)
            var first = true

            while (true) {
                val count = pcm.read(bytes, 0, if (first) bytesPerWindow else bytesPerStep)
                if (count <= 0) break

                val newSamples = count / BYTES_PER_SAMPLE
                val buffer = ByteBuffer.wrap(bytes, 0, count).order(ByteOrder.LITTLE_ENDIAN)
                val sampleStart = if (first) 0 else overlapSamples
                for (index in sampleStart until sampleStart + newSamples) {
                    floats[index] = buffer.short / PCM16_SCALE
                }
                for (index in sampleStart + newSamples until floats.size) {
                    floats[index] = 0.0f
                }
                first = false
                yield(floats.copyOf())
                System.arraycopy(floats, step, floats, 0, overlapSamples)
            }
        }

    private fun locatePcmData(input: InputStream): InputStream {
        val header = ByteArray(12)
        readFully(input, header)
        require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") {
            "Not a RIFF/WAVE file"
        }

        var foundPcmFormat = false
        while (true) {
            val chunkHeader = ByteArray(8)
            readFully(input, chunkHeader)
            val id = String(chunkHeader, 0, 4)
            val size = ByteBuffer.wrap(chunkHeader, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            require(size >= 0) { "Invalid WAV chunk size" }

            when (id) {
                "fmt " -> {
                    require(size >= 16) { "Invalid WAV format chunk" }
                    val format = ByteArray(size)
                    readFully(input, format)
                    val fields = ByteBuffer.wrap(format).order(ByteOrder.LITTLE_ENDIAN)
                    require(fields.short.toInt() == PCM_FORMAT) {
                        "Expected uncompressed PCM audio"
                    }
                    fields.position(2)
                    require(fields.short.toInt() == 1) { "Expected mono WAV input" }
                    fields.position(4)
                    require(fields.int == samplingRate) {
                        "Expected $samplingRate Hz WAV input"
                    }
                    fields.position(14)
                    require(fields.short.toInt() == BITS_PER_SAMPLE) {
                        "Expected 16-bit PCM WAV input"
                    }
                    foundPcmFormat = true
                }

                "data" -> {
                    require(foundPcmFormat) { "WAV data chunk appeared before format chunk" }
                    return input
                }

                else -> {
                    skipFully(input, size.toLong())
                }
            }
            if (size and 1 == 1) skipFully(input, 1)
        }
    }

    private fun sampleCount(duration: Duration) = (samplingRate * duration.toDouble(DurationUnit.SECONDS)).toInt()

    private fun readFully(
        input: InputStream,
        bytes: ByteArray,
    ) {
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            require(read > 0) { "Truncated WAV input" }
            offset += read
        }
    }

    private fun skipFully(
        input: InputStream,
        size: Long,
    ) {
        var remaining = size
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            require(skipped > 0) { "Truncated WAV input" }
            remaining -= skipped
        }
    }

    private companion object {
        const val PCM_FORMAT = 1
        const val BITS_PER_SAMPLE = 16
        const val BYTES_PER_SAMPLE = 2
        const val PCM16_SCALE = 32768.0f
    }
}
