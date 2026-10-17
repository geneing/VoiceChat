package com.voicechat.agent.voice

import com.voicechat.agent.domain.AudioFrame

/**
 * A rolling buffer of the most recent capture frames.
 *
 * Voice activity is only declared a few frames **after** speech actually starts,
 * and the coordinator feeds a recognition turn from that onset — so the audio
 * captured just before the onset decision (the first word or two, especially when
 * the opening syllable is quieter than the onset threshold) would otherwise never
 * reach the recognizer and the transcript would begin mid-sentence.
 *
 * The buffer keeps the last [capacity] frames so a new turn can be handed them
 * immediately before the live audio continues. It is deliberately small and
 * bounded: it holds raw frames only for the fraction of a second it takes the VAD
 * to fire, never the whole utterance.
 *
 * Thread-safety: [record] runs on the capture coroutine and [clear]/[snapshot] may
 * run on the detection coroutine, so every access is guarded. No call blocks on
 * I/O, and the snapshot copies only references, so the guard is never held across
 * a suspension point.
 */
internal class CapturePreRoll(
    /** Maximum number of frames retained. At 20 ms/frame, 50 ≈ 1.0 s. */
    private val capacity: Int,
) {
    init {
        require(capacity > 0) { "capacity must be positive, was $capacity" }
    }

    private val frames = ArrayDeque<AudioFrame>()
    private val lock = Any()

    /** Appends [frame], dropping the oldest once [capacity] is exceeded. */
    fun record(frame: AudioFrame) =
        synchronized(lock) {
            frames.addLast(frame)
            while (frames.size > capacity) frames.removeFirst()
        }

    /** A snapshot of the buffered frames, oldest first. */
    fun snapshot(): List<AudioFrame> = synchronized(lock) { frames.toList() }

    /** The number of buffered frames. */
    val size: Int get() = synchronized(lock) { frames.size }

    /** Drops every buffered frame, for example when a turn ends. */
    fun clear() = synchronized(lock) { frames.clear() }
}
