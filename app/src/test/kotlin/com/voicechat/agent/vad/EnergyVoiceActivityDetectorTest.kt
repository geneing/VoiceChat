package com.voicechat.agent.vad

import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.VadEvent
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.replay.ReplayAudioInput
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fast onset path: replay-labeled audio through the real measured-audio
 * detector (not a scripted fake) and assert it separates onset, pause, and
 * resume from the audio itself.
 */
class EnergyVoiceActivityDetectorTest {
    private val config = VadConfig.default()

    private suspend fun activities(fixture: com.voicechat.agent.replay.PcmFixture): List<VadEvent.Activity> =
        EnergyVoiceActivityDetector(config)
            .observe(ReplayAudioInput(fixture).frames())
            .toList()
            .map { it as VadEvent.Activity }

    @Test
    fun onsetCandidatePauseAndResumeAreDetectedFromCleanAudio() =
        runTest {
            val events = activities(VadTestFixtures.pauseResume())

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
    fun eventOffsetsIncreaseAndOnsetLatencyIsLow() =
        runTest {
            val events = activities(VadTestFixtures.pauseResume())
            val offsets = events.map { it.atOffsetMillis }

            assertEquals(offsets.sorted(), offsets)
            assertEquals(offsets.distinct(), offsets)
            assertTrue("onset must arrive within 200 ms: $offsets", offsets.first() <= 200L)
            assertTrue("pause must follow onset", offsets[0] < offsets[1])
            assertTrue("resume must follow the pause", offsets[1] < offsets[2])
        }

    @Test
    fun pureSilenceProducesNoActivity() =
        runTest {
            val events = EnergyVoiceActivityDetector(config).observe(ReplayAudioInput(VadTestFixtures.silence(1_000)).frames()).toList()

            assertTrue("silence must not report activity: $events", events.isEmpty())
        }

    @Test
    fun aNonMonoFrameFailsExplicitlyInsteadOfMisMeasuring() =
        runTest {
            val stereo = AudioFrame(AudioFormat(sampleRateHz = 16_000, channelCount = 2), ShortArray(320) { 1_000 })

            val events = EnergyVoiceActivityDetector(config).observe(flowOf(stereo)).toList()

            assertEquals(1, events.size)
            val failed = events.single() as VadEvent.Failed
            assertEquals(ErrorCode.TURN_DETECTION_FAILED, failed.error.code)
        }

    @Test
    fun replayingDetectionTwiceIsIdentical() =
        runTest {
            val fixture = VadTestFixtures.pauseResume()

            val first = EnergyVoiceActivityDetector(config).observe(ReplayAudioInput(fixture).frames()).toList()
            val second = EnergyVoiceActivityDetector(config).observe(ReplayAudioInput(fixture).frames()).toList()

            assertEquals(first, second)
        }
}
