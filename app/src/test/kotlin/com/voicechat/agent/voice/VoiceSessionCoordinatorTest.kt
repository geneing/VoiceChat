package com.voicechat.agent.voice

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.fake.DeterministicLanguageModel
import com.voicechat.agent.fake.FakeAudioInput
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.FakeTextToSpeech
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.fake.KeepAliveAudioInput
import com.voicechat.agent.fake.LateInterimSpeechToText
import com.voicechat.agent.fake.ManualSpeechToText
import com.voicechat.agent.fake.ManualVoiceTurnDetector
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import com.voicechat.agent.fake.RecordingVoiceSessionListener
import com.voicechat.agent.fake.ScriptedLlmStep
import com.voicechat.agent.fake.ScriptedSpeechToText
import com.voicechat.agent.fake.StallingSpeechToText
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.replay.ReplayAudioInput
import com.voicechat.agent.vad.BoundedTurnEndpointPolicy
import com.voicechat.agent.vad.EndpointReason
import com.voicechat.agent.vad.VadConfig
import com.voicechat.agent.vad.VadTestFixtures
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic JVM tests for the M24 [VoiceSessionCoordinator].
 *
 * They drive the real coordinator over the M02 contracts with deterministic fakes
 * and the fake M03 replay fixtures, so no device, microphone, network, or
 * credential is involved. Interruptions are placed at precise LLM/TTS points to
 * prove the acceptance cases: next-user capture starts, no stale events are
 * applied, history keeps only delivered text, and stop/cancel timing is recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSessionCoordinatorTest {
    private val selection = ProviderModelSelection(ProviderId("test-provider"), ModelId("test-model"))
    private val conversation = Conversation(id = ConversationId("c1"), createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    /** Shared by every orchestrator in a test so two assistant turns never collide. */
    private val assistantTurnIds = SequentialAssistantIds()

    private fun coordinatorFor(
        repository: InMemoryConversationRepository,
        detector: VoiceTurnDetector,
        speechToText: SpeechToText,
        languageModel: LanguageModel,
        textToSpeech: TextToSpeech? = null,
        listener: VoiceSessionListener = RecordingVoiceSessionListener(),
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = FakeMonotonicClock(),
        dispatcher: CoroutineDispatcher,
        audioInput: com.voicechat.agent.contracts.AudioInput = FakeAudioInput(),
        providerSource: VoiceTurnProviderSource =
            VoiceTurnProviderSource { VoiceTurnProvider(selection, reasoning = null, languageModel = languageModel) },
    ): VoiceSessionCoordinator =
        VoiceSessionCoordinator(
            audioInput = audioInput,
            speechToText = speechToText,
            textToSpeech = textToSpeech,
            turnDetector = detector,
            orchestratorFactory = {
                TurnOrchestrator(
                    repository = repository,
                    languageModel = languageModel,
                    textToSpeech = textToSpeech,
                    diagnostics = diagnostics,
                    clock = clock,
                    wallClock = { 2L },
                    assistantTurnId = assistantTurnIds,
                )
            },
            providerSource = providerSource,
            listener = listener,
            diagnostics = diagnostics,
            clock = clock,
            turnIdFactory = SequentialTurnIds(),
            dispatcher = dispatcher,
        )

    @Test
    fun aSpokenUtteranceShowsProvisionalTextCommitsAndSpeaksTheReply() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(interims = listOf("helo", "hello"), finalText = "hello")
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("Hi. "), LlmStreamEvent.Completed()),
                )
            val tts = FakeTextToSpeech()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, tts, listener, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            assertEquals(listOf("helo", "hello"), listener.provisional.map { it.second })
            assertEquals(1, listener.listeningCount)
            assertEquals(
                "hello",
                listener.committed
                    .single()
                    .second.text,
            )
            // The interim text is provisional; it never reaches the persisted turn.
            val stored = repository.load(conversation.id)!!
            assertEquals(2, stored.turns.size)
            assertEquals("hello", (stored.turns[0] as UserTurn).transcript.text)
            val assistant = stored.turns[1] as AssistantTurn
            assertEquals("Hi. ", assistant.generated.text)
            assertEquals("Hi. ", assistant.delivery.deliveredText)
            assertEquals(listOf("Hi. "), tts.spokenTexts)
            job.cancelAndJoin()
        }

    @Test
    fun aRevokedPermissionOrEmptyCaptureIsReportedAsNoSpeechWithoutATurn() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val listener = RecordingVoiceSessionListener()
            val coordinator =
                coordinatorFor(
                    repository,
                    detector,
                    ScriptedSpeechToText(),
                    FakeLanguageModel(),
                    listener = listener,
                    dispatcher = dispatcher,
                )

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.endpoint(EndpointReason.EMPTY_NO_SPEECH)
            runCurrent()

            assertEquals(0, listener.listeningCount)
            assertEquals(1, listener.noSpeechCount)
            assertNull(repository.load(conversation.id))
            job.cancelAndJoin()
        }

    @Test
    fun aStaleInterimRevisionIsDroppedAndNeverShown() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ManualSpeechToText()
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, listener = listener, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            speechToText.interim("newer", revision = 5)
            speechToText.interim("stale", revision = 2)
            speechToText.final("done", revision = 6)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            assertEquals(listOf("newer"), listener.provisional.map { it.second })
            assertEquals(
                "done",
                listener.committed
                    .single()
                    .second.text,
            )
            job.cancelAndJoin()
        }

    @Test
    fun bargeInWhileSpeakingStopsPlaybackCancelsGenerationAndStartsANewCapture() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("Hi. ")),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("unheard")),
                            ScriptedLlmStep.Stall(60_000L),
                        ),
                )
            val tts = FakeTextToSpeech()
            val sink = RecordingDiagnosticsSink()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, tts, listener, sink, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()
            assertEquals(listOf("Hi. ", "Hi. unheard"), listener.assistantText.map { it.second })

            // User speech arrives while the assistant is still generating/speaking.
            detector.speech(SpeechActivity.SPEECH_RESUMED)
            runCurrent()

            // Barge-in stopped playback and cancelled generation immediately...
            assertEquals(1, listener.bargeIns.size)
            assertTrue("playback must be stopped", tts.stopCount >= 1)
            assertTrue("generation must be cancelled", model.cancellationCount >= 1)
            // ...and the next capture started without waiting for the cancellation.
            assertEquals("the new utterance must start listening", 2, listener.listeningCount)
            val bargeIn = listener.bargeIns.single()
            assertNotNull("capture must have resumed", bargeIn.captureResumedAtNanos)
            assertTrue(bargeIn.onsetToStopNanos >= 0L)

            // The interrupted turn settles with only the delivered prefix.
            runCurrent()
            val assistant = repository.load(conversation.id)!!.turns[1] as AssistantTurn
            assertEquals("Hi. unheard", assistant.generated.text)
            assertEquals("Hi. ", assistant.delivery.deliveredText)
            assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
            assertEquals(GenerationState.CANCELLED, assistant.generated.state)
            assertTrue(assistant.generated.text.startsWith(assistant.delivery.deliveredText))
            assertFalse(assistant.delivery.deliveredText == assistant.generated.text)

            // The stop/cancel timing is recorded as privacy-safe diagnostics.
            assertTrue(
                "barge-in stop timing must be recorded",
                sink.events.any { it.attributes[DiagnosticAttribute.BARGE_IN_STOP_MILLIS] != null },
            )
            job.cancelAndJoin()
        }

    @Test
    fun aShortAcknowledgementAfterBargeInCommitsAsANewTurn() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Delta("Reply. ")), ScriptedLlmStep.Stall(60_000L)),
                )
            val tts = FakeTextToSpeech()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, tts, listener, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            // A short backchannel/acknowledgement interrupts and becomes its own turn.
            speechToText.finalText = "yes"
            detector.speech(SpeechActivity.SPEECH_RESUMED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            assertEquals(
                "yes",
                listener.committed
                    .last()
                    .second.text,
            )
            val stored = repository.load(conversation.id)!!
            assertTrue(stored.turns.filterIsInstance<UserTurn>().any { it.transcript.text == "yes" })
            assertEquals(VoiceInterruptionRecovery.COMMITTED, coordinator.bargeIns.single().recovery)
            job.cancelAndJoin()
        }

    @Test
    fun aFalseNoiseInterruptionCommitsNoTurnAndLeavesTheReplyInterrupted() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Delta("Reply. ")), ScriptedLlmStep.Stall(60_000L)),
                )
            val tts = FakeTextToSpeech()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, tts, listener, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            // Onset fires on noise/echo, but no usable speech follows.
            speechToText.finalText = ""
            detector.speech(SpeechActivity.SPEECH_RESUMED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            assertEquals(VoiceInterruptionRecovery.NO_USABLE_SPEECH, listener.recoveries.last())
            assertTrue(listener.noSpeechCount >= 1)
            // No phantom user turn: only turn A's user turn exists.
            val stored = repository.load(conversation.id)!!
            assertEquals(1, stored.turns.filterIsInstance<UserTurn>().size)
            assertEquals(VoiceInterruptionRecovery.NO_USABLE_SPEECH, coordinator.bargeIns.single().recovery)
            assertNotNull(coordinator.bargeIns.single().settledAtNanos)
            job.cancelAndJoin()
        }

    @Test
    fun stopEndsTheSessionAndCancelsInFlightWork() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Delta("Reply. ")), ScriptedLlmStep.Stall(60_000L)),
                )
            val tts = FakeTextToSpeech()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, tts, listener, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            coordinator.stop()
            job.cancelAndJoin()

            assertEquals(VoiceSessionState.STOPPED, listener.sessionStates.last())
            assertTrue(model.cancellationCount >= 1)
        }

    @Test
    fun theRealM03ReplayAndEndpointPolicyDriveACommittedVoiceTurn() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            // Reuse the M03 fixture generator and the real M09 bounded endpoint policy.
            val fixture = VadTestFixtures.withTrailingSilence(VadTestFixtures.phrase("hello"), 3_000)
            val detector =
                PolicyVoiceTurnDetector(
                    BoundedTurnEndpointPolicy(config = VadConfig.default().copy(maxSilenceMillis = 400)),
                )
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("Sure. "), LlmStreamEvent.Completed()),
                )
            val tts = FakeTextToSpeech()
            val listener = RecordingVoiceSessionListener()
            val coordinator =
                coordinatorFor(
                    repository,
                    detector,
                    speechToText,
                    model,
                    tts,
                    listener,
                    dispatcher = dispatcher,
                    audioInput = KeepAliveAudioInput(ReplayAudioInput(fixture)),
                )

            val job = launch(dispatcher) { coordinator.run(conversation) }
            advanceUntilIdle()

            assertEquals(1, listener.committed.size)
            assertEquals(
                "hello",
                listener.committed
                    .single()
                    .second.text,
            )
            val assistant = repository.load(conversation.id)!!.turns[1] as AssistantTurn
            assertEquals("Sure. ", assistant.delivery.deliveredText)
            job.cancelAndJoin()
        }

    @Test
    fun bargeInDuringTtsPlaybackStopsQueuedAudioAndKeepsOnlyTheDeliveredPrefix() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("First sentence. ")),
                            ScriptedLlmStep.Stall(60_000L),
                        ),
                )
            // Playback is slow enough that the barge-in lands while the chunk is
            // playing, so this is the TTS playback interruption point.
            val tts = FakeTextToSpeech(eventDelayMillis = 100L)
            val sink = RecordingDiagnosticsSink()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, tts, listener, sink, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()
            advanceTimeBy(150L)
            runCurrent()
            assertEquals(listOf("First sentence. "), tts.spokenTexts)

            // The user starts speaking while the chunk is still audible.
            detector.speech(SpeechActivity.SPEECH_RESUMED)
            runCurrent()

            assertEquals(1, listener.bargeIns.size)
            assertTrue("queued audio must be stopped immediately", tts.stopCount >= 1)
            assertEquals("the new utterance must start listening", 2, listener.listeningCount)

            runCurrent()
            val assistant = repository.load(conversation.id)!!.turns[1] as AssistantTurn
            assertEquals("First sentence. ", assistant.generated.text)
            // The chunk started but was never confirmed delivered: history keeps
            // only the audible prefix, never the unheard remainder.
            assertTrue(assistant.generated.text.startsWith(assistant.delivery.deliveredText))
            assertFalse(assistant.delivery.deliveredText == assistant.generated.text)
            assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
            assertEquals(GenerationState.CANCELLED, assistant.generated.state)

            advanceUntilIdle()
            val bargeIn = coordinator.bargeIns.single()
            assertTrue("onset-to-stop must be recorded", bargeIn.onsetToStopNanos >= 0L)
            assertNotNull("capture must have resumed without waiting for cancel", bargeIn.captureResumedAtNanos)
            assertNotNull("the interrupted turn must settle", bargeIn.settledAtNanos)
            assertTrue(
                "stop timing must reach the diagnostics sink",
                sink.events.any { it.attributes[DiagnosticAttribute.BARGE_IN_STOP_MILLIS] != null },
            )
            job.cancelAndJoin()
        }

    @Test
    fun bargeInBeforeAnyAssistantTextLeavesNoPhantomAssistantTurn() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            // The stream stalls before emitting any delta: the interruption lands
            // at the very start of generation.
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = listOf(ScriptedLlmStep.Stall(60_000L)),
                )
            val tts = FakeTextToSpeech()
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, tts, listener, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            detector.speech(SpeechActivity.SPEECH_RESUMED)
            runCurrent()

            assertEquals(1, listener.bargeIns.size)
            assertTrue("no text was delivered, so nothing was spoken", tts.spokenTexts.isEmpty())
            assertEquals("the new utterance must start listening", 2, listener.listeningCount)
            assertTrue("no assistant text may reach the dialog", listener.assistantText.isEmpty())

            runCurrent()
            // Nothing was generated, so no phantom assistant turn is stored.
            val stored = repository.load(conversation.id)!!
            assertEquals(1, stored.turns.size)
            assertTrue(stored.turns.single() is UserTurn)
            job.cancelAndJoin()
        }

    @Test
    fun aBargeInSupersedesTheInterruptedTurnAndEachTurnsTextIsTaggedByItsOwnId() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = ScriptedSpeechToText(finalText = "hello")
            val interrupted =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("Hi. ")),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("unheard")),
                            ScriptedLlmStep.Stall(60_000L),
                        ),
                )
            val nextTurn =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("Second. "), LlmStreamEvent.Completed()),
                )
            var resolved = 0
            val providerSource =
                VoiceTurnProviderSource {
                    val model = if (resolved++ == 0) interrupted else nextTurn
                    VoiceTurnProvider(selection, reasoning = null, languageModel = model)
                }
            val tts = FakeTextToSpeech()
            val listener = RecordingVoiceSessionListener()
            val coordinator =
                coordinatorFor(
                    repository,
                    detector,
                    speechToText,
                    interrupted,
                    tts,
                    listener,
                    dispatcher = dispatcher,
                    providerSource = providerSource,
                )

            val job = launch(dispatcher) { coordinator.run(conversation) }
            // Turn v1 streams and starts speaking.
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()
            // Barge-in starts turn v2, which then completes normally.
            detector.speech(SpeechActivity.SPEECH_RESUMED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            advanceUntilIdle()

            // Every live assistant update is tagged with the turn that produced it;
            // the interrupted turn's text never appears under the new turn's ID.
            val byTurn = listener.assistantText.groupBy({ it.first }, { it.second })
            assertEquals(listOf("Hi. ", "Hi. unheard"), byTurn[TurnId("v1")])
            assertEquals(listOf("Second. "), byTurn[TurnId("v2")])

            val stored = repository.load(conversation.id)!!
            assertEquals(4, stored.turns.size)
            val firstAssistant = stored.turns[1] as AssistantTurn
            assertEquals("Hi. unheard", firstAssistant.generated.text)
            assertEquals("Hi. ", firstAssistant.delivery.deliveredText)
            assertEquals(DeliveryState.INTERRUPTED, firstAssistant.delivery.state)
            val secondAssistant = stored.turns[3] as AssistantTurn
            assertEquals("Second. ", secondAssistant.generated.text)
            assertEquals("Second. ", secondAssistant.delivery.deliveredText)
            assertEquals(DeliveryState.COMPLETED, secondAssistant.delivery.state)

            assertEquals(VoiceInterruptionRecovery.COMMITTED, coordinator.bargeIns.single().recovery)
            assertNotNull(coordinator.bargeIns.single().settledAtNanos)
            job.cancelAndJoin()
        }

    @Test
    fun aLateInterimAfterTheTurnEndpointIsDroppedAndOnlyTheFinalIsShown() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText =
                LateInterimSpeechToText(
                    earlyInterim = "early",
                    lateInterim = "late guess",
                    finalText = "done",
                )
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val listener = RecordingVoiceSessionListener()
            val coordinator = coordinatorFor(repository, detector, speechToText, model, listener = listener, dispatcher = dispatcher)

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            // The late interim arrives while the turn finalizes and is superseded;
            // only the earlier provisional guess and the final text are shown.
            assertEquals(listOf("early"), listener.provisional.map { it.second })
            assertEquals(
                "done",
                listener.committed
                    .single()
                    .second.text,
            )
            job.cancelAndJoin()
        }

    @Test
    fun aStalledRecognizerIsBoundedAndFailsTypedInsteadOfHangingTheSession() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            val speechToText = StallingSpeechToText()
            val listener = RecordingVoiceSessionListener()
            val coordinator =
                coordinatorFor(
                    repository,
                    detector,
                    speechToText,
                    FakeLanguageModel(),
                    listener = listener,
                    dispatcher = dispatcher,
                )

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            // The recognizer ignores its closed input; the coordinator must not sit
            // in listening state forever. Advance past the completion bound.
            advanceTimeBy(5_001L)
            runCurrent()

            assertEquals(
                listOf(ErrorCode.STT_RECOGNITION_FAILED),
                listener.errors.map { it.code },
            )
            assertTrue("the stalled recognizer must be cancelled", speechToText.cancelled.isCompleted)
            assertEquals(VoiceSessionState.FAILED, listener.sessionStates.last())
            job.cancelAndJoin()
        }

    @Test
    fun endpointFinalizationDoesNotBlockDetectionOfTheNextOnset() =
        runTest {
            val dispatcher = UnconfinedTestDispatcher(testScheduler)
            val repository = InMemoryConversationRepository()
            val detector = ManualVoiceTurnDetector()
            // A recognizer that never finishes after its input closes: the first
            // turn's finalization is still pending when the next onset arrives.
            val speechToText = StallingSpeechToText()
            val listener = RecordingVoiceSessionListener()
            val coordinator =
                coordinatorFor(
                    repository,
                    detector,
                    speechToText,
                    FakeLanguageModel(),
                    listener = listener,
                    dispatcher = dispatcher,
                )

            val job = launch(dispatcher) { coordinator.run(conversation) }
            detector.speech(SpeechActivity.SPEECH_STARTED)
            runCurrent()
            assertEquals(1, listener.listeningCount)
            detector.endpoint(EndpointReason.SILENCE_CAP)
            runCurrent()

            // Detection keeps draining while the first turn finalizes: the next
            // onset starts a second listening turn without waiting for STT
            // (CODE_REVIEW P1, R-0225).
            detector.speech(SpeechActivity.SPEECH_RESUMED)
            runCurrent()
            assertEquals(2, listener.listeningCount)
            job.cancelAndJoin()
        }

    private class SequentialTurnIds : () -> TurnId {
        private var count = 0

        override fun invoke(): TurnId = TurnId("v${++count}")
    }

    private class SequentialAssistantIds : () -> TurnId {
        private var count = 0

        override fun invoke(): TurnId = TurnId("a${++count}")
    }
}
