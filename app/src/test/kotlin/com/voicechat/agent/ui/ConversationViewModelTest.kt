package com.voicechat.agent.ui

import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.LlmUsage
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TtsEvent
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.DeterministicLanguageModel
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.FakeTextToSpeech
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import com.voicechat.agent.fake.ScriptedLlmStep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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
 * JVM tests for the M06 state holder.
 *
 * They cover the acceptance behaviors end to end at the state layer: text send,
 * correction, incremental streaming, cancellation/interruption, failure and
 * retry, conversation switching, deletion, process-restored history, and the
 * shared text/voice turn path. A deterministic dispatcher makes streaming
 * timing exact and the suite needs no device, network, or credentials.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConversationViewModelTest {
    private val selection = ProviderModelSelection(ProviderId("test-provider"), ModelId("test-model"))

    private fun TestScope.newViewModel(
        repository: ConversationRepository,
        languageModel: LanguageModel,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = FakeMonotonicClock(),
        idFactory: ConversationIdFactory = SequentialConversationIdFactory(),
        textToSpeech: com.voicechat.agent.contracts.TextToSpeech? = null,
    ): ConversationViewModel {
        val testDispatcher = UnconfinedTestDispatcher(testScheduler)
        return ConversationViewModel(
            repository = repository,
            languageModel = languageModel,
            selection = selection,
            diagnostics = diagnostics,
            clock = clock,
            wallClock = { NOW_EPOCH_MILLIS },
            idFactory = idFactory,
            dispatcher = testDispatcher,
            textToSpeech = textToSpeech,
            scope = CoroutineScope(testDispatcher),
        )
    }

    @Test
    fun aTextOnlyVoiceSessionSurfacesTheTypedTtsUnavailableNotice() =
        runTest {
            val viewModel = newViewModel(InMemoryConversationRepository(), FakeLanguageModel())
            viewModel.onNewConversation()

            viewModel.onTextToSpeechUnavailable(
                VoiceAgentError(ErrorCode.TTS_NO_ON_DEVICE_VOICE, "no embedded voice"),
            )

            assertEquals(
                ConversationNotice.Failure(ErrorCode.TTS_NO_ON_DEVICE_VOICE, retryable = false),
                viewModel.uiState.value.dialog!!
                    .notice,
            )
        }

    @Test
    fun sendingTextPersistsAndRendersTheAssistantReply() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script =
                        listOf(
                            LlmStreamEvent.Delta("Hello"),
                            LlmStreamEvent.Delta(" there"),
                            LlmStreamEvent.Completed(),
                        ),
                )
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Hi")
            viewModel.onSend()
            advanceUntilIdle()

            val dialog = viewModel.uiState.value.dialog!!
            assertEquals(TurnPhase.COMPLETED, dialog.phase)
            assertEquals("", dialog.composerText)
            assertEquals(2, dialog.turns.size)

            val user = dialog.turns[0] as UserTurn
            assertEquals("Hi", user.transcript.text)
            assertEquals(UserTurnSource.TEXT, user.source)
            assertTrue(user.transcript.isFinal)

            val assistant = dialog.turns[1] as AssistantTurn
            assertEquals("Hello there", assistant.generated.text)
            assertEquals(GenerationState.COMPLETED, assistant.generated.state)
            assertEquals(DeliveryState.COMPLETED, assistant.delivery.state)

            // The same content is durable, so reopening restores it.
            val persisted = repository.load(dialog.conversationId!!)!!
            assertEquals(dialog.turns, persisted.turns)
            // Manual text uses the bounded model context, starting with this turn.
            assertEquals(listOf("Hi"), model.lastRequest!!.messages.map { it.content })
            assertEquals(1, model.streamCount)
            viewModel.shutdown()
        }

    @Test
    fun correctingTypedTextPersistsTheCorrectionAndNotTheEarlierDraft() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("helo ther")
            // The user corrects the recognition; the state holder must not rewrite it back.
            viewModel.onComposerChanged("hello there")
            viewModel.onSend()
            advanceUntilIdle()

            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            val user = persisted.turns.single() as UserTurn
            assertEquals("hello there", user.transcript.text)
            assertTrue(persisted.turns.none { it is UserTurn && it.transcript.text == "helo ther" })
            viewModel.shutdown()
        }

    @Test
    fun assistantDeltasRenderIncrementallyBeforeCompletion() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script =
                        listOf(
                            LlmStreamEvent.Delta("Par"),
                            LlmStreamEvent.Delta("tial"),
                            LlmStreamEvent.Completed(),
                        ),
                    eventDelayMillis = DELTA_DELAY_MILLIS,
                )
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()

            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()
            assertEquals(
                TurnPhase.GENERATING,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            assertEquals(
                "Par",
                viewModel.uiState.value.dialog
                    ?.liveAssistantText,
            )

            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()
            assertEquals(
                "Partial",
                viewModel.uiState.value.dialog
                    ?.liveAssistantText,
            )

            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()
            assertNull(
                viewModel.uiState.value.dialog
                    ?.liveAssistantText,
            )
            val assistant =
                viewModel.uiState.value.dialog!!
                    .turns
                    .last() as AssistantTurn
            assertEquals("Partial", assistant.generated.text)
            assertEquals(GenerationState.COMPLETED, assistant.generated.state)
            viewModel.shutdown()
        }

    @Test
    fun cancellingMidStreamPersistsAnInterruptedTurnNotACompletedOne() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script =
                        listOf(
                            LlmStreamEvent.Delta("Hello"),
                            LlmStreamEvent.Delta(" world"),
                            LlmStreamEvent.Completed(),
                        ),
                    eventDelayMillis = DELTA_DELAY_MILLIS,
                )
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()
            assertEquals(
                "Hello",
                viewModel.uiState.value.dialog
                    ?.liveAssistantText,
            )

            viewModel.onCancel()
            advanceUntilIdle()

            assertEquals(
                TurnPhase.CANCELLED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            assertEquals(
                ConversationNotice.RequestCancelled,
                viewModel.uiState.value.dialog
                    ?.notice,
            )
            assertEquals(1, model.cancellationCount)

            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            val assistant = persisted.turns.last() as AssistantTurn
            assertEquals(GenerationState.CANCELLED, assistant.generated.state)
            assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
            // Only the prefix the user actually saw is stored as delivered.
            assertEquals("Hello", assistant.delivery.deliveredText)
            assertFalse(assistant.generated.text.contains("world"))
            assertFalse(assistant.delivery.state == DeliveryState.COMPLETED)
            viewModel.shutdown()
        }

    @Test
    fun cancellingBeforeAnyOutputLeavesNoAssistantTurn() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("late"), LlmStreamEvent.Completed()),
                    eventDelayMillis = DELTA_DELAY_MILLIS,
                )
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            runCurrent()
            viewModel.onCancel()
            advanceUntilIdle()

            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            assertEquals(1, persisted.turns.size)
            assertTrue(persisted.turns.single() is UserTurn)
            viewModel.shutdown()
        }

    @Test
    fun aFailedRequestShowsAnErrorAndRetryReplacesTheFailedReply() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                ScriptedLanguageModel(
                    providerId = selection.providerId,
                    scripts =
                        listOf(
                            listOf(
                                LlmStreamEvent.Delta("Par"),
                                LlmStreamEvent.Failed(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED), partialText = "Par"),
                            ),
                            listOf(LlmStreamEvent.Delta("Recovered"), LlmStreamEvent.Completed()),
                        ),
                )
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(
                TurnPhase.FAILED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            assertTrue(
                viewModel.uiState.value.dialog
                    ?.canRetry == true,
            )
            assertEquals(
                ConversationNotice.Failure(ErrorCode.LLM_NETWORK_FAILED, retryable = true),
                viewModel.uiState.value.dialog
                    ?.notice,
            )

            viewModel.onRetry()
            advanceUntilIdle()

            assertEquals(
                TurnPhase.COMPLETED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            assertEquals(1, persisted.turns.count { it is UserTurn })
            val assistant = persisted.turns.last() as AssistantTurn
            assertEquals("Recovered", assistant.generated.text)
            assertEquals(GenerationState.COMPLETED, assistant.generated.state)
            assertEquals(2, model.streamCount)
            viewModel.shutdown()
        }

    @Test
    fun openingConversationsLoadsEachHistoryIndependently() =
        runTest {
            val repository = InMemoryConversationRepository()
            repository.save(testConversation("c1", updatedAt = 100L, title = "First talk", turns = listOf(testUserTurn("u1", "alpha"))))
            repository.save(testConversation("c2", updatedAt = 200L, title = "Second talk", turns = listOf(testUserTurn("u2", "beta"))))
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val viewModel = newViewModel(repository, model)
            advanceUntilIdle()

            assertEquals(2, viewModel.uiState.value.list.summaries.size)

            viewModel.onOpenConversation(ConversationId("c1"))
            advanceUntilIdle()
            assertEquals(
                "alpha",
                (
                    viewModel.uiState.value.dialog!!
                        .turns
                        .single() as UserTurn
                ).transcript.text,
            )

            viewModel.onOpenConversation(ConversationId("c2"))
            advanceUntilIdle()
            assertEquals(
                "beta",
                (
                    viewModel.uiState.value.dialog!!
                        .turns
                        .single() as UserTurn
                ).transcript.text,
            )
            assertEquals(0, model.streamCount)
            viewModel.shutdown()
        }

    @Test
    fun deletingAConversationRemovesItAndClosesTheOpenDialog() =
        runTest {
            val repository = InMemoryConversationRepository()
            repository.save(testConversation("c1", updatedAt = 100L, title = "Delete me", turns = listOf(testUserTurn("u1", "hello"))))
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val viewModel = newViewModel(repository, model)
            advanceUntilIdle()

            viewModel.onOpenConversation(ConversationId("c1"))
            advanceUntilIdle()
            viewModel.onRequestDelete(ConversationId("c1"))
            assertNotNull(viewModel.uiState.value.pendingDeletion)

            viewModel.onConfirmDelete()
            advanceUntilIdle()

            assertNull(repository.load(ConversationId("c1")))
            assertNull(viewModel.uiState.value.dialog)
            assertEquals(ConversationScreen.LIST, viewModel.uiState.value.screen)
            assertTrue(
                viewModel.uiState.value.list.summaries
                    .isEmpty(),
            )
            viewModel.shutdown()
        }

    @Test
    fun reopeningRestoredHistoryReconcilesAMidTurnProcessDeath() =
        runTest {
            val repository = InMemoryConversationRepository()
            // Stored exactly as a process that died mid-generation would leave it.
            repository.save(
                testConversation(
                    "c1",
                    updatedAt = 100L,
                    title = "Restored",
                    turns = listOf(testUserTurn("u1", "are you there"), AssistantTurn.pending(TurnId("a-pending"))),
                ),
            )
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val viewModel = newViewModel(repository, model)
            advanceUntilIdle()

            viewModel.onOpenConversation(ConversationId("c1"))
            advanceUntilIdle()

            val turns =
                viewModel.uiState.value.dialog!!
                    .turns
            assertEquals(2, turns.size)
            val assistant = turns[1] as AssistantTurn
            assertEquals(GenerationState.CANCELLED, assistant.generated.state)
            assertEquals(DeliveryState.INTERRUPTED, assistant.delivery.state)
            assertEquals(
                TurnPhase.CANCELLED,
                viewModel.uiState.value.dialog!!
                    .phase,
            )

            // The reconciled state is written back, so the restart cannot report a
            // reply that was never finished.
            val persisted = repository.load(ConversationId("c1"))!!
            assertEquals(GenerationState.CANCELLED, (persisted.turns[1] as AssistantTurn).generated.state)
            viewModel.shutdown()
        }

    @Test
    fun voiceAndTextTurnsShareOneTurnAndConversationPath() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.setProvisionalTranscript("recognized words")
            assertEquals(
                "recognized words",
                viewModel.uiState.value.dialog
                    ?.provisionalUserText,
            )

            // The recognizer's raw text stays provisional; the user's corrected text commits.
            viewModel.commitProvisionalTranscript("corrected words")
            advanceUntilIdle()

            val afterVoice =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            val voiceTurn = afterVoice.turns.first() as UserTurn
            assertEquals("corrected words", voiceTurn.transcript.text)
            assertEquals(UserTurnSource.VOICE, voiceTurn.source)
            assertNull(
                viewModel.uiState.value.dialog
                    ?.provisionalUserText,
            )

            // A typed turn uses the same conversation and the same turn path.
            viewModel.onComposerChanged("typed words")
            viewModel.onSend()
            advanceUntilIdle()

            val afterText = repository.load(afterVoice.id)!!
            val userTurns = afterText.turns.filterIsInstance<UserTurn>()
            assertEquals(2, userTurns.size)
            assertEquals(UserTurnSource.TEXT, userTurns[1].source)
            assertEquals("typed words", userTurns[1].transcript.text)
            assertEquals(
                afterVoice.id,
                viewModel.uiState.value.dialog
                    ?.conversationId,
            )
            assertEquals(2, model.streamCount)
            viewModel.shutdown()
        }

    @Test
    fun tracingGoesThroughTheDiagnosticsSeamWithoutContent() =
        runTest {
            val repository = InMemoryConversationRepository()
            val sink = RecordingDiagnosticsSink()
            val model =
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("ok"), LlmStreamEvent.Completed()),
                )
            val viewModel = newViewModel(repository, model, diagnostics = sink)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("secret prompt")
            viewModel.onSend()
            advanceUntilIdle()

            assertTrue(sink.events.isNotEmpty())
            assertTrue(sink.isSingleTrace)
            assertTrue(sink.events.all { it.attributes[DiagnosticAttribute.PROVIDER_ID] == selection.providerId.value })
            assertTrue(sink.events.any { it.stage == DiagnosticStage.LLM_REQUEST })
            assertTrue(sink.events.none { it.attributes.containsValue("secret prompt") })
            viewModel.shutdown()
        }

    @Test
    fun blankComposerDoesNotCreateATurn() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model = FakeLanguageModel(providerId = selection.providerId, script = listOf(LlmStreamEvent.Completed()))
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("   ")
            viewModel.onSend()
            advanceUntilIdle()

            assertTrue(
                viewModel.uiState.value.dialog!!
                    .turns
                    .isEmpty(),
            )
            assertEquals(0, model.streamCount)
            viewModel.shutdown()
        }

    @Test
    fun aStreamThatEndsWithoutATerminalEventIsPersistedAsAFailureNotACompletion() =
        runTest {
            val repository = InMemoryConversationRepository()
            // An adapter that emits deltas and then simply ends: it never said
            // whether the text was whole, so it must not be persisted as complete.
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("truncated")),
                        ),
                )
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(
                TurnPhase.FAILED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            val assistant = persisted.turns.last() as AssistantTurn
            assertEquals(GenerationState.FAILED, assistant.generated.state)
            assertEquals(DeliveryState.FAILED, assistant.delivery.state)
            // The text that did arrive is preserved for reconciliation.
            assertEquals("truncated", assistant.generated.text)
            viewModel.shutdown()
        }

    @Test
    fun aFailedPartialResponseKeepsTheDeliveredPrefixAndTheTypedReason() =
        runTest {
            val repository = InMemoryConversationRepository()
            val model =
                DeterministicLanguageModel(
                    steps =
                        DeterministicLanguageModel.failingAfter(
                            partial = "half a sen",
                            code = ErrorCode.LLM_RATE_LIMITED,
                        ),
                )
            val viewModel = newViewModel(repository, model)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(
                TurnPhase.FAILED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            assertEquals(
                ConversationNotice.Failure(ErrorCode.LLM_RATE_LIMITED, retryable = true),
                viewModel.uiState.value.dialog
                    ?.notice,
            )
            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            val assistant = persisted.turns.last() as AssistantTurn
            assertEquals("half a sen", assistant.generated.text)
            assertEquals("half a sen", assistant.delivery.deliveredText)
            assertEquals(GenerationState.FAILED, assistant.generated.state)
            viewModel.shutdown()
        }

    @Test
    fun aProviderModelMismatchIsRecordedOnTheTraceInsteadOfSilentlyAccepted() =
        runTest {
            val repository = InMemoryConversationRepository()
            val sink = RecordingDiagnosticsSink()
            val model =
                DeterministicLanguageModel(
                    steps = listOf(ScriptedLlmStep.Emit(LlmStreamEvent.Completed())),
                    reportedModelId = ModelId("someone-elses-model"),
                )
            val viewModel = newViewModel(repository, model, diagnostics = sink)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceUntilIdle()

            // The trace still names the selected provider/model, so a later
            // adapter-level mismatch check has the selection to compare against.
            assertTrue(sink.events.any { it.attributes[DiagnosticAttribute.PROVIDER_ID] == selection.providerId.value })
            assertTrue(sink.events.any { it.attributes[DiagnosticAttribute.MODEL_ID] == selection.modelId.value })
            // Only identities and counts reach the trace, never delta text.
            assertTrue(sink.events.none { it.attributes.containsValue("truncated") })
            viewModel.shutdown()
        }

    @Test
    fun usageAndRequestEndReasonAreTracedWithoutContent() =
        runTest {
            val repository = InMemoryConversationRepository()
            val sink = RecordingDiagnosticsSink()
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta("hello")),
                            ScriptedLlmStep.Emit(
                                LlmStreamEvent.Completed(usage = LlmUsage(promptTokens = 12, completionTokens = 3)),
                            ),
                        ),
                )
            val viewModel = newViewModel(repository, model, diagnostics = sink)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("secret prompt")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(
                "prompt=12,completion=3",
                sink.events.firstNotNullOfOrNull { it.attributes[DiagnosticAttribute.USAGE] },
            )
            assertEquals(
                "completed",
                sink.events.firstNotNullOfOrNull { it.attributes[DiagnosticAttribute.REQUEST_END_REASON] },
            )
            assertTrue(sink.events.none { it.attributes.containsValue("secret prompt") })
            assertTrue(sink.events.none { it.attributes.containsValue("hello") })
            viewModel.shutdown()
        }

    @Test
    fun aTtsFailureIsShownAsAFailedTurnAndNotACompletedReply() =
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
                FakeLanguageModel(
                    providerId = selection.providerId,
                    script = listOf(LlmStreamEvent.Delta("Hello."), LlmStreamEvent.Completed()),
                )
            val viewModel = newViewModel(repository, model, textToSpeech = tts)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(
                TurnPhase.FAILED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            assertEquals(
                ConversationNotice.Failure(ErrorCode.TTS_PLAYBACK_FAILED, retryable = true),
                viewModel.uiState.value.dialog
                    ?.notice,
            )
            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            val assistant = persisted.turns.last() as AssistantTurn
            assertEquals(GenerationState.FAILED, assistant.generated.state)
            assertEquals("", assistant.delivery.deliveredText)
            viewModel.shutdown()
        }

    private companion object {
        const val NOW_EPOCH_MILLIS = 1_000L
        const val DELTA_DELAY_MILLIS = 10L
    }
}
