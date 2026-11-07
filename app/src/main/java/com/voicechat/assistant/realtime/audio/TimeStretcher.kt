package com.voicechat.assistant.realtime.audio

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Pitch-preserving time stretcher for 16-bit mono PCM, based on WSOLA
 * (waveform-similarity overlap-add).
 *
 * `AudioTrack.setPlaybackParams` is rejected on the target device with
 * `IllegalArgumentException: arguments out of range` for every usage, sample
 * rate and performance mode, so the speech rate is applied to the PCM stream
 * before it reaches the track instead of asking the platform resampler.
 *
 * The input is processed as a stream: [process] may be called with arbitrary
 * chunk sizes and returns whatever output is ready. Up to one analysis window
 * (~30 ms) of the stream tail is not emitted because the similarity search
 * needs look-ahead; that is below the audible threshold of an answer.
 */
class TimeStretcher(
    private val sampleRate: Int = 24_000,
    speed: Float = 1f,
) {

    private val speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)
    private val frame = sampleRate / 50 // 20 ms analysis window
    private val hop = frame / 2 // 50 % overlap keeps the window sum at 1.0
    private val analysisHop = hop * this.speed
    private val search = sampleRate / 100 // ±10 ms similarity search
    private val window = FloatArray(frame) { index ->
        (0.5 - 0.5 * cos(2.0 * PI * index / frame)).toFloat()
    }

    private var samples = ShortArray(sampleRate * 2)
    private var count = 0
    private var base = 0
    private var position = 0.0
    private var previousStart = -1
    private var pending = FloatArray(hop)

    /** True when the stretcher does nothing and audio can pass straight through. */
    val passthrough: Boolean get() = speed == 1f

    fun process(pcm: ByteArray): ByteArray {
        if (passthrough) return pcm
        if (pcm.size < 2) return EMPTY
        append(pcm)
        val out = java.io.ByteArrayOutputStream(pcm.size)
        produce(out)
        return out.toByteArray()
    }

    /** Drops buffered audio, e.g. on barge-in, so the next turn starts clean. */
    fun reset() {
        count = 0
        base = 0
        position = 0.0
        previousStart = -1
        pending = FloatArray(hop)
    }

    private fun append(pcm: ByteArray) {
        val incoming = pcm.size / 2
        if (count + incoming > samples.size) {
            val grown = maxOf(samples.size * 2, count + incoming)
            samples = samples.copyOf(grown)
        }
        var index = 0
        var target = count
        while (index + 1 < pcm.size) {
            val low = pcm[index].toInt() and 0xFF
            val high = pcm[index + 1].toInt() shl 8
            samples[target++] = (high or low).toShort()
            index += 2
        }
        count = target
    }

    private fun produce(out: java.io.ByteArrayOutputStream) {
        while (true) {
            val start = position.roundToInt()
            if (base + count < start + search + frame) return
            val chosen = chooseStart(start)
            emit(chosen, out)
            previousStart = chosen
            position += analysisHop
            compact(
                start - search - frame - analysisHop.toInt(),
            )
        }
    }

    /** Picks the candidate that best continues the previous frame (WSOLA). */
    private fun chooseStart(start: Int): Int {
        val previous = previousStart
        if (previous < 0) return start
        val template = previous + hop
        if (template + frame > base + count) return start
        var bestDelta = 0
        var bestScore = Long.MIN_VALUE
        var delta = -search
        while (delta <= search) {
            val score = correlation(start + delta, template, COARSE_STRIDE)
            if (score > bestScore) {
                bestScore = score
                bestDelta = delta
            }
            delta += COARSE_STEP
        }
        var fine = (bestDelta - COARSE_STEP + 1).coerceAtLeast(-search)
        val fineEnd = (bestDelta + COARSE_STEP - 1).coerceAtMost(search)
        while (fine <= fineEnd) {
            val score = correlation(start + fine, template, 1)
            if (score > bestScore) {
                bestScore = score
                bestDelta = fine
            }
            fine++
        }
        return start + bestDelta
    }

    private fun correlation(first: Int, second: Int, stride: Int): Long {
        var sum = 0L
        var index = 0
        while (index < frame) {
            sum += sampleAt(first + index).toLong() * sampleAt(second + index).toLong()
            index += stride
        }
        return sum
    }

    private fun emit(chosen: Int, out: java.io.ByteArrayOutputStream) {
        val first = chosen - base
        val next = FloatArray(hop)
        for (index in 0 until hop) {
            val mixed = pending[index] + window[index] * samples[first + index]
            val value = mixed.roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out.write(value and 0xFF)
            out.write((value shr 8) and 0xFF)
            next[index] = window[index + hop] * samples[first + index + hop]
        }
        pending = next
    }

    private fun compact(keepFrom: Int) {
        val drop = keepFrom - base
        if (drop <= 0) return
        if (drop >= count) {
            base += count
            count = 0
            return
        }
        System.arraycopy(samples, drop, samples, 0, count - drop)
        count -= drop
        base += drop
    }

    private fun sampleAt(absolute: Int): Short {
        val relative = absolute - base
        return if (relative in 0 until count) samples[relative] else 0
    }

    companion object {
        private const val MIN_SPEED = 0.5f
        private const val MAX_SPEED = 2.5f
        private const val COARSE_STEP = 4
        private const val COARSE_STRIDE = 4
        private val EMPTY = ByteArray(0)
    }
}
