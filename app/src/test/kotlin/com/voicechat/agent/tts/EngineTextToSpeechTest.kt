package com.voicechat.agent.tts

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.fake.FakeTtsEngine
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import com.voicechat.agent.fake.ttsVoice
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure [EngineTextToSpeech] adapter, driven by [FakeTtsEngine].
 * No Android, no device, no availability is faked as "ready" unless a voice is
 * present.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineTextToSpeechTest {
    private val first = UtteranceId("utterance-1")
    private val second = UtteranceId("utterance-2")

    @Test
    fun playbackIsReportedAsQueuedStartedAndDeliveredInOrder() =
        runTest {
            val engine = FakeTtsEngine()
            val tts = EngineTextToSpeech(engine)

            val events = tts.speak("hello", first).toList()

            assertEquals(
                listOf(
                    TtsEvent.Queued(first, "hello"),
                    TtsEvent.Started(first, "hello"),
                    TtsEvent.Delivered(first, "hello"),
                ),
                events,
            )
            assertEquals(listOf("hello"), engine.spokenTexts)
        }

    @Test
    fun emptyInputCompletesWithoutPlaybackOrEngineWork() =
        runTest {
            val engine = FakeTtsEngine()
            val tts = EngineTextToSpeech(engine)

            assertEquals(emptyList<TtsEvent>(), tts.speak("", first).toList())
            assertTrue("empty input must not reach the engine", engine.spokenTexts.isEmpty())
        }

    @Test
    fun noOnDeviceVoiceIsAnExplicitTypedFailure() =
        runTest {
            val engine = FakeTtsEngine(voices = listOf(ttsVoice("network-voice", requiresNetwork = true)))
            val tts = EngineTextToSpeech(engine)

            val events = tts.speak("hello", first).toList()

            assertEquals(1, events.size)
            val failure = events.single() as TtsEvent.Failed
            assertEquals(ErrorCode.TTS_NO_ON_DEVICE_VOICE, failure.error.code)
            assertTrue("a network voice must never be used silently", engine.spokenTexts.isEmpty())
        }

    @Test
    fun aSelectedEmbeddedVoiceIsUsedForPlayback() =
        runTest {
            val engine =
                FakeTtsEngine(
                    voices = listOf(ttsVoice("voice-a"), ttsVoice("voice-b")),
                    preferredVoiceId = "voice-b",
                )
            val tts = EngineTextToSpeech(engine)

            val events = tts.speak("hello", first).toList()

            assertEquals("voice-b", engine.selectedVoice?.id)
            assertEquals(TtsEvent.Queued(first, "hello"), events.first())
            assertEquals(listOf("hello"), engine.spokenTexts)
        }

    @Test
    fun aSelectedVoiceThatIsNotInstalledIsAnExplicitTypedFailure() =
        runTest {
            val engine =
                FakeTtsEngine(
                    voices = listOf(ttsVoice("voice-a")),
                    preferredVoiceId = "voice-missing",
                )
            val tts = EngineTextToSpeech(engine)

            val events = tts.speak("hello", first).toList()

            assertEquals(1, events.size)
            val failure = events.single() as TtsEvent.Failed
            assertEquals(ErrorCode.TTS_NO_ON_DEVICE_VOICE, failure.error.code)
            assertTrue("no other voice may be substituted", engine.spokenTexts.isEmpty())
        }

    @Test
    fun incrementalChunksAreQueuedInOrder() =
        runTest {
            val engine = FakeTtsEngine()
            val tts = EngineTextToSpeech(engine)

            val firstEvents = tts.speak("one", first).toList()
            val secondEvents = tts.speak("two", second).toList()

            assertEquals(listOf("one", "two"), engine.spokenTexts)
            assertEquals(TtsEvent.Queued(first, "one"), firstEvents.first())
            assertEquals(TtsEvent.Queued(second, "two"), secondEvents.first())
        }

    @Test
    fun engineFailureIsMappedToATypedError() =
        runTest {
            val engine = FakeTtsEngine(failFirst = true)
            val tts = EngineTextToSpeech(engine)

            val events = tts.speak("hello", first).toList()

            assertEquals(TtsEvent.Queued(first, "hello"), events.first())
            val failure = events.last() as TtsEvent.Failed
            assertEquals(ErrorCode.TTS_SYNTHESIS_FAILED, failure.error.code)
        }

    @Test
    fun stopInterruptsEveryQueuedChunkAndLeavesNothingUnaccounted() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = FakeTtsEngine(autoComplete = false)
            val tts = EngineTextToSpeech(engine)
            val accounting = TtsPlaybackAccounting()
            accounting.onGenerated("hello world")

            val firstJob =
                launch {
                    tts.speak("hello ", first).collect { accounting.onEvent(first, it) }
                }
            val secondJob =
                launch {
                    tts.speak("world", second).collect { accounting.onEvent(second, it) }
                }
            // Both chunks are enqueued and playing (Started) before the stop.
            assertEquals(listOf("hello ", "world"), engine.spokenTexts)
            assertEquals("hello world", accounting.startedText)

            tts.stop()
            firstJob.join()
            secondJob.join()

            assertEquals(1, engine.stopCount)
            assertEquals("hello world", accounting.queuedText)
            assertTrue("every queued chunk must reach a terminal event", accounting.isFullyAccounted)
            assertEquals(com.voicechat.agent.domain.DeliveryState.INTERRUPTED, accounting.state)
            assertEquals("", accounting.deliveredText)
        }

    @Test
    fun cancellingCollectionStopsThatUtteranceWithoutHanging() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = FakeTtsEngine(autoComplete = false)
            val tts = EngineTextToSpeech(engine)
            val job = launch { tts.speak("hello", first).collect { } }
            // The utterance reached Started and is waiting for the engine.
            assertEquals(listOf("hello"), engine.spokenTexts)

            job.cancelAndJoin()

            // A later stop has nothing left to interrupt and must not hang.
            tts.stop()
            assertEquals(1, engine.stopCount)
        }

    @Test
    fun closeIsIdempotentAndReleasesTheEngine() =
        runTest {
            val engine = FakeTtsEngine()
            val tts = EngineTextToSpeech(engine)

            tts.close()
            tts.close()

            assertEquals(1, engine.closeCount)
            val error = runCatching { tts.speak("hello", first).toList() }.exceptionOrNull()
            assertTrue("speaking after close must fail", error is IllegalStateException)
        }

    @Test
    fun diagnosticsRecordFirstAudibleAndDeliveredCountsWithoutContent() =
        runTest {
            val sink = RecordingDiagnosticsSink()
            val tts = EngineTextToSpeech(FakeTtsEngine(), diagnostics = sink)

            tts.speak("hello world", first).toList()

            val playback = sink.events.filter { it.stage == DiagnosticStage.TTS_PLAYBACK }
            assertTrue(playback.any { it.attributes[DiagnosticAttribute.STREAM_STATE] == "first-audible" })
            val completed = playback.first { it.attributes[DiagnosticAttribute.STREAM_STATE] == "completed" }
            assertEquals("11", completed.attributes[DiagnosticAttribute.DELIVERED_CHARACTER_COUNT])
            assertEquals("11", completed.attributes[DiagnosticAttribute.TOTAL_CHARACTER_COUNT])
            assertFalse(
                "diagnostics must not carry assistant text",
                sink.events.any { it.attributes.values.any { value -> value.contains("hello") } },
            )
        }
}
