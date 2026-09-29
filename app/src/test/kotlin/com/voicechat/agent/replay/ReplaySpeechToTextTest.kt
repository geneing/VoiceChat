package com.voicechat.agent.replay

import com.voicechat.agent.contracts.SttEvent
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The acceptance criteria call for revised partial hypotheses driven by
 * replayed input; these tests prove the replay STT emits them in order on the
 * fixture timeline and consumes the replay frames.
 */
class ReplaySpeechToTextTest {
    private val fixture = ReplayFixtures.syntheticPauseResume()

    private suspend fun results(): List<SttEvent.Result> =
        ReplaySpeechToText(fixture)
            .transcribe(ReplayAudioInput(fixture).frames())
            .toList()
            .map { it as SttEvent.Result }

    @Test
    fun partialHypothesesAreRevisedBeforeTheFinalTranscript() =
        runTest {
            val results = results()

            assertEquals(listOf(false, false, true), results.map { it.transcript.isFinal })
            assertEquals(listOf("hello", "hello again", "hello again"), results.map { it.transcript.text })
            assertEquals(listOf(0, 1, 2), results.map { it.transcript.revision.value })
        }

    @Test
    fun theFinalHypothesisMatchesTheManifestLabel() =
        runTest {
            val finalResult = results().last()

            assertTrue(finalResult.transcript.isFinal)
            assertEquals(fixture.manifest.labels.finalTranscript, finalResult.transcript.text)
        }

    @Test
    fun replayingRecognitionTwiceIsIdenticalAndConsumesEveryFrame() =
        runTest {
            val first = ReplaySpeechToText(fixture)
            val second = ReplaySpeechToText(fixture)

            val firstEvents = first.transcribe(ReplayAudioInput(fixture).frames()).toList()
            val secondEvents = second.transcribe(ReplayAudioInput(fixture).frames()).toList()

            assertEquals(firstEvents, secondEvents)
            assertEquals(fixture.sliceFrames().size, first.observedFrameCount)
            assertEquals(first.observedFrameCount, second.observedFrameCount)
        }

    @Test
    fun closingTheReplayRecognizerIsIdempotent() =
        runTest {
            val speechToText = ReplaySpeechToText(fixture)

            assertTrue(!speechToText.closed)
            speechToText.close()
            speechToText.close()
            assertTrue(speechToText.closed)
            assertEquals(fixture.manifest.engine.engineId, speechToText.engineId.value)
        }
}
