package com.voicechat.agent.vad

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.FakeTurnCompletionDetector
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import com.voicechat.agent.replay.FixtureVariants
import com.voicechat.agent.replay.NoiseProfile
import com.voicechat.agent.replay.NoiseTransformation
import com.voicechat.agent.replay.PcmFixture
import com.voicechat.agent.replay.ReplayAudioInput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M09 acceptance for the bounded VAD-only endpoint policy, driven entirely by
 * the M03 replay fixtures through the real M02 `AudioInput` contract.
 *
 * The properties asserted are the milestone's: exactly one final endpoint per
 * logical turn; a pause plus resumed speech stays one turn; the maximum-silence
 * cap always terminates a trailing turn; empty/no-speech is explicit; and the
 * behavior is deterministic across echo/music/noise degradations.
 */
class BoundedTurnEndpointPolicyTest {
    private val defaultConfig = VadConfig.default()

    private suspend fun events(
        fixture: PcmFixture,
        config: VadConfig = defaultConfig,
        detector: FakeTurnCompletionDetector? = null,
        diagnostics: RecordingDiagnosticsSink? = null,
    ): List<TurnDetectionEvent> =
        BoundedTurnEndpointPolicy(
            config = config,
            semanticDetector = detector,
            sink = diagnostics ?: com.voicechat.agent.contracts.NoOpDiagnosticsSink,
        ).observe(ReplayAudioInput(fixture).frames()).toList()

    private fun List<TurnDetectionEvent>.activities(): List<SpeechActivity> =
        filterIsInstance<TurnDetectionEvent.Activity>().map { it.activity }

    private fun List<TurnDetectionEvent>.endpoints(): List<TurnDetectionEvent.Endpointed> =
        filterIsInstance<TurnDetectionEvent.Endpointed>()

    private fun totalDurationMillis(fixture: PcmFixture): Long = fixture.samples.size.toLong() * 1000L / fixture.format.sampleRateHz

    @Test
    fun pauseThenResumeKeepsOneTurnAndEndpointsExactlyOnce() =
        runTest {
            val events = events(VadTestFixtures.pauseResume())

            assertEquals(1, events.endpoints().size)
            assertEquals(EndpointReason.CAPTURE_ENDED, events.endpoints().single().reason)
            assertEquals(
                listOf(
                    SpeechActivity.SPEECH_STARTED,
                    SpeechActivity.CANDIDATE_PAUSE,
                    SpeechActivity.SPEECH_RESUMED,
                ),
                events.activities(),
            )

            val resumeIndex = events.indexOfFirst { it is TurnDetectionEvent.Activity && it.activity == SpeechActivity.SPEECH_RESUMED }
            val endpointIndex = events.indexOfFirst { it is TurnDetectionEvent.Endpointed }
            assertTrue("the endpoint must come after resumed speech", endpointIndex > resumeIndex)

            val holds = events.filterIsInstance<TurnDetectionEvent.Held>()
            assertEquals(1, holds.size)
            assertEquals(EndpointHoldReason.VAD_ONLY, holds.single().reason)
        }

    @Test
    fun semanticCompleteFinalizesAtTheCandidatePauseWithoutWaitingForTheCap() =
        runTest {
            val detector = FakeTurnCompletionDetector(decision = TurnCompletion.COMPLETE)
            val events = events(VadTestFixtures.phrase("hello |"), detector = detector)

            assertEquals(1, events.endpoints().size)
            assertEquals(EndpointReason.SEMANTIC_COMPLETE, events.endpoints().single().reason)
            assertEquals("the semantic detector runs once per candidate pause, not per frame", 1, detector.evaluationCount)
            assertFalse(events.activities().contains(SpeechActivity.SPEECH_RESUMED))
        }

    @Test
    fun semanticIncompleteHoldsThenTheBoundedCapFinalizes() =
        runTest {
            val detector = FakeTurnCompletionDetector(decision = TurnCompletion.INCOMPLETE)
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 4_000)
            val events = events(fixture, detector = detector)

            assertEquals(1, detector.evaluationCount)
            assertEquals(
                EndpointHoldReason.SEMANTIC_INCOMPLETE,
                events.filterIsInstance<TurnDetectionEvent.Held>().single().reason,
            )
            assertEquals(1, events.endpoints().size)
            assertEquals(EndpointReason.SILENCE_CAP, events.endpoints().single().reason)
        }

    @Test
    fun aSemanticUnavailableDetectorUsesTheVadOnlyPolicy() =
        runTest {
            val detector = FakeTurnCompletionDetector(decision = TurnCompletion.UNAVAILABLE)
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 4_000)
            val events = events(fixture, detector = detector)

            assertEquals(1, detector.evaluationCount)
            assertEquals(
                EndpointHoldReason.VAD_ONLY,
                events.filterIsInstance<TurnDetectionEvent.Held>().single().reason,
            )
            assertEquals(1, events.endpoints().size)
            assertEquals(EndpointReason.SILENCE_CAP, events.endpoints().single().reason)
        }

    @Test
    fun prolongedSilenceEndsTheTurnBeforeCaptureEnds() =
        runTest {
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello"), 4_000)
            val config = defaultConfig.copy(maxSilenceMillis = 1_000)
            val events = events(fixture, config = config)

            val endpoint = events.endpoints().single()
            assertEquals(EndpointReason.SILENCE_CAP, endpoint.reason)
            assertTrue(
                "the cap must fire before capture end: endpoint=${endpoint.atOffsetMillis} total=${totalDurationMillis(fixture)}",
                endpoint.atOffsetMillis < totalDurationMillis(fixture) - 1_000,
            )
        }

    @Test
    fun anEmptyCaptureProducesExactlyOneExplicitEmptyEndpoint() =
        runTest {
            val events = events(VadTestFixtures.silence(1_000))

            assertEquals(1, events.endpoints().size)
            assertEquals(EndpointReason.EMPTY_NO_SPEECH, events.endpoints().single().reason)
            assertTrue(events.activities().isEmpty())
        }

    @Test
    fun aShortAcknowledgementEndsOnceWithoutAPause() =
        runTest {
            val events = events(VadTestFixtures.phrase("yes"))

            assertEquals(1, events.endpoints().size)
            assertEquals(EndpointReason.CAPTURE_ENDED, events.endpoints().single().reason)
            assertFalse(events.activities().contains(SpeechActivity.CANDIDATE_PAUSE))
            assertTrue(events.filterIsInstance<TurnDetectionEvent.Held>().isEmpty())
        }

    @Test
    fun aNaturalShortPauseDoesNotCommitTheTurn() =
        runTest {
            val events = events(VadTestFixtures.phrase("hi, there"))

            assertFalse(
                "a sub-threshold pause must not become a candidate pause: ${events.activities()}",
                events.activities().contains(SpeechActivity.CANDIDATE_PAUSE),
            )
            assertEquals(1, events.endpoints().size)
        }

    @Test
    fun eachLogicalTurnGetsExactlyOneEndpointAndResumedSpeechStartsANewTurn() =
        runTest {
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.pauseResume(), 3_000)
            // A cap shorter than the pause commits the first turn before speech resumes.
            val config = defaultConfig.copy(maxSilenceMillis = 200)
            val events = events(fixture, config = config)

            val endpoints = events.endpoints()
            assertEquals(2, endpoints.size)
            assertTrue(endpoints.all { it.reason == EndpointReason.SILENCE_CAP })
            assertEquals(endpoints.map { it.atOffsetMillis }.sorted(), endpoints.map { it.atOffsetMillis })
            assertEquals(2, events.activities().count { it == SpeechActivity.SPEECH_STARTED || it == SpeechActivity.SPEECH_RESUMED })
        }

    @Test
    fun theSemanticWindowHandedToTheDetectorIsBoundedByConfiguration() =
        runTest {
            val detector = FakeTurnCompletionDetector(decision = TurnCompletion.INCOMPLETE)
            val config = defaultConfig.copy(semanticWindowMillis = 500)
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 1_000)
            events(fixture, config = config, detector = detector)

            val window = detector.evaluatedWindows.single()
            val expected = 500L * fixture.format.sampleRateHz / 1000L
            assertTrue("window too large: ${window.sampleCount}", window.sampleCount <= expected.toInt())
            assertTrue("window too small: ${window.sampleCount}", window.sampleCount >= expected.toInt() - 320)
        }

    @Test
    fun aSemanticFailureIsVisibleAndFallsBackToTheVadOnlyCap() =
        runTest {
            val detector =
                FakeTurnCompletionDetector(
                    failure = VoiceAgentError(ErrorCode.TURN_DETECTION_UNAVAILABLE, "no model"),
                )
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello |"), 4_000)
            val sink = RecordingDiagnosticsSink()
            val events = events(fixture, detector = detector, diagnostics = sink)

            assertEquals(
                EndpointHoldReason.VAD_ONLY,
                events.filterIsInstance<TurnDetectionEvent.Held>().single().reason,
            )
            assertEquals(1, events.endpoints().size)
            assertTrue(sink.events.any { it.outcome == com.voicechat.agent.contracts.DiagnosticOutcome.FAILED })
        }

    @Test
    fun standardDegradationVariantsEachEndExactlyOnceAndDeterministically() =
        runTest {
            val base = VadTestFixtures.pauseResume()
            val variants = FixtureVariants.standardVariants(base)
            assertTrue("expected the M03 degradation variants", variants.size >= 8)

            variants.forEach { variant ->
                val first = events(variant).endpoints()
                val second = events(variant).endpoints()
                assertEquals("${variant.manifest.id} must end exactly once", 1, first.size)
                assertEquals("${variant.manifest.id} must be deterministic", first, second)
            }
        }

    @Test
    fun windNoiseAndMusicLikeToneStillEndExactlyOnce() =
        runTest {
            val wind =
                VadTestFixtures.transformed(
                    VadTestFixtures.pauseResume(),
                    NoiseTransformation(profile = NoiseProfile.WIND, snrDb = 10.0, seed = 99L),
                )
            val music = VadTestFixtures.tone(millis = 1_500, frequencyHz = 220.0)

            listOf(wind, music).forEach { fixture ->
                val endpoints = events(fixture).endpoints()
                assertEquals("${fixture.manifest.id} must end exactly once", 1, endpoints.size)
                assertEquals(endpoints, events(fixture).endpoints())
            }
        }

    @Test
    fun diagnosticsRecordThresholdReasonsWithoutAudioContent() =
        runTest {
            val sink = RecordingDiagnosticsSink()
            events(VadTestFixtures.pauseResume(), diagnostics = sink)

            val detectionEvents = sink.events.filter { it.stage == DiagnosticStage.TURN_DETECTION }
            assertTrue("expected turn-detection diagnostics", detectionEvents.isNotEmpty())
            assertTrue(detectionEvents.all { it.attributes[DiagnosticAttribute.VAD_EVENT] != null })
            assertTrue(detectionEvents.all { it.attributes[DiagnosticAttribute.VAD_REASON] != null })
            assertTrue(
                "activity events must carry the measured level, never sample content",
                detectionEvents
                    .filter { it.attributes[DiagnosticAttribute.VAD_EVENT] == SpeechActivity.SPEECH_STARTED.name }
                    .all { it.attributes[DiagnosticAttribute.AUDIO_RMS_LEVEL] != null },
            )
            assertTrue(sink.events.none { it.traceId != null })
        }
}
