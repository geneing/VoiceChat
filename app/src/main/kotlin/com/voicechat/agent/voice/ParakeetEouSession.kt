package com.voicechat.agent.voice

import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.stt.SttEngine

/**
 * The Parakeet-EOU adapter pair for one voice session (stream 3 ↔ stream 4).
 *
 * Parakeet-EOU is a streaming recognizer whose EOU head is the natural semantic
 * end-of-turn corroborator (`docs/parakeet-eou-streaming.md`). One adapter
 * instance therefore owns both the [SpeechToText] surface and the optional
 * [turnDetector] built from the same decode session, and is closed once per
 * session.
 */
interface ParakeetEouSession : AutoCloseable {
    /** The streaming recognizer to run in the voice loop. */
    val speechToText: SpeechToText

    /**
     * The EOU-backed semantic completion detector, or `null` when the EOU signal
     * is not available. `null` keeps the bounded VAD-only endpoint; it is never
     * fabricated.
     */
    val turnDetector: TurnCompletionDetector?
}

/**
 * Builds a [ParakeetEouSession] from a verified model set (stream 4 seam).
 *
 * **Cross-stream dependency.** The production implementation is supplied by
 * stream 3 (`ParakeetEouSpeechToText` + `EouTurnCompletionDetector`). Stream 4
 * resolves the *selection* and owns this seam so the voice loop, settings, and
 * resolution can compile and be tested before the adapter lands; until stream 3
 * provides the concrete factory the app wires
 * [UnavailableParakeetEouSessionFactory], which reports a typed
 * `MODEL_UNAVAILABLE` rather than fabricating a session.
 *
 * Implementations must open the ONNX graphs off the main thread and must throw a
 * typed failure (never return a half-initialized session) when a graph cannot be
 * loaded.
 */
fun interface ParakeetEouSessionFactory {
    /**
     * @param engine the resolved Parakeet-EOU engine (its locale is the session
     *   language; its model id names the pinned set).
     * @param directory the verified app-private directory holding the five files.
     */
    fun create(
        engine: SttEngine,
        directory: java.io.File,
    ): ParakeetEouSession
}

/**
 * The honest default while stream 3's adapter is not wired: a typed failure.
 *
 * It exists so a Parakeet-EOU selection is reported as unavailable instead of
 * the app constructing a session it cannot honor. The voice assembly catches the
 * failure and reports `MODEL_UNAVAILABLE`.
 */
object UnavailableParakeetEouSessionFactory : ParakeetEouSessionFactory {
    override fun create(
        engine: SttEngine,
        directory: java.io.File,
    ): ParakeetEouSession =
        throw UnsupportedOperationException(
            "the Parakeet-EOU adapter is not wired in this build",
        )
}
