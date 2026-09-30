package com.voicechat.agent.turn

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.fake.FakeSmartTurnInferenceEngine
import com.voicechat.agent.replay.ReplayAudioInput
import com.voicechat.agent.vad.BoundedTurnEndpointPolicy
import com.voicechat.agent.vad.EndpointHoldReason
import com.voicechat.agent.vad.EndpointReason
import com.voicechat.agent.vad.TurnDetectionEvent
import com.voicechat.agent.vad.VadConfig
import com.voicechat.agent.vad.VadTestFixtures
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M10 end-to-end over the real M09 endpoint path: the Smart Turn detector is
 * evaluated exactly **once per VAD-confirmed candidate pause, never per audio
 * frame**, and the COMPLETE/INCOMPLETE verdict drives the same one-endpoint,
 * one-logical-turn behavior M09 guarantees.
 */
class SmartTurnEndpointIntegrationTest {
    private val config = SmartTurnConfig(windowSamples = 8_000)

    private fun detector(engine: FakeSmartTurnInferenceEngine) = SmartTurnCompletionDetector(engine, config)

    private suspend fun events(
        fixture: com.voicechat.agent.replay.PcmFixture,
        detector: SmartTurnCompletionDetector,
    ): List<TurnDetectionEvent> =
        BoundedTurnEndpointPolicy(
            config = VadConfig.default(),
            semanticDetector = detector,
        ).observe(ReplayAudioInput(fixture).frames()).toList()

    @Test
    fun theDetectorRunsOncePerCandidatePauseNotPerFrame() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine(probability = 0.9f)
            val fixture = VadTestFixtures.phrase("hello |")
            val frameCount = ReplayAudioInput(fixture).frames().toList().size

            val events = events(fixture, detector(engine))

            assertTrue("the fixture must contain many frames", frameCount > 3)
            assertEquals("one inference per candidate pause", 1, engine.inferenceCount)
            val endpoints = events.filterIsInstance<TurnDetectionEvent.Endpointed>()
            assertEquals(1, endpoints.size)
            assertEquals(EndpointReason.SEMANTIC_COMPLETE, endpoints.single().reason)
        }

    @Test
    fun anIncompleteVerdictHoldsTheTurnThenTheSilenceCapFinalizesIt() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine(probability = 0.1f)
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 4_000)

            val events = events(fixture, detector(engine))

            assertEquals(1, engine.inferenceCount)
            assertEquals(
                EndpointHoldReason.SEMANTIC_INCOMPLETE,
                events.filterIsInstance<TurnDetectionEvent.Held>().single().reason,
            )
            val endpoints = events.filterIsInstance<TurnDetectionEvent.Endpointed>()
            assertEquals(1, endpoints.size)
            assertEquals(EndpointReason.SILENCE_CAP, endpoints.single().reason)
        }

    @Test
    fun resumedSpeechAfterAnIncompleteVerdictStaysInTheSameUserTurn() =
        runTest {
            val engine = FakeSmartTurnInferenceEngine(probability = 0.1f)
            val fixture = VadTestFixtures.pauseResume()

            val events = events(fixture, detector(engine))

            assertEquals(1, engine.inferenceCount)
            assertTrue(events.activities().contains(SpeechActivity.SPEECH_RESUMED))
            val endpoints = events.filterIsInstance<TurnDetectionEvent.Endpointed>()
            assertEquals("one logical turn across the pause and resume", 1, endpoints.size)
            assertEquals(EndpointReason.CAPTURE_ENDED, endpoints.single().reason)
        }

    private fun List<TurnDetectionEvent>.activities(): List<SpeechActivity> =
        filterIsInstance<TurnDetectionEvent.Activity>().map { it.activity }
}
