package com.voicechat.agent.voice

import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.parakeet.EndOfUtteranceSignal

/**
 * The Parakeet-EOU adapter pair for one voice session (stream 3 ↔ stream 4).
 *
 * Parakeet-EOU streams recognized text and a model-internal EOU signal. The
 * independent VAD endpoint remains responsible for force-finalizing pending
 * text, then the adapter reuses its decoder caches for the next utterance.
 */
interface ParakeetEouSession : AutoCloseable {
    /** The streaming recognizer to run in the voice loop. */
    val speechToText: SpeechToText

    /** Model EOU events on this streaming session, when the engine exposes them. */
    val endOfUtterance: EndOfUtteranceSignal? get() = null
}

/**
 * Builds a [ParakeetEouSession] from a verified model set (stream 4 seam).
 *
 * The production implementation loads the verified ONNX graphs and creates one
 * streaming recognizer for the selected engine. The unavailable default reports
 * a typed `MODEL_UNAVAILABLE` rather than fabricating a session.
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
