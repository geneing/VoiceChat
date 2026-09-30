package com.voicechat.agent.vad

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame

/**
 * A bounded rolling window of the most recent audio frames.
 *
 * The M02 `TurnCompletionDetector.evaluate(window: AudioFrame)` takes one
 * frame-sized window. Smart Turn v3.2 (M10) evaluates the preceding several
 * seconds, so at a candidate pause the endpoint policy hands the detector the
 * concatenation of the last [windowMillis] of audio. The window is capped by
 * duration, so memory stays bounded however long a session runs, and it retains
 * only samples already delivered on the audio path.
 */
internal class RecentAudioWindow(
    private val windowMillis: Long,
) {
    private val chunks = ArrayDeque<ShortArray>()
    private var sampleCount = 0
    private var sampleRateHz = 0
    private var maxSamples = 0

    /** Appends one frame, evicting the oldest audio past the window duration. */
    fun add(frame: AudioFrame) {
        if (windowMillis <= 0L || frame.samples.isEmpty()) return
        if (sampleRateHz == 0) {
            sampleRateHz = frame.format.sampleRateHz
            maxSamples = (windowMillis * sampleRateHz / 1000L).toInt()
        }
        chunks.addLast(frame.samples)
        sampleCount += frame.samples.size
        while (sampleCount > maxSamples && chunks.size > 1) {
            sampleCount -= chunks.removeFirst().size
        }
    }

    /** The retained audio as one frame, or `null` when nothing is retained. */
    fun snapshot(): AudioFrame? {
        if (windowMillis <= 0L || sampleCount == 0 || sampleRateHz == 0) return null
        val samples = ShortArray(sampleCount)
        var offset = 0
        for (chunk in chunks) {
            chunk.copyInto(samples, offset)
            offset += chunk.size
        }
        return AudioFrame(AudioFormat(sampleRateHz = sampleRateHz, channelCount = 1), samples)
    }

    /** Retained sample count, for tests. */
    val retainedSamples: Int get() = sampleCount
}
