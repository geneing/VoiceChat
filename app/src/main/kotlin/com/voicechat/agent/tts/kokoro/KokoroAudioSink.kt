package com.voicechat.agent.tts.kokoro

/**
 * Where synthesized Kokoro audio is played.
 *
 * Kept apart from the engine so the engine's queueing, interruption, and
 * accounting are pure Kotlin and JVM-testable with a fake sink: the only
 * implementation that touches `android.media.AudioTrack` is
 * `AudioTrackKokoroSink`.
 *
 * **Contract.** [play] returns when [samples] have finished playing, or as soon
 * as [stop] has been called. It must call [onAudible] exactly once, as close as
 * the platform allows to the first frame reaching the output device, because
 * that callback is what the app reports as "first audible" — the number
 * `docs/voice-quality-and-latency.md` defines time-to-first-audio by. [play]
 * must not call [onAudible] when playback never became audible, so a stopped or
 * failed utterance cannot claim it started.
 */
interface KokoroAudioSink {
    /**
     * Plays 24 kHz mono [samples].
     *
     * @param onAudible invoked once when the first frame reaches the output.
     * @return `true` when every sample played; `false` when playback was stopped
     *   early (by [stop] or by the platform), which the engine reports as an
     *   interruption rather than a delivery.
     */
    suspend fun play(
        samples: FloatArray,
        onAudible: () -> Unit,
    ): Boolean

    /** Stops audible playback immediately; safe to call from another coroutine during barge-in. */
    suspend fun stop()

    /** Releases the platform audio resources. Idempotent. */
    fun close()
}
