package com.voicechat.agent.ui

import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.fake.FakeMonotonicClock
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.voice.VoiceSessionController
import com.voicechat.agent.voice.VoiceSessionFactory
import com.voicechat.agent.voice.VoiceSessionListener
import com.voicechat.agent.voice.VoiceSessionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the M24 voice control on the M06 state holder.
 *
 * They use a scripted [VoiceSessionController] instead of platform capture, so
 * the dialog's mapping of live and finalized transcript text plus start/stop is
 * asserted without a device, network, microphone, or real STT. The coordinator's own pipeline is covered by
 * `voice.VoiceSessionCoordinatorTest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VoiceLoopUiIntegrationTest {
    private val selection = ProviderModelSelection(ProviderId("test-provider"), ModelId("test-model"))

    private fun newViewModel(
        repository: InMemoryConversationRepository,
        scheduler: TestCoroutineScheduler,
    ): ConversationViewModel {
        val testDispatcher = UnconfinedTestDispatcher(scheduler)
        return ConversationViewModel(
            repository = repository,
            languageModel = FakeLanguageModel(providerId = selection.providerId),
            selection = selection,
            diagnostics = NoOpDiagnosticsSink,
            clock = FakeMonotonicClock(),
            wallClock = { 1L },
            dispatcher = testDispatcher,
            scope = CoroutineScope(testDispatcher),
        )
    }

    @Test
    fun startingAVoiceSessionShowsLiveTextAndStopsCleanly() =
        runTest {
            val viewModel = newViewModel(InMemoryConversationRepository(), testScheduler)
            lateinit var controller: FakeVoiceSessionController
            viewModel.attachVoiceSession(
                VoiceSessionFactory { listener ->
                    FakeVoiceSessionController(listener).also { controller = it }
                },
            )
            viewModel.onNewConversation()
            assertTrue(
                "attaching a factory must expose voice",
                viewModel.uiState.value.dialog!!
                    .voiceAvailable,
            )

            viewModel.onStartVoice()
            advanceUntilIdle()
            assertNotNull("the session must start over a conversation", controller.startedConversation)
            assertEquals(
                VoiceSessionState.LISTENING,
                viewModel.uiState.value.dialog!!
                    .voiceState,
            )

            // The dialog renders the live provisional transcript.
            controller.provisional("hel")
            assertEquals(
                "hel",
                viewModel.uiState.value.dialog!!
                    .provisionalUserText,
            )
            assertNull(
                viewModel.uiState.value.dialog!!
                    .liveAssistantText,
            )

            viewModel.onStopVoice()
            assertEquals(1, controller.stopCount)
            assertEquals(
                VoiceSessionState.IDLE,
                viewModel.uiState.value.dialog!!
                    .voiceState,
            )
        }

    @Test
    fun aStoppedSessionsLateCallbackIsIgnored() =
        runTest {
            val viewModel = newViewModel(InMemoryConversationRepository(), testScheduler)
            lateinit var controller: FakeVoiceSessionController
            viewModel.attachVoiceSession(
                VoiceSessionFactory { listener ->
                    FakeVoiceSessionController(listener).also { controller = it }
                },
            )
            viewModel.onNewConversation()
            viewModel.onStartVoice()
            advanceUntilIdle()

            viewModel.onStopVoice()
            // A callback that arrives after the session ended must not mutate the
            // dialog for a newer state (no stale events, CODE_REVIEW P1, R-0224).
            controller.provisional("late text")
            assertNull(
                viewModel.uiState.value.dialog!!
                    .provisionalUserText,
            )
            assertEquals(
                null,
                viewModel.uiState.value.dialog!!
                    .liveAssistantText,
            )

            // A late session-state callback from the stopped session must also be
            // dropped instead of resurrecting a stale voice state.
            controller.sessionState(VoiceSessionState.FAILED)
            assertEquals(
                VoiceSessionState.IDLE,
                viewModel.uiState.value.dialog!!
                    .voiceState,
            )
        }

    @Test
    fun aSecondVoiceStartIsRefusedWhileOneIsActive() =
        runTest {
            val viewModel = newViewModel(InMemoryConversationRepository(), testScheduler)
            val controllers = mutableListOf<FakeVoiceSessionController>()
            viewModel.attachVoiceSession(
                VoiceSessionFactory { listener ->
                    FakeVoiceSessionController(listener).also { controllers += it }
                },
            )
            viewModel.onNewConversation()
            viewModel.onStartVoice()
            advanceUntilIdle()
            viewModel.onStartVoice()
            advanceUntilIdle()

            assertEquals(1, controllers.size)
        }

    @Test
    fun withoutAFactoryVoiceStaysUnavailableAndStartIsANoOp() =
        runTest {
            val viewModel = newViewModel(InMemoryConversationRepository(), testScheduler)
            viewModel.onNewConversation()
            assertFalse(
                viewModel.uiState.value.dialog!!
                    .voiceAvailable,
            )
            assertFalse(viewModel.voiceAvailable)

            viewModel.onStartVoice()
            advanceUntilIdle()
            assertEquals(
                VoiceSessionState.IDLE,
                viewModel.uiState.value.dialog!!
                    .voiceState,
            )
        }

    private class FakeVoiceSessionController(
        private val listener: VoiceSessionListener,
    ) : VoiceSessionController {
        private val _state = MutableStateFlow(VoiceSessionState.IDLE)

        override val state: StateFlow<VoiceSessionState> = _state.asStateFlow()

        var startedConversation: Conversation? = null
        var stopCount: Int = 0
            private set

        override suspend fun run(conversation: Conversation) {
            startedConversation = conversation
            _state.value = VoiceSessionState.LISTENING
            listener.onSessionState(VoiceSessionState.LISTENING)
            awaitCancellation()
        }

        override fun stop() {
            stopCount++
        }

        fun provisional(text: String) = listener.onProvisionalTranscript(TurnId("v1"), text)

        fun sessionState(state: VoiceSessionState) = listener.onSessionState(state)
    }
}
