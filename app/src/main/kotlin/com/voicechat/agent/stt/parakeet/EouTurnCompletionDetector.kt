package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Semantic end-of-turn detector that corroborates a VAD candidate pause with the
 * Parakeet model's confirmed `<EOU>` signal (stream 3 of
 * `docs/parakeet-eou-streaming.md`).
 *
 * **What it is not.** It is not a second classifier, and it never decodes audio.
 * It reads the already-produced [EndOfUtteranceSignal] published by
 * [ParakeetEouSpeechToText]; the model runs exactly once per chunk on the STT
 * path, so EOU adds no inference to the endpoint decision.
 *
 * **Decision.** At a candidate pause — one [evaluate] call per pause, never per
 * frame — it answers:
 *
 * - [TurnCompletion.COMPLETE] when a **confirmed** EOU was observed within
 *   [eouWindowMillis] of the pause;
 * - [TurnCompletion.INCOMPLETE] when the signal exists but no recent EOU is
 *   present (the bounded silence cap still ends the trailing turn);
 * - [TurnCompletion.UNAVAILABLE] when no signal was supplied at all, so the
 *   VAD-only policy applies.
 *
 * **Why corroboration, not the primary commit.** Host measurement on the ten
 * local clips recorded in `docs/parakeet-eou-streaming.md` shows the EOU head (with
 * its 1280 ms debounce) committed only **1 of 10** clips even though streaming
 * decode still beat full-utterance decode. EOU is therefore a *corroborator*: a
 * recent confirmation can end a turn early, but only the VAD/silence cap is the
 * guarantee that a trailing turn terminates.
 *
 * **Timing.** [evaluate] does not receive the pause's audio offset, so the
 * detector stamps each newly observed EOU with the injected [MonotonicClock] and
 * at [evaluate] compares it with the current monotonic time. That measures "an
 * EOU happened within N ms of this pause" directly and is robust to
 * capture/dispatch lag, which a raw audio-offset difference would not be. The
 * audio offset is still carried on [EouObservation] for diagnostics.
 *
 * **Replay safety.** A [kotlinx.coroutines.flow.StateFlow] replays its current
 * value to a new subscriber. A confirmation that already existed when the
 * detector subscribed must not be stamped as fresh, so the collector ignores
 * every emission whose [EouObservation.sequence] does not exceed the sequence
 * already present at subscription.
 *
 * @param signal the EOU signal to observe; `null` makes every evaluation
 *   [TurnCompletion.UNAVAILABLE].
 * @param eouWindowMillis how recent a confirmed EOU must be to complete the turn.
 * @param clock monotonic clock used to age a confirmation.
 */
class EouTurnCompletionDetector(
    private val signal: EndOfUtteranceSignal?,
    private val eouWindowMillis: Long = DEFAULT_EOU_WINDOW_MILLIS,
    private val clock: MonotonicClock = SystemMonotonicClock,
    collectorDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : TurnCompletionDetector {
    init {
        require(eouWindowMillis >= 0L) { "eouWindowMillis must not be negative" }
    }

    private val scope: CoroutineScope?

    /** Monotonic time of the most recently observed *new* EOU confirmation, or null. */
    @Volatile
    private var lastConfirmedAtNanos: Long? = null

    /** True once [close] has been called. */
    @Volatile
    private var closed: Boolean = false

    init {
        val events = signal?.eouEvents
        if (events == null) {
            scope = null
        } else {
            val initialSequence = events.value.sequence
            val collectorScope = CoroutineScope(Job() + collectorDispatcher)
            collectorScope.launch {
                events.collect { observation ->
                    // Ignore the replayed value and any duplicate read; only a
                    // sequence greater than the subscription baseline is new.
                    if (observation.sequence > initialSequence && !closed) {
                        lastConfirmedAtNanos = clock.nanoTime()
                    }
                }
            }
            scope = collectorScope
        }
    }

    override suspend fun evaluate(window: AudioFrame): TurnCompletion {
        if (closed) return TurnCompletion.UNAVAILABLE
        if (signal == null) return TurnCompletion.UNAVAILABLE
        val confirmedAtNanos = lastConfirmedAtNanos ?: return TurnCompletion.INCOMPLETE
        val ageMillis = (clock.nanoTime() - confirmedAtNanos) / 1_000_000L
        return if (ageMillis in 0..eouWindowMillis) {
            TurnCompletion.COMPLETE
        } else {
            TurnCompletion.INCOMPLETE
        }
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        scope?.cancel()
    }

    companion object {
        /**
         * Default corroboration window. The model's own debounce is 1280 ms, so a
         * confirmation is only trusted while it is still recent relative to the
         * pause; 2000 ms matches the default bounded silence cap and is provisional
         * until device measurement (R-0240).
         */
        const val DEFAULT_EOU_WINDOW_MILLIS: Long = 2_000L
    }
}
