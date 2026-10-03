package com.voicechat.agent.eval.litert

import java.io.File
import java.io.InputStream

internal data class ParakeetPcmWindow(
    val samples: FloatArray,
    val validSamples: Int,
)

/** Reads the first five seconds of the staged 16 kHz mono PCM16 WAV window. */
internal fun readParakeetPcmWindow(file: File): FloatArray = readParakeetPcmWindowWithLength(file).samples

/** Also preserves the source length so feature normalization can ignore zero-padded audio. */
internal fun readParakeetPcmWindowWithLength(file: File): ParakeetPcmWindow {
    val output = FloatArray(5 * 16_000)
    var filled = 0
    file.inputStream().use { input ->
        val header = ByteArray(12)
        readFully(input, header)
        while (filled < output.size) {
            val chunk = ByteArray(8)
            val read = input.read(chunk)
            if (read < chunk.size) break
            val id = String(chunk, 0, 4)
            val size =
                java.nio.ByteBuffer
                    .wrap(chunk, 4, 4)
                    .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                    .int
            if (id == "data") {
                val bytes = ByteArray(size)
                var offset = 0
                while (offset < size && filled < output.size) {
                    val count = input.read(bytes, offset, minOf(size - offset, output.size * 2))
                    if (count <= 0) break
                    offset += count
                }
                val buffer =
                    java.nio.ByteBuffer
                        .wrap(bytes, 0, offset)
                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                while (buffer.remaining() >= 2 && filled < output.size) output[filled++] = buffer.short / 32768f
                return ParakeetPcmWindow(output, filled)
            }
            input.skip(size.toLong())
        }
    }
    return ParakeetPcmWindow(output, filled)
}

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
