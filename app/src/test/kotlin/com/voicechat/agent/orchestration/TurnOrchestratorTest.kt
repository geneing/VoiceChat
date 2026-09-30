package com.voicechat.agent.orchestration

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.DeterministicLanguageModel
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.FakeTextToSpeech
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end JVM tests for [TurnOrchestrator].
 *
 * They drive the real orchestration path (persistence, M12 stream consumer, M11
 * TTS accounting, M04 tracing) with deterministic fakes and a test dispatcher,
 * so no device, network, or credential is involved.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TurnOrchestratorTest {
    private val selection = ProviderModelSelection(ProviderId("test-provider"), ModelId("test-model"))
    private val conversationId = ConversationId("c1")
    private val userTurnId = TurnId("u1")

    private fun conversation() = Conversation(id = conversationId, createdAtEpochMillis = 1L, updatedAtEpochMillis = 1L)

    private fun userTurn(source: UserTurnSource = UserTurnSource.TEXT) =
        UserTurn(id = userTurnId, transcript = Transcript.final("Hi"), source = source)

    private fun request(
        repository: InMemoryConversationRepository,
        source: UserTurnSource = UserTurnSource.TEXT,
    ) = TurnRequest(
        conversation = conversation(),
        userTurn = userTurn(source),
        selection = selection,
    )

    private fun orchestrator(
        repository: InMemoryConversationRepository,
        model: com.voicechat.agent.contracts.LanguageModel,
        tts: FakeTextToSpeech? = null,
        diagnostics: com.voicechat.agent.contracts.DiagnosticsSink = NoOpDiagnosticsSink,
    ) = TurnOrchestrator(
        repository = repository,
        languageModel = model,
        textToSpeech = tts,
        diagnostics = diagnostics,
        clock = FakeMonotonicClock(),
        wallClock = { 2L },
        assistantTurnId = SequentialAssistantIds(),
    )

    @Test
    fun aManualTurnStreamsToTheUiAndPersistsTruthfully() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("Hello"), LlmStreamEvent.Delta(" there"), LlmStreamEvent.Completed()),
                )
            val live = mutableListOf<String>()
            val observer =
                object : TurnObserver {
                    override fun onLiveAssistantText(text: String) {
                        live += text
                    }
                }

            val result = orchestrator(repository, model).run(request(repository), observer)

            assertEquals(TurnPhase.COMPLETED, result.record!!.phase)
            assertEquals(listOf("Hello", "Hello there"), live)

            val persisted = repository.load(conversationId)!!
            assertEquals(2, persisted.turns.size)
            val assistant = persisted.turns[1] as AssistantTurn
            assertEquals("Hello there", assistant.generated.text)
            assertEquals(GenerationState.COMPLETED, assistant.generated.state)
            assertEquals(DeliveryState.COMPLETED, assistant.delivery.state)
            // The bounded context carries only the current user turn.
            assertEquals(listOf("Hi"), model.lastRequest!!.messages.map { it.content })
            assertEquals(
                result.conversation,
                persisted,
            )
        }

    @Test
    fun aVoiceTurnUsesTheSamePathAndRecordsItsSource() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))

            orchestrator(repository, model).run(request(repository, UserTurnSource.VOICE))

            val persisted = repository.load(conversationId)!!
            val user = persisted.turns.first() as UserTurn
            assertEquals(UserTurnSource.VOICE, user.source)
            assertNull(persisted.turns.firstOrNull { it is AssistantTurn && it.generated.text.isNotEmpty() })
        }

    @Test
    fun completeTextChunksAreSpokenAndDeliveryIsPersisted() =
        runTest {
            val repository = InMemoryConversationRepository()
            val tts = FakeTextToSpeech()
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = listOf(ScriptedStep.delta("Hello. "), ScriptedStep.delta("World."), ScriptedStep.completed()),
                )

            val result = orchestrator(repository, model, tts).run(request(repository, UserTurnSource.VOICE))

            assertEquals(TurnPhase.COMPLETED, result.record!!.phase)
            assertEquals(listOf("Hello. ", "World."), tts.spokenTexts)
            val assistant = repository.load(conversationId)!!.turns[1] as AssistantTurn
            assertEquals("Hello. World.", assistant.generated.text)
            assertEquals("Hello. World.", assistant.delivery.deliveredText)
            assertEquals(DeliveryState.COMPLETED, assistant.delivery.state)
        }

    @Test
    fun aTtsFailureIsSurfacedAndTheReplyIsNotCompleted() =
        runTest {
            val repository = InMemoryConversationRepository()
            val tts =
                FakeTextToSpeech(
                    script = { utteranceId, _ ->
                        listOf(
                            TtsEvent.Queued(utteranceId, "Hello."),
                            TtsEvent.Failed(VoiceAgentError(ErrorCode.TTS_PLAYBACK_FAILED)),
                        )
                    },
                )
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = listOf(ScriptedStep.delta("Hello."), ScriptedStep.completed()),
                )

            val result = orchestrator(repository, model, tts).run(request(repository, UserTurnSource.VOICE))

            val record = result.record!!
            assertEquals(TurnPhase.FAILED, record.phase)
            assertTrue(record.outcome is TurnOutcome.TtsFailure)
            assertEquals(VoiceAgentError(ErrorCode.TTS_PLAYBACK_FAILED), record.failure)
            val assistant = repository.load(conversationId)!!.turns[1] as AssistantTurn
            assertEquals("Hello.", assistant.generated.text)
            assertEquals("", assistant.delivery.deliveredText)
            assertEquals(DeliveryState.FAILED, assistant.delivery.state)
        }

    @Test
    fun aProviderFailurePersistsAFailedTurnWithItsPartialText() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = DeterministicLanguageModel.failingAfter("half a sen", ErrorCode.LLM_RATE_LIMITED),
                )

            val result = orchestrator(repository, model).run(request(repository))

            val record = result.record!!
            assertEquals(TurnPhase.FAILED, record.phase)
            assertEquals(
                TurnOutcome.ProviderError(VoiceAgentError(ErrorCode.LLM_RATE_LIMITED)),
                record.outcome,
            )
            val assistant = repository.load(conversationId)!!.turns[1] as AssistantTurn
            assertEquals("half a sen", assistant.generated.text)
            assertEquals(GenerationState.FAILED, assistant.generated.state)
        }

    @Test
    fun cancellingMidStreamPersistsOnlyWhatWasGeneratedAndStopsTheProvider() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps =
                        listOf(
                            ScriptedStep.delta("Hello"),
                            com.voicechat.agent.fake.ScriptedLlmStep
                                .Stall(60_000L),
                        ),
                )
            val orchestrator = orchestrator(repository, model)
            val sawDelta = CompletableDeferred<Unit>()

            val job =
                launch {
                    orchestrator.run(
                        request(repository),
                        object : TurnObserver {
                            override fun onLiveAssistantText(text: String) {
                                sawDelta.complete(Unit)
                            }
                        },
                    )
                }
            sawDelta.await()
            job.cancelAndJoin()

            assertTrue(model.cancellationCount >= 1)
            val assistant = repository.load(conversationId)!!.turns[1] as AssistantTurn
            assertEquals("Hello", assistant.generated.text)
            assertEquals(GenerationState.CANCELLED, assistant.generated.state)
            assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
            assertEquals("Hello", assistant.delivery.deliveredText)
        }

    @Test
    fun anInterruptedSpeechTurnKeepsOnlyTheDeliveredPrefix() =
        runTest {
            val repository = InMemoryConversationRepository()
            val tts =
                FakeTextToSpeech(
                    // Audible start, then the flow stays open until the turn is interrupted.
                    script = { utteranceId, text ->
                        listOf(TtsEvent.Queued(utteranceId, text), TtsEvent.Started(utteranceId, text))
                    },
                )
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps =
                        listOf(
                            ScriptedStep.delta("Hello world."),
                            com.voicechat.agent.fake.ScriptedLlmStep
                                .Stall(60_000L),
                        ),
                )
            val orchestrator = orchestrator(repository, model, tts)
            val sawDelta = CompletableDeferred<Unit>()

            val job =
                launch {
                    orchestrator.run(
                        request(repository, UserTurnSource.VOICE),
                        object : TurnObserver {
                            override fun onLiveAssistantText(text: String) {
                                sawDelta.complete(Unit)
                            }
                        },
                    )
                }
            sawDelta.await()
            orchestrator.interrupt(onsetAtNanos = 5L)
            job.join()

            val assistant = repository.load(conversationId)!!.turns[1] as AssistantTurn
            assertEquals(GenerationState.CANCELLED, assistant.generated.state)
            assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
            assertTrue(assistant.generated.text.startsWith(assistant.delivery.deliveredText))
            assertFalse(assistant.delivery.state == DeliveryState.COMPLETED)
        }

    @Test
    fun aFailedUserTurnSaveSkipsGenerationAndReportsPersistenceFailure() =
        runTest {
            val repository = InMemoryConversationRepository()
            repository.failOnNextSave = VoiceAgentError(ErrorCode.PERSISTENCE_FAILED)
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))

            val result = orchestrator(repository, model).run(request(repository))

            assertFalse(result.userTurnPersisted)
            assertEquals(0, model.streamCount)
            assertNull(repository.load(conversationId))
        }

    @Test
    fun historyAndTraceStayContentFree() =
        runTest {
            val repository = InMemoryConversationRepository()
            val sink = RecordingDiagnosticsSink()
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("secret reply"), LlmStreamEvent.Completed()),
                )

            val result = orchestrator(repository, model, diagnostics = sink).run(request(repository))

            assertEquals(TurnPhase.COMPLETED, result.record!!.phase)
            assertTrue(sink.events.isNotEmpty())
            assertTrue(sink.isSingleTrace)
            assertTrue(sink.events.none { it.attributes.containsValue("secret reply") })
            assertTrue(sink.events.none { it.attributes.containsValue("Hi") })
            assertEquals(
                "completed",
                sink.events.firstNotNullOfOrNull { it.attributes[DiagnosticAttribute.REQUEST_END_REASON] },
            )
        }

    @Test
    fun aProviderReportingADifferentModelIsRecordedInsteadOfSilentlyAccepted() =
        runTest {
            val repository = InMemoryConversationRepository()
            val sink = RecordingDiagnosticsSink()
            val model =
                DeterministicLanguageModel(
                    providerId = selection.providerId,
                    steps = listOf(ScriptedStep.completed()),
                    reportedModelId = ModelId("someone-elses-model"),
                )

            orchestrator(repository, model, diagnostics = sink).run(request(repository))

            assertEquals(
                "someone-elses-model",
                sink.events.firstNotNullOfOrNull { it.attributes[DiagnosticAttribute.REPORTED_MODEL_ID] },
            )
            // The request still named the selected provider/model; nothing rerouted.
            assertEquals(selection.modelId, model.lastRequest!!.model.modelId)
            assertEquals(selection.providerId, model.lastRequest!!.model.providerId)
        }

    private class SequentialAssistantIds : () -> TurnId {
        private var count = 0

        override fun invoke(): TurnId = TurnId("assistant-${++count}")
    }

    /** Small typed-delta helpers so the script reads like the scenario. */
    private object ScriptedStep {
        fun delta(text: String) =
            com.voicechat.agent.fake.ScriptedLlmStep
                .Emit(LlmStreamEvent.Delta(text))

        fun completed() =
            com.voicechat.agent.fake.ScriptedLlmStep
                .Emit(LlmStreamEvent.Completed())
    }
}
