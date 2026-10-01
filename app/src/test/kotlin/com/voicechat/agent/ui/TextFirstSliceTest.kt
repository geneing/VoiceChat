package com.voicechat.agent.ui

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.CredentialStatus
import com.voicechat.agent.credentials.CredentialStore
import com.voicechat.agent.credentials.CredentialStoreOutcome
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.context.ModelContextBuilder
import com.voicechat.agent.fake.DeterministicLanguageModel
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import com.voicechat.agent.fake.ScriptedLlmStep
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderLanguageModelFactory
import com.voicechat.agent.providers.RegisteredProviderLanguageModelFactory
import com.voicechat.agent.providers.opencodego.OpenCodeGoFixtures
import com.voicechat.agent.providers.opencodego.OpenCodeGoLanguageModel
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.remote.scriptedResponse
import com.voicechat.agent.settings.VoiceSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M23 acceptance: the text-first vertical slice.
 *
 * These tests drive the real M06 state holder over the **real** M17 OpenCode Go
 * adapter (with a recorded SSE fixture behind a fake HTTP engine), the real M05
 * context builder and repository, and the M13 credential-store seam. No device,
 * network, or real credential is involved: the only provider "connection" is the
 * fixture `FakeHttpStreamingEngine`.
 *
 * They prove the full lifecycle (send → streamed deltas → visible result →
 * persisted history), cancellation, retry, a typed provider failure, that the
 * persisted M22 selection actually drives the adapter/identity a turn uses
 * (R-0103), that the request carries the bounded M05 context (not the whole
 * history), and that the M04 trace stays content-free across the turn.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TextFirstSliceTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val goModel = "glm-5.3-flash"
    private val secret = "oc-go-DO-NOT-LEAK-0123456789"

    // --- full lifecycle ------------------------------------------------------

    @Test
    fun aFixtureBackedProviderTurnStreamsPersistsAndRendersTheReply() =
        runTest {
            val engine =
                FakeHttpStreamingEngine {
                    scriptedResponse(body = OpenCodeGoFixtures.text("chat_normal.sse"))
                }
            val repository = InMemoryConversationRepository()
            val viewModel = newViewModel(MutableStateFlow(goSettings(goModel)), engine = engine, repository = repository)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Hello provider")
            viewModel.onSend()
            advanceUntilIdle()

            val dialog = viewModel.uiState.value.dialog!!
            assertEquals(TurnPhase.COMPLETED, dialog.phase)
            // The active selection is carried as the turn identity (no warning card).
            assertEquals("OpenCode Go", dialog.provider.providerDisplayName)
            assertEquals(goModel, dialog.provider.modelId)

            val assistant = dialog.turns.last() as AssistantTurn
            assertEquals("Hello, world", assistant.generated.text)
            assertEquals(GenerationState.COMPLETED, assistant.generated.state)

            // The same content is durable: reopening restores it.
            val persisted = repository.load(dialog.conversationId!!)!!
            assertEquals(dialog.turns, persisted.turns)

            // The provider received the real request, on the documented endpoint,
            // with a conversation-scoped session id (R-0132).
            val sent = engine.requests.single()
            assertTrue(sent.url.endsWith("/chat/completions"))
            assertEquals(
                "voicechat-${dialog.conversationId!!.value}",
                sent.headers.first { it.name == OpenCodeGoLanguageModel.SESSION_HEADER }.value,
            )
            viewModel.shutdown()
        }

    @Test
    fun streamedDeltasAreVisibleBeforeTheReplyCompletes() =
        runTest {
            val model =
                FakeLanguageModel(
                    providerId = KnownProviders.OPENCODE_GO,
                    script =
                        listOf(
                            LlmStreamEvent.Delta("Hel"),
                            LlmStreamEvent.Delta("lo"),
                            LlmStreamEvent.Completed(),
                        ),
                    eventDelayMillis = DELTA_DELAY_MILLIS,
                )
            val viewModel = newViewModel(MutableStateFlow(goSettings(goModel)), factory = { _, _, _ -> model })

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()

            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()
            assertEquals(
                "Hel",
                viewModel.uiState.value.dialog
                    ?.liveAssistantText,
            )
            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()
            assertEquals(
                "Hello",
                viewModel.uiState.value.dialog
                    ?.liveAssistantText,
            )
            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()

            assertEquals(
                TurnPhase.COMPLETED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            assertEquals(
                "Hello",
                (
                    viewModel.uiState.value.dialog!!
                        .turns
                        .last() as AssistantTurn
                ).generated.text,
            )
            viewModel.shutdown()
        }

    // --- cancel and retry ----------------------------------------------------

    @Test
    fun cancellingMidStreamPersistsAnInterruptedTurnNotACompletedOne() =
        runTest {
            val model =
                FakeLanguageModel(
                    providerId = KnownProviders.OPENCODE_GO,
                    script =
                        listOf(
                            LlmStreamEvent.Delta("Hel"),
                            LlmStreamEvent.Delta("lo"),
                            LlmStreamEvent.Completed(),
                        ),
                    eventDelayMillis = DELTA_DELAY_MILLIS,
                )
            val repository = InMemoryConversationRepository()
            val viewModel =
                newViewModel(
                    MutableStateFlow(goSettings(goModel)),
                    repository = repository,
                    factory = { _, _, _ -> model },
                )

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceTimeBy(DELTA_DELAY_MILLIS + 1)
            runCurrent()
            assertEquals(
                "Hel",
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
            val assistant =
                repository
                    .load(
                        viewModel.uiState.value.dialog!!
                            .conversationId!!,
                    )!!
                    .turns
                    .last() as AssistantTurn
            assertEquals(GenerationState.CANCELLED, assistant.generated.state)
            assertEquals("Hel", assistant.delivery.deliveredText)
            viewModel.shutdown()
        }

    @Test
    fun retryAfterAFailureReplacesTheFailedReplyWithoutDuplicatingTheUserTurn() =
        runTest {
            val model =
                ScriptedLanguageModel(
                    providerId = KnownProviders.OPENCODE_GO,
                    scripts =
                        listOf(
                            listOf(
                                LlmStreamEvent.Delta("half"),
                                LlmStreamEvent.Failed(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED), partialText = "half"),
                            ),
                            listOf(LlmStreamEvent.Delta("Recovered"), LlmStreamEvent.Completed()),
                        ),
                )
            val repository = InMemoryConversationRepository()
            val viewModel =
                newViewModel(
                    MutableStateFlow(goSettings(goModel)),
                    repository = repository,
                    factory = { _, _, _ -> model },
                )

            viewModel.onNewConversation()
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceUntilIdle()
            assertEquals(
                TurnPhase.FAILED,
                viewModel.uiState.value.dialog
                    ?.phase,
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
            assertEquals("Recovered", (persisted.turns.last() as AssistantTurn).generated.text)
            assertEquals(2, model.streamCount)
            viewModel.shutdown()
        }

    // --- typed provider failure ----------------------------------------------

    @Test
    fun aFixtureProviderFailureIsTypedAndNeverPersistedAsASuccess() =
        runTest {
            val engine =
                FakeHttpStreamingEngine {
                    scriptedResponse(body = OpenCodeGoFixtures.text("chat_error.sse"))
                }
            val repository = InMemoryConversationRepository()
            val viewModel = newViewModel(MutableStateFlow(goSettings(goModel)), engine = engine, repository = repository)

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
            // Nothing was generated, so no phantom assistant turn is persisted: the
            // failure is visible, but history is not inflated with an empty reply.
            val persisted =
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )!!
            assertEquals(1, persisted.turns.size)
            assertTrue(persisted.turns.single() is UserTurn)
            viewModel.shutdown()
        }

    // --- selection drives the turn path (R-0103) -----------------------------

    @Test
    fun changingThePersistedSelectionChangesTheAdapterAndRequestIdentity() =
        runTest {
            val engine =
                FakeHttpStreamingEngine { request ->
                    val fixture = if (request.url.endsWith("/responses")) "responses_normal.sse" else "chat_normal.sse"
                    scriptedResponse(body = OpenCodeGoFixtures.text(fixture))
                }
            val recording = RecordingProviderFactory(realFactory(engine))
            val settings = MutableStateFlow(goSettings(goModel))
            val sink = RecordingDiagnosticsSink()
            val viewModel = newViewModel(settings, factory = recording, diagnostics = sink)

            viewModel.onNewConversation()
            viewModel.onComposerChanged("one")
            viewModel.onSend()
            advanceUntilIdle()

            // The user changes the model in Settings; the same dialog now resolves
            // the other protocol family against the same conversation.
            settings.value = goSettings("gpt-5.6-luna")
            runCurrent()
            assertEquals(
                "gpt-5.6-luna",
                viewModel.uiState.value.dialog
                    ?.provider
                    ?.modelId,
            )

            viewModel.onComposerChanged("two")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(listOf(goModel, "gpt-5.6-luna"), recording.selections.map { it.modelId.value })
            assertTrue(engine.requests[0].url.endsWith("/chat/completions"))
            assertTrue(engine.requests[1].url.endsWith("/responses"))
            // The trace names the provider/model that actually served each turn.
            assertTrue(sink.events.any { it.attributes[DiagnosticAttribute.MODEL_ID] == "gpt-5.6-luna" })
            viewModel.shutdown()
        }

    @Test
    fun withNoProviderSelectedTheTurnStaysHonestlyNotConfigured() =
        runTest {
            val model = FakeLanguageModel(providerId = KnownProviders.OPENCODE_GO, script = listOf(LlmStreamEvent.Completed()))
            // The app's default is a real provider; this exercises the genuinely
            // unselected case a user can reach by choosing "no provider".
            val unselected = VoiceSettings(sttLocaleLanguageTag = VoiceSettings.DEFAULT_LANGUAGE_TAG)
            val viewModel = newViewModel(MutableStateFlow(unselected), factory = { _, _, _ -> model })

            viewModel.onNewConversation()
            assertFalse(
                viewModel.uiState.value.dialog!!
                    .provider.hasSelection,
            )
            viewModel.onComposerChanged("Q")
            viewModel.onSend()
            advanceUntilIdle()

            assertEquals(
                TurnPhase.FAILED,
                viewModel.uiState.value.dialog
                    ?.phase,
            )
            assertEquals(
                ConversationNotice.Failure(ErrorCode.LLM_NOT_CONFIGURED, retryable = false),
                viewModel.uiState.value.dialog
                    ?.notice,
            )
            // The configured factory is never consulted when nothing is selected.
            assertEquals(0, model.streamCount)
            viewModel.shutdown()
        }

    // --- bounded context and privacy-safe trace ------------------------------

    @Test
    fun theRequestUsesTheBoundedContextNotTheWholeStoredHistory() =
        runTest {
            val repository = InMemoryConversationRepository()
            val turns = (1..30).map { testUserTurn("u$it", "message number $it") }
            repository.save(testConversation("c1", updatedAt = 1L, title = "Long", turns = turns))
            val model = FakeLanguageModel(providerId = KnownProviders.OPENCODE_GO, script = listOf(LlmStreamEvent.Completed()))
            val viewModel =
                newViewModel(
                    MutableStateFlow(goSettings(goModel)),
                    repository = repository,
                    factory = { _, _, _ -> model },
                )
            advanceUntilIdle()

            viewModel.onOpenConversation(ConversationId("c1"))
            advanceUntilIdle()
            viewModel.onComposerChanged("newest question")
            viewModel.onSend()
            advanceUntilIdle()

            val request = model.lastRequest!!
            assertTrue(request.messages.size <= ModelContextBuilder.DEFAULT_MAX_MESSAGES)
            assertTrue(request.characterCount <= ModelContextBuilder.DEFAULT_MAX_CHARACTERS)
            // The newest request is present; the oldest history was not resent.
            assertEquals("newest question", request.messages.last().content)
            assertFalse(request.messages.any { it.content == "message number 1" })
            viewModel.shutdown()
        }

    @Test
    fun theWholeTurnTraceIsPrivacySafe() =
        runTest {
            val prompt = "my private medical question"
            val reply = "a private assistant answer"
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta(reply)),
                            ScriptedLlmStep.Emit(LlmStreamEvent.Completed()),
                        ),
                )
            val sink = RecordingDiagnosticsSink()
            val viewModel =
                newViewModel(
                    MutableStateFlow(goSettings(goModel)),
                    factory = { _, _, _ -> model },
                    diagnostics = sink,
                )

            viewModel.onNewConversation()
            viewModel.onComposerChanged(prompt)
            viewModel.onSend()
            advanceUntilIdle()

            assertTrue(sink.events.isNotEmpty())
            assertTrue(sink.isSingleTrace)
            assertTrue(sink.events.none { event -> event.attributes.values.any { it == prompt } })
            assertTrue(sink.events.none { event -> event.attributes.values.any { it == reply } })
            viewModel.shutdown()
        }

    // --- harness -------------------------------------------------------------

    private fun goSettings(model: String): VoiceSettings =
        VoiceSettings(
            llmProviderId = KnownProviders.OPENCODE_GO,
            llmModelId = ModelId(model),
            llmAuthMethod = AuthMethod.API_KEY,
        )

    /**
     * The real registry-driven factory over a fake engine and a fixed credential.
     * The credential is a test-only constant; the adapter still loads it through
     * the M13 [CredentialStore] seam, so no code path skips the store.
     */
    private fun realFactory(engine: FakeHttpStreamingEngine): ProviderLanguageModelFactory =
        RegisteredProviderLanguageModelFactory(
            registry = registry,
            credentials = FixedCredentialStore(Credential(KnownProviders.OPENCODE_GO, CredentialKind.API_KEY, secret)),
            transport = RemoteTransport(engine),
        )

    private fun TestScope.newViewModel(
        settings: MutableStateFlow<VoiceSettings>,
        engine: FakeHttpStreamingEngine? = null,
        repository: InMemoryConversationRepository = InMemoryConversationRepository(),
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        factory: ProviderLanguageModelFactory =
            engine?.let(::realFactory) ?: realFactory(FakeHttpStreamingEngine { scriptedResponse() }),
    ): ConversationViewModel {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        return ConversationViewModel(
            repository = repository,
            languageModel = NotConfiguredLanguageModel(),
            selection = ConversationDefaults.selection,
            diagnostics = diagnostics,
            clock = FakeMonotonicClock(),
            wallClock = { NOW_EPOCH_MILLIS },
            idFactory = SequentialConversationIdFactory(),
            dispatcher = dispatcher,
            settingsFlow = settings,
            providerRegistry = registry,
            providerFactory = factory,
            scope = CoroutineScope(dispatcher),
        )
    }

    /** Records which selections the factory was asked to build. */
    private class RecordingProviderFactory(
        private val delegate: ProviderLanguageModelFactory,
    ) : ProviderLanguageModelFactory {
        val selections = mutableListOf<ProviderModelSelection>()

        override fun create(
            selection: ProviderModelSelection,
            sessionHint: String?,
            configuredServerUrl: String?,
        ): LanguageModel? {
            selections += selection
            return delegate.create(selection, sessionHint, configuredServerUrl)
        }
    }

    /** A [CredentialStore] holding one fixed credential for one provider. */
    private class FixedCredentialStore(
        private val credential: Credential,
    ) : CredentialStore {
        override suspend fun status(providerId: ProviderId): CredentialStatus =
            if (providerId == credential.providerId) {
                CredentialStatus.Stored(providerId, credential.kind)
            } else {
                CredentialStatus.NotStored
            }

        override suspend fun store(credential: Credential): CredentialStoreOutcome = CredentialStoreOutcome.Success

        override suspend fun remove(providerId: ProviderId): CredentialStoreOutcome = CredentialStoreOutcome.NotStored

        override suspend fun load(providerId: ProviderId): Credential? = credential.takeIf { providerId == credential.providerId }
    }

    private companion object {
        const val NOW_EPOCH_MILLIS = 1_000L
        const val DELTA_DELAY_MILLIS = 10L
    }
}
