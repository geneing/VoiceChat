package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.FakeTextToSpeech
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextToSpeechContractTest {
    private val utteranceId = UtteranceId("utterance-1")

    @Test
    fun playbackIsReportedAsQueuedStartedAndDelivered() =
        runTest {
            val tts = FakeTextToSpeech()

            val events = tts.speak("hello", utteranceId).toList()

            assertEquals(
                listOf(
                    TtsEvent.Queued(utteranceId, "hello"),
                    TtsEvent.Started(utteranceId, "hello"),
                    TtsEvent.Delivered(utteranceId, "hello"),
                ),
                events,
            )
            assertEquals(listOf("hello"), tts.spokenTexts)
        }

    @Test
    fun anInterruptedUtteranceReportsWhatWasActuallyHeard() =
        runTest {
            val tts =
                FakeTextToSpeech(
                    script = { id, text ->
                        listOf(
                            TtsEvent.Queued(id, text),
                            TtsEvent.Started(id, text),
                            TtsEvent.Interrupted(id, deliveredText = text.substring(0, 5)),
                        )
                    },
                )

            val events = tts.speak("hello world", utteranceId).toList()

            val terminal = events.last()
            assertTrue(terminal is TtsEvent.Interrupted)
            assertEquals("hello", (terminal as TtsEvent.Interrupted).deliveredText)
        }

    @Test
    fun synthesisFailureIsASingleTerminalEvent() =
        runTest {
            val error = VoiceAgentError(ErrorCode.TTS_NO_ON_DEVICE_VOICE)
            val tts = FakeTextToSpeech(script = { _, _ -> listOf(TtsEvent.Failed(error)) })

            assertEquals(listOf(TtsEvent.Failed(error)), tts.speak("hello", utteranceId).toList())
        }

    @Test
    fun stopIsObservableForBargeIn() =
        runTest {
            val tts = FakeTextToSpeech()

            tts.stop()
            tts.stop()

            assertEquals(2, tts.stopCount)
        }

    @Test
    fun cancellingPlaybackStopsTheUtterance() =
        runTest {
            val tts =
                FakeTextToSpeech(
                    script = { id, text ->
                        listOf(
                            TtsEvent.Queued(id, text),
                            TtsEvent.Started(id, text),
                            TtsEvent.Delivered(id, text),
                        )
                    },
                    eventDelayMillis = 100L,
                )

            val job = launch { tts.speak("hello", utteranceId).collect { } }
            advanceTimeBy(150L)
            job.cancelAndJoin()

            assertEquals(1, tts.cancellationCount)
        }
}
