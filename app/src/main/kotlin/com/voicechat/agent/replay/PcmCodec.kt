package com.voicechat.agent.replay

import java.security.MessageDigest

/**
 * Conversion between raw 16-bit signed little-endian PCM bytes and [ShortArray]
 * samples, plus a content hash for fixture integrity.
 *
 * The STT custom-audio contract accepts raw headerless 16-bit mono PCM at
 * 16 kHz delivered in real time (see `docs/decisions.md` §2.1). Fixtures are
 * stored in exactly that shape, so replay needs no container or codec and a
 * committed fixture is byte-comparable with what the pipeline would capture.
 */
object PcmCodec {
    fun encode(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val value = sample.toInt()
            bytes[index * 2] = (value and 0xFF).toByte()
            bytes[index * 2 + 1] = ((value ushr 8) and 0xFF).toByte()
        }
        return bytes
    }

    fun decode(bytes: ByteArray): ShortArray {
        require(bytes.size % 2 == 0) { "PCM byte count must be even, was ${bytes.size}" }
        val samples = ShortArray(bytes.size / 2)
        for (index in samples.indices) {
            val low = bytes[index * 2].toInt() and 0xFF
            val high = bytes[index * 2 + 1].toInt()
            samples[index] = ((high shl 8) or low).toShort()
        }
        return samples
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xFF) }

    fun sha256Hex(samples: ShortArray): String = sha256Hex(encode(samples))
}
