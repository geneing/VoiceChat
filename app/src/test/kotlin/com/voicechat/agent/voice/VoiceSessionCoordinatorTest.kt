package com.voicechat.agent.voice

import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.fake.FakeAudioInput
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.fake.LateInterimSpeechToText
import com.voicechat.agent.fake.ManualAudioInput
import com.voicechat.agent.fake.ManualSpeechToText
import com.voicechat.agent.fake.ManualVoiceTurnDetector
import com.voicechat.agent.fake.RecordingVoiceSessionListener
import com.voicechat.agent.fake.StallingSpeechToText
import com.voicechat.agent.vad.EndpointReason
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic coverage of the isolated capture → STT → endpoint path. */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionCoordinatorTest {
    private val conversation = Conversation(id = ConversationId("c1"), createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private fun coordinatorFor(
        repository: InMemoryConversationRepository,
        detector: VoiceTurnDetector,
        speechToText: SpeechToText,
        listener: VoiceSessionListener = RecordingVoiceSessionListener(),
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = MonotonicClock { 10L },
        dispatcher: CoroutineDispatcher,
    ): VoiceSessionCoordinator =
        VoiceSessionCoordinator(
            audioInput = FakeAudioInput(),
            repository = repository,
            speechToText = speechToText,
            turnDetector = detector,
            listener = listener,
            diagnostics = diagnostics,
            clock = clock,
            turnIdFactory = SequentialTurnIds()::next,
            dispatcher = dispatcher,
            wallClock = { 2L },
        )

    @Test
    fun finalTranscriptIsShownAndPersistedWithoutAssistantGeneration() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText =
                com.voicechat.agent.fake
                    .ScriptedSpeechToText(interims = listOf("helo", "hello"), finalText = "hello")
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, listener, dispatcher = dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            advanceUntilIdle()

            assertEquals(listOf("helo", "hello"), listener.provisional.map { it.second })
            assertEquals(
                "hello",
                listener.committed
                    .single()
                    .second.text,
            )
            val stored = repository.load(conversation.id)!!
            assertEquals(1, stored.turns.size)
            assertEquals("hello", (stored.turns.single() as UserTurn).transcript.text)
            assertEquals(
                1,
                listener.conversations
                    .single()
                    .turns.size,
            )
            assertEquals(listOf(VoiceSessionState.LISTENING), listener.sessionStates.distinct())
            session.cancelAndJoin()
        }

    @Test
    fun endpointWithoutSpeechDoesNotCreateAConversationTurn() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val listener = RecordingVoiceSessionListener()
            val coordinator =
                coordinatorFor(
                    repository,
                    detector,
                    com.voicechat.agent.fake
                        .ScriptedSpeechToText(),
                    listener,
                    dispatcher = dispatcher,
                )

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.endpoint(EndpointReason.EMPTY_NO_SPEECH)
            runCurrent()

            assertEquals(1, listener.noSpeechCount)
            assertEquals(0, listener.committed.size)
            assertNull(repository.load(conversation.id))
            session.cancelAndJoin()
        }

    @Test
    fun staleInterimRevisionIsDroppedBeforeTheFinalTranscriptIsSaved() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ManualSpeechToText()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, listener, dispatcher = dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            speechToText.interim("newer", revision = 5)
            speechToText.interim("stale", revision = 2)
            speechToText.final("final", revision = 6)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            advanceUntilIdle()

            // The stale revision ("stale") is dropped; the later interim and the
            // committed text echoed to the provisional bubble remain.
            assertEquals(listOf("newer", "final"), listener.provisional.map { it.second })
            assertEquals("final", (repository.load(conversation.id)!!.turns.single() as UserTurn).transcript.text)
            session.cancelAndJoin()
        }

    @Test
    fun interimArrivingAfterEndpointIsNotShownButFinalIsSaved() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = LateInterimSpeechToText(earlyInterim = "early", lateInterim = "late", finalText = "final")
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, listener, dispatcher = dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            advanceUntilIdle()

            assertEquals(listOf("early"), listener.provisional.map { it.second })
            assertEquals("final", (repository.load(conversation.id)!!.turns.single() as UserTurn).transcript.text)
            session.cancelAndJoin()
        }

    @Test
    fun stalledRecognizerTimesOutWithTypedSttError() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = StallingSpeechToText()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, listener, dispatcher = dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            speechToText.started.await()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()
            advanceTimeBy(5_001L)
            runCurrent()

            assertEquals(ErrorCode.STT_RECOGNITION_FAILED, listener.errors.single().code)
            assertNull(repository.load(conversation.id))
            session.cancelAndJoin()
            assertTrue(speechToText.cancelled.isCompleted)
        }

    @Test
    fun aTurnIsSeededWithTheAudioCapturedBeforeOnset() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ManualSpeechToText()
            val audio = ManualAudioInput()
            val listener = RecordingVoiceSessionListener()
            val coordinator =
                VoiceSessionCoordinator(
                    audioInput = audio,
                    repository = repository,
                    speechToText = speechToText,
                    turnDetector = detector,
                    listener = listener,
                    dispatcher = dispatcher,
                    clock = MonotonicClock { 10L },
                    turnIdFactory = SequentialTurnIds()::next,
                    wallClock = { 2L },
                )

            val session = launch(dispatcher) { coordinator.run(conversation) }
            // Speech has started, but the VAD has not declared onset yet: these
            // frames are the opening word(s) that used to be dropped.
            repeat(3) { audio.emit(frame(it.toLong())) }
            runCurrent()
            assertEquals("no turn exists before the onset decision", 0, speechToText.observedFrameCount)

            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            // The next captured frame flushes the pre-roll, then continues live.
            audio.emit(frame(3L))
            runCurrent()

            assertEquals(
                "the recognizer must receive the pre-onset frames plus the live frame",
                4,
                speechToText.observedFrameCount,
            )
            session.cancelAndJoin()
        }

    private fun frame(index: Long): AudioFrame =
        AudioFrame(format = AudioFormat.MONO_16_KHZ, samples = ShortArray(4), capturedAtNanos = index)

    private class SequentialTurnIds {
        private var count = 0

        fun next(): TurnId = TurnId("voice-${++count}")
    }

    @Test
    fun recognizerEndOfSpeechIsSurfacedButDoesNotCommitTheTurn() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ManualSpeechToText()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, listener, dispatcher = dispatcher)

            val session = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            speechToText.interim("hello", revision = 1)
            speechToText.send(SttEvent.EndOfSpeech)
            runCurrent()

            // The engine's own signal is observed, but nothing is committed: the
            // bounded VAD/Smart Turn endpoint is still the authority.
            assertEquals(1, listener.endOfSpeechCount)
            assertNull(repository.load(conversation.id))
            assertEquals(0, listener.committed.size)

            speechToText.final("hello", revision = 2)
            detector.endpoint(EndpointReason.SILENCE_CAP)
            advanceUntilIdle()

            assertEquals("hello", (repository.load(conversation.id)!!.turns.single() as UserTurn).transcript.text)
            session.cancelAndJoin()
        }
}
