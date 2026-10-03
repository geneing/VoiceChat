package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock

/**
 * Builds the Parakeet EOU [TurnCompletionDetector] for the voice assembly, so
 * "Parakeet STT is selected" can be translated into the optional semantic
 * detector the M09 endpoint policy already accepts.
 *
 * **How it plugs in (one line, no policy change).** The assembly already builds
 * the turn detector through
 * `PolicyVoiceTurnDetector.forRoute(semanticDetector = …)`, whose signature is
 * `TurnCompletionDetector?`. When Parakeet-EOU is the selected engine, pass the
 * detector this factory returns:
 *
 * ```
 * val stt: ParakeetEouSpeechToText = …
 * val semantic = ParakeetEouTurnDetectorFactory.forEngine(stt)
 * val turnDetector = PolicyVoiceTurnDetector.forRoute(
 *     diagnostics = diagnostics,
 *     clock = clock,
 *     semanticDetector = semantic,   // <- the only change
 * )
 * ```
 *
 * The M10 Smart Turn detector and this one are interchangeable at that seam:
 * both are `TurnCompletionDetector`s evaluated once per candidate pause. They are
 * not meant to be combined — a route selects exactly one semantic corroborator.
 *
 * **Nothing changes on the onset/barge-in path.** [BoundedTurnEndpointPolicy]
 * emits `SPEECH_STARTED`/`CANDIDATE_PAUSE`/`SPEECH_RESUMED` unconditionally and
 * only calls the semantic detector at a candidate pause, so passing this detector
 * leaves barge-in latency identical to the VAD-only case.
 *
 * **Lifecycle.** The returned detector owns a collector on the signal; close it
 * with the session (the assembly already closes the semantic detector in its
 * `finally`).
 *
 * This type is pure Kotlin (`android.*`-free); the app boundary is responsible
 * for constructing [ParakeetEouSpeechToText] and passing its
 * [ParakeetEouSpeechToText.endOfUtterance] signal.
 */
object ParakeetEouTurnDetectorFactory {
    /**
     * Builds an EOU detector over [engine]'s signal.
     *
     * @param engine the selected Parakeet STT; its
     *   [ParakeetEouSpeechToText.endOfUtterance] signal is read directly, so EOU
     *   costs no second decode.
     * @param eouWindowMillis corroboration window (see
     *   [EouTurnCompletionDetector.DEFAULT_EOU_WINDOW_MILLIS]).
     */
    fun forEngine(
        engine: ParakeetEouSpeechToText,
        eouWindowMillis: Long = EouTurnCompletionDetector.DEFAULT_EOU_WINDOW_MILLIS,
        clock: MonotonicClock = SystemMonotonicClock,
    ): TurnCompletionDetector = EouTurnCompletionDetector(engine.endOfUtterance, eouWindowMillis, clock)

    /**
     * Builds an EOU detector over any [EndOfUtteranceSignal], for callers that
     * hold the signal rather than the adapter, or for tests. A `null` signal
     * yields a detector that always reports
     * [com.voicechat.agent.contracts.TurnCompletion.UNAVAILABLE], i.e. the
     * VAD-only policy.
     */
    fun forSignal(
        signal: EndOfUtteranceSignal?,
        eouWindowMillis: Long = EouTurnCompletionDetector.DEFAULT_EOU_WINDOW_MILLIS,
        clock: MonotonicClock = SystemMonotonicClock,
    ): TurnCompletionDetector = EouTurnCompletionDetector(signal, eouWindowMillis, clock)
}
