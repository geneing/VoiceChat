package com.voicechat.agent.contracts

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.fake.FakeSpeechToText
import com.voicechat.agent.fake.FakeTurnCompletionDetector
import com.voicechat.agent.fake.FakeVoiceActivityDetector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechContractsTest {
    private val audioFrame =
        AudioFrame(AudioFormat.MONO_16_KHZ, shortArrayOf(1, 2, 3))

    @Test
    fun interimRevisionsAreEmittedBeforeTheFinalTranscript() =
        runTest {
            val speechToText =
                FakeSpeechToText(
                    script =
                        listOf(
                            SttEvent.Result(Transcript.interim("hel", TranscriptRevision(0))),
                            SttEvent.Result(Transcript.interim("hello", TranscriptRevision(1))),
                            SttEvent.Result(Transcript.final("hello world", TranscriptRevision(2))),
                        ),
                )

            val events = speechToText.transcribe(flowOf(audioFrame)).toList()

            assertEquals(3, events.size)
            assertEquals(listOf(false, false, true), events.map { (it as SttEvent.Result).transcript.isFinal })
            assertEquals(
                listOf(0, 1, 2),
                events.map { (it as SttEvent.Result).transcript.revision.value },
            )
            assertEquals(1, speechToText.observedFrameCount)
        }

    @Test
    fun recognitionFailureIsSurfacedAsATypedEvent() =
        runTest {
            val error = VoiceAgentError(ErrorCode.STT_RECOGNITION_FAILED)
            val speechToText = FakeSpeechToText(script = listOf(SttEvent.Failed(error)))

            val events = speechToText.transcribe(flowOf(audioFrame)).toList()

            assertEquals(listOf(SttEvent.Failed(error)), events)
        }

    @Test
    fun cancellingRecognitionStopsWithoutEmittingAResult() =
        runTest {
            val speechToText =
                FakeSpeechToText(
                    script = listOf(SttEvent.Result(Transcript.final("should not arrive"))),
                )
            val endlessAudio =
                flow {
                    emit(audioFrame)
                    awaitCancellation()
                }

            val job = launch { speechToText.transcribe(endlessAudio).collect { } }
            runCurrent()
            job.cancelAndJoin()

            assertEquals(1, speechToText.cancellationCount)
            assertEquals(1, speechToText.observedFrameCount)
        }

    @Test
    fun voiceActivityReportsOnsetPauseAndResumeInOrder() =
        runTest {
            val detector =
                FakeVoiceActivityDetector(
                    events =
                        listOf(
                            VadEvent.Activity(SpeechActivity.SPEECH_STARTED, atOffsetMillis = 10L),
                            VadEvent.Activity(SpeechActivity.CANDIDATE_PAUSE, atOffsetMillis = 500L),
                            VadEvent.Activity(SpeechActivity.SPEECH_RESUMED, atOffsetMillis = 700L),
                        ),
                )

            val events = detector.observe(flowOf(audioFrame)).toList()

            assertEquals(
                listOf(
                    SpeechActivity.SPEECH_STARTED,
                    SpeechActivity.CANDIDATE_PAUSE,
                    SpeechActivity.SPEECH_RESUMED,
                ),
                events.map { (it as VadEvent.Activity).activity },
            )
        }

    @Test
    fun semanticCompletionIsEvaluatedOnceAtACandidatePause() =
        runTest {
            val detector = FakeTurnCompletionDetector(decision = TurnCompletion.INCOMPLETE)

            val decision = detector.evaluate(audioFrame)

            assertEquals(TurnCompletion.INCOMPLETE, decision)
            assertEquals(1, detector.evaluationCount)
        }

    @Test
    fun anUnavailableSemanticDetectorFailsLoudly() {
        val error = VoiceAgentError(ErrorCode.TURN_DETECTION_UNAVAILABLE)
        val detector = FakeTurnCompletionDetector(failure = error)

        val thrown =
            assertThrows(VoiceAgentException::class.java) {
                kotlinx.coroutines.runBlocking { detector.evaluate(audioFrame) }
            }

        assertEquals(error, thrown.error)
        assertTrue(detector.evaluationCount == 1)
    }
}
