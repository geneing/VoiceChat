package com.voicechat.agent.replay

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.contracts.VadEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Acceptance criteria call for pause/resume input; these tests prove the replay
 * VAD reports onset, a candidate pause, and resumed speech on the audio
 * timeline, and that the turn detector is evaluated at the candidate pause.
 */
class ReplayVoiceActivityTest {
    private val fixture = ReplayFixtures.syntheticPauseResume()

    private suspend fun activities(): List<VadEvent.Activity> =
        ReplayVoiceActivityDetector(fixture)
            .observe(ReplayAudioInput(fixture).frames())
            .toList()
            .map { it as VadEvent.Activity }

    @Test
    fun onsetCandidatePauseAndResumedSpeechAreReportedInOrder() =
        runTest {
            val events = activities()

            assertEquals(
                listOf(
                    SpeechActivity.SPEECH_STARTED,
                    SpeechActivity.CANDIDATE_PAUSE,
                    SpeechActivity.SPEECH_RESUMED,
                ),
                events.map { it.activity },
            )
        }

    @Test
    fun pauseAndResumeOffsetsFollowTheAudioTimeline() =
        runTest {
            val events = activities()
            val offsets = events.map { it.atOffsetMillis }

            assertEquals(offsets.sorted(), offsets)
            assertTrue("resume must follow the pause", offsets[1] < offsets[2])
            assertEquals(
                listOf(
                    frameOffsetMillis(
                        fixture.manifest.labels.vadEvents[0]
                            .frameIndex,
                        fixture.manifest.frameSizeSamples,
                        fixture.format.sampleRateHz,
                    ),
                    frameOffsetMillis(
                        fixture.manifest.labels.vadEvents[1]
                            .frameIndex,
                        fixture.manifest.frameSizeSamples,
                        fixture.format.sampleRateHz,
                    ),
                    frameOffsetMillis(
                        fixture.manifest.labels.vadEvents[2]
                            .frameIndex,
                        fixture.manifest.frameSizeSamples,
                        fixture.format.sampleRateHz,
                    ),
                ),
                offsets,
            )
        }

    @Test
    fun theTurnDetectorIsEvaluatedAtTheCandidatePauseAndKeepsTheTurnOpen() =
        runTest {
            val detector = ReplayTurnCompletionDetector(fixture)
            val pauseWindow = fixture.sliceFrames().first()

            assertEquals(TurnCompletion.INCOMPLETE, detector.evaluate(pauseWindow))
            assertEquals(TurnCompletion.COMPLETE, detector.evaluate(pauseWindow))
            assertEquals(TurnCompletion.UNAVAILABLE, detector.evaluate(pauseWindow))
            assertEquals(3, detector.evaluationCount)
            assertEquals(3, detector.evaluatedWindows.size)
        }

    @Test
    fun replayingDetectionTwiceIsIdentical() =
        runTest {
            val first =
                ReplayVoiceActivityDetector(fixture).observe(ReplayAudioInput(fixture).frames()).toList()
            val second =
                ReplayVoiceActivityDetector(fixture).observe(ReplayAudioInput(fixture).frames()).toList()

            assertEquals(first, second)
        }
}
