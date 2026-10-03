package com.voicechat.agent.vad

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.replay.ReplayAudioInput
import com.voicechat.agent.stt.parakeet.EndOfUtteranceSignal
import com.voicechat.agent.stt.parakeet.EouObservation
import com.voicechat.agent.stt.parakeet.EouTurnCompletionDetector
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream-3 integration tests: the Parakeet EOU corroborator plugged into the
 * **unchanged** M09 [BoundedTurnEndpointPolicy].
 *
 * They prove the endpoints and holds behave exactly as the frozen contract
 * requires, and that the onset/barge-in path is untouched by the EOU detector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ParakeetEouPolicyIntegrationTest {
    private val config = VadConfig.default()

    private fun detector(
        signal: EndOfUtteranceSignal?,
        clock: FakeMonotonicClock,
        windowMillis: Long = 1_000L,
        dispatcher: CoroutineDispatcher,
    ): TurnCompletionDetector =
        EouTurnCompletionDetector(
            signal = signal,
            eouWindowMillis = windowMillis,
            clock = clock,
            collectorDispatcher = dispatcher,
        )

    private suspend fun events(
        fixture: com.voicechat.agent.replay.PcmFixture,
        semantic: TurnCompletionDetector?,
    ): List<TurnDetectionEvent> =
        BoundedTurnEndpointPolicy(
            config = config,
            semanticDetector = semantic,
            sink = com.voicechat.agent.contracts.NoOpDiagnosticsSink,
        ).observe(ReplayAudioInput(fixture).frames()).toList()

    @Test
    fun aPauseWithARecentConfirmedEouEndpointsOnceAsSemanticComplete() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val counting = CountingDetector(detector(signal, clock, dispatcher = dispatch))

            signal.confirm(offsetMillis = 100L)
            clock.advanceMillis(100L) // recent relative to the pause

            val events = events(VadTestFixtures.phrase("hello |"), counting)

            val endpoints = events.filterIsInstance<TurnDetectionEvent.Endpointed>()
            assertEquals(1, endpoints.size)
            assertEquals(EndpointReason.SEMANTIC_COMPLETE, endpoints.single().reason)
            assertEquals("the detector is evaluated once per candidate pause", 1, counting.count)
            assertTrue(events.filterIsInstance<TurnDetectionEvent.Activity>().any { it.activity == SpeechActivity.SPEECH_STARTED })
        }

    @Test
    fun aPauseWithNoEouHoldsAndTheSilenceCapStillEndpoints() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val counting = CountingDetector(detector(signal, clock, dispatcher = dispatch))
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 4_000)

            val events = events(fixture, counting)

            assertEquals(1, counting.count)
            assertEquals(
                EndpointHoldReason.SEMANTIC_INCOMPLETE,
                events.filterIsInstance<TurnDetectionEvent.Held>().single().reason,
            )
            val endpoints = events.filterIsInstance<TurnDetectionEvent.Endpointed>()
            assertEquals(1, endpoints.size)
            assertEquals(
                "the bounded cap, not EOU, is the guarantee the trailing turn ends",
                EndpointReason.SILENCE_CAP,
                endpoints.single().reason,
            )
        }

    @Test
    fun anOldEouIsIncompleteAndTheCapEndpoints() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val counting = CountingDetector(detector(signal, clock, windowMillis = 500L, dispatcher = dispatch))

            signal.confirm(offsetMillis = 1L)
            clock.advanceMillis(5_000L) // stale

            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 4_000)
            val events = events(fixture, counting)

            assertEquals(
                EndpointHoldReason.SEMANTIC_INCOMPLETE,
                events.filterIsInstance<TurnDetectionEvent.Held>().single().reason,
            )
            assertEquals(EndpointReason.SILENCE_CAP, events.filterIsInstance<TurnDetectionEvent.Endpointed>().single().reason)
        }

    @Test
    fun anAbsentSignalFallsBackToTheVadOnlyPolicy() =
        runTest {
            val clock = FakeMonotonicClock()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val counting = CountingDetector(detector(signal = null, clock = clock, dispatcher = dispatch))
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 4_000)

            val events = events(fixture, counting)

            assertEquals(TurnCompletion.UNAVAILABLE, counting.lastDecision)
            assertEquals(
                EndpointHoldReason.VAD_ONLY,
                events.filterIsInstance<TurnDetectionEvent.Held>().single().reason,
            )
            assertEquals(EndpointReason.SILENCE_CAP, events.filterIsInstance<TurnDetectionEvent.Endpointed>().single().reason)
        }

    @Test
    fun theOnsetAndBargeInPathIsIdenticalWithAndWithoutTheEouDetector() =
        runTest {
            val clock = FakeMonotonicClock()
            val signal = FakeEouSignal()
            val dispatch = UnconfinedTestDispatcher(testScheduler)
            val fixture = VadTestFixtures.pauseResume()

            val withoutEou = events(fixture, semantic = null)
            val withEou = events(fixture, detector(signal, clock, dispatcher = dispatch))

            val activitiesWithout = withoutEou.filterIsInstance<TurnDetectionEvent.Activity>().map { it.activity }
            val activitiesWith = withEou.filterIsInstance<TurnDetectionEvent.Activity>().map { it.activity }

            assertEquals(
                "barge-in onset/pause/resume must not change when EOU is present",
                activitiesWithout,
                activitiesWith,
            )
            assertTrue(activitiesWith.contains(SpeechActivity.SPEECH_STARTED))
            assertTrue(activitiesWith.contains(SpeechActivity.CANDIDATE_PAUSE))
        }

    /** Wraps a detector to count evaluations and remember the last verdict. */
    private class CountingDetector(
        private val delegate: TurnCompletionDetector,
    ) : TurnCompletionDetector {
        var count: Int = 0
            private set
        var lastDecision: TurnCompletion? = null
            private set

        override suspend fun evaluate(window: AudioFrame): TurnCompletion =
            delegate.evaluate(window).also {
                count++
                lastDecision = it
            }

        override suspend fun close() = delegate.close()
    }

    /** A test [EndOfUtteranceSignal] whose confirmation the test drives directly. */
    private class FakeEouSignal : EndOfUtteranceSignal {
        private val state = MutableStateFlow(EouObservation(Long.MIN_VALUE, null, 0L))
        private var latestSequence = 0L

        override val eouEvents: StateFlow<EouObservation> = state

        override fun lastEouOffsetMillis(): Long? = state.value.offsetMillis.takeIf { it != Long.MIN_VALUE }

        override fun consumeEou(): Boolean = false

        fun confirm(offsetMillis: Long) {
            state.value = EouObservation(offsetMillis, 0.9f, ++latestSequence)
        }
    }
}
