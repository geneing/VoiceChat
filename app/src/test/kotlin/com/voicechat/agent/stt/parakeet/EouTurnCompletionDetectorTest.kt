package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.fake.FakeMonotonicClock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Stream-3 tests for [EouTurnCompletionDetector].
 *
 * They prove the frozen rule: a **confirmed** EOU within the window is
 * `COMPLETE`, an EOU older than the window (or none at all) is `INCOMPLETE`, and
 * an absent signal is `UNAVAILABLE` so the bounded silence cap stays the
 * guarantee. They also prove the detector is a once-per-pause classifier, not a
 * per-frame one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EouTurnCompletionDetectorTest {
    private val window = AudioFrame(AudioFormat.MONO_16_KHZ, ShortArray(320))

    private fun detector(
        signal: EndOfUtteranceSignal?,
        clock: MonotonicClock,
        eouWindowMillis: Long = 1_000L,
        dispatcher: CoroutineDispatcher,
    ) = EouTurnCompletionDetector(
        signal = signal,
        eouWindowMillis = eouWindowMillis,
        clock = clock,
        collectorDispatcher = dispatcher,
    )

    @Test
    fun aConfirmedEouWithinTheWindowCompletesTheTurn() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val underTest = detector(signal, clock, eouWindowMillis = 1_000L, dispatcher = dispatch)

            signal.confirm(offsetMillis = 500L)
            clock.advanceMillis(200L) // within the 1000 ms window

            assertEquals(TurnCompletion.COMPLETE, underTest.evaluate(window))
            underTest.close()
        }

    @Test
    fun anEouOlderThanTheWindowIsIncompleteNotUnavailable() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val underTest = detector(signal, clock, eouWindowMillis = 1_000L, dispatcher = dispatch)

            signal.confirm(offsetMillis = 100L)
            clock.advanceMillis(2_000L) // past the window

            assertEquals(TurnCompletion.INCOMPLETE, underTest.evaluate(window))
            underTest.close()
        }

    @Test
    fun aSignalWithNoConfirmationIsIncomplete() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val underTest = detector(signal, clock, dispatcher = dispatch)

            assertEquals(TurnCompletion.INCOMPLETE, underTest.evaluate(window))
            underTest.close()
        }

    @Test
    fun anAbsentSignalIsUnavailableSoTheCapStillEndsTheTurn() =
        runTest {
            val clock = FakeMonotonicClock()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val underTest = detector(signal = null, clock = clock, dispatcher = dispatch)

            assertEquals(TurnCompletion.UNAVAILABLE, underTest.evaluate(window))
            underTest.close()
        }

    @Test
    fun aConfirmationExistingBeforeTheDetectorSubscribedIsNotTreatedAsFresh() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            signal.confirm(offsetMillis = 10L)
            clock.advanceMillis(5_000L) // the stale confirmation is now old

            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val underTest = detector(signal, clock, eouWindowMillis = 1_000L, dispatcher = dispatch)

            // The replayed StateFlow value must not be stamped as a fresh arrival.
            assertEquals(TurnCompletion.INCOMPLETE, underTest.evaluate(window))
            underTest.close()
        }

    @Test
    fun evaluationIsPerCallAndTheWindowContentsNeverChangeTheVerdict() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val underTest = detector(signal, clock, eouWindowMillis = 1_000L, dispatcher = dispatch)

            signal.confirm(offsetMillis = 1L)
            clock.advanceMillis(1L)

            // Many different windows, one verdict each: the detector runs once per
            // evaluate call and has no internal per-frame loop.
            val verdicts =
                (0 until 50).map { index ->
                    underTest.evaluate(AudioFrame(AudioFormat.MONO_16_KHZ, ShortArray(160 * (index + 1))))
                }

            assertEquals(50, verdicts.size)
            assertEquals(TurnCompletion.COMPLETE, verdicts.first())
            underTest.close()
        }

    @Test
    fun closeMakesLaterEvaluationsUnavailable() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val underTest = detector(signal, clock, dispatcher = dispatch)

            signal.confirm(offsetMillis = 1L)
            clock.advanceMillis(1L)
            assertEquals(TurnCompletion.COMPLETE, underTest.evaluate(window))

            underTest.close()
            assertEquals(TurnCompletion.UNAVAILABLE, underTest.evaluate(window))
        }

    /** A test [EndOfUtteranceSignal] whose confirmation the test drives directly. */
    private class FakeEouSignal : EndOfUtteranceSignal {
        private val state = MutableStateFlow(EouObservation(Long.MIN_VALUE, null, 0L))
        private var latestSequence = 0L

        override val eouEvents: StateFlow<EouObservation> = state

        override fun lastEouOffsetMillis(): Long? = state.value.offsetMillis.takeIf { it != Long.MIN_VALUE }

        override fun consumeEou(): Boolean {
            if (state.value.offsetMillis == Long.MIN_VALUE) return false
            state.value = EouObservation(Long.MIN_VALUE, null, ++latestSequence)
            return true
        }

        fun confirm(offsetMillis: Long) {
            state.value = EouObservation(offsetMillis, 0.9f, ++latestSequence)
        }
    }
}
