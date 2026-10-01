package com.voicechat.agent.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.ui.ConversationTestTags.CANCEL
import com.voicechat.agent.ui.ConversationTestTags.COMPOSER
import com.voicechat.agent.ui.ConversationTestTags.CONFIRM_DELETE
import com.voicechat.agent.ui.ConversationTestTags.DELETE_CONVERSATION
import com.voicechat.agent.ui.ConversationTestTags.DRAWER
import com.voicechat.agent.ui.ConversationTestTags.LOADING
import com.voicechat.agent.ui.ConversationTestTags.NEW_CONVERSATION
import com.voicechat.agent.ui.ConversationTestTags.RETRY
import com.voicechat.agent.ui.ConversationTestTags.SEND
import com.voicechat.agent.ui.ConversationTestTags.conversationRow
import com.voicechat.agent.ui.ConversationTestTags.deleteRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose UI tests for the M06 conversation surface.
 *
 * They run on the JVM under Robolectric (no device): the real
 * [ConversationViewModel] is bound to the real [ConversationApp] over an
 * in-memory repository and a fake language model, so a click exercises the same
 * send/correct/stream/switch/delete path the app uses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ConversationAppUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val selection = ProviderModelSelection(ProviderId("test-provider"), ModelId("test-model"))
    private val scheduler = TestCoroutineScheduler()
    private lateinit var scope: CoroutineScope
    private lateinit var repository: InMemoryConversationRepository
    private lateinit var viewModel: ConversationViewModel
    private val activeViewModel = mutableStateOf<ConversationViewModel?>(null)

    @Before
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
        repository = InMemoryConversationRepository()
        viewModel = createViewModel(FakeLanguageModel(providerId = selection.providerId, script = REPLY_SCRIPT))
    }

    @After
    fun tearDown() {
        viewModel.shutdown()
        scope.cancel()
    }

    private fun createViewModel(languageModel: LanguageModel): ConversationViewModel =
        ConversationViewModel(
            repository = repository,
            languageModel = languageModel,
            selection = selection,
            dispatcher = UnconfinedTestDispatcher(scheduler),
            idFactory = SequentialConversationIdFactory(),
            wallClock = { 1_000L },
            scope = scope,
        )

    private fun setConversationApp() {
        activeViewModel.value = viewModel
        composeRule.setContent {
            val vm = requireNotNull(activeViewModel.value)
            val state by vm.uiState.collectAsState()
            ConversationApp(state = state, actions = vm)
        }
        composeRule.waitForIdle()
    }

    /** Stands in for a process restart: a fresh state holder over the same durable repository. */
    private fun restartWithANewViewModel() {
        viewModel.shutdown()
        viewModel = createViewModel(FakeLanguageModel(providerId = selection.providerId, script = REPLY_SCRIPT))
        activeViewModel.value = viewModel
        composeRule.waitForIdle()
    }

    private fun openNewConversation() {
        composeRule.onNodeWithTag(NEW_CONVERSATION).performClick()
        composeRule.waitForIdle()
    }

    /** Opens the history/settings drawer, which is where persisted conversations live. */
    private fun openDrawer() {
        composeRule.onNodeWithTag(DRAWER).performClick()
        composeRule.waitForIdle()
    }

    private fun typeMessage(text: String) {
        composeRule.onNodeWithTag(COMPOSER).performClick()
        composeRule.onNodeWithTag(COMPOSER).performTextInput(text)
    }

    @Test
    fun typingAndSendingRendersTheReplyAndPersistsTheTurn() {
        setConversationApp()
        openNewConversation()
        typeMessage("Hello")
        composeRule.onNodeWithTag(SEND).performClick()
        composeRule.waitForIdle()

        // "Hello" also becomes the auto-title, so it appears in the top bar and the bubble.
        composeRule.onAllNodesWithText("Hello").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Echo reply").assertIsDisplayed()

        val conversationId =
            viewModel.uiState.value.dialog!!
                .conversationId!!
        val persisted = runBlocking { repository.load(conversationId) }!!
        assertEquals(2, persisted.turns.size)
        assertTrue(persisted.turns[0] is UserTurn)
        assertTrue(persisted.turns[1] is AssistantTurn)
    }

    @Test
    fun correctingTheDraftBeforeSendingPersistsTheCorrection() {
        setConversationApp()
        openNewConversation()
        typeMessage("helo ther")
        composeRule.onNodeWithTag(COMPOSER).performTextClearance()
        composeRule.onNodeWithTag(COMPOSER).performTextInput("hello there")
        composeRule.onNodeWithTag(SEND).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("hello there").onFirst().assertIsDisplayed()
        composeRule.onAllNodesWithText("helo ther").assertCountEquals(0)

        val persisted =
            runBlocking {
                repository.load(
                    viewModel.uiState.value.dialog!!
                        .conversationId!!,
                )
            }!!
        val user = persisted.turns.first() as UserTurn
        assertEquals("hello there", user.transcript.text)
    }

    @Test
    fun streamingAssistantTextIsRenderedWithAnExplicitGeneratingState() {
        composeRule.setContent {
            ConversationApp(
                state =
                    ConversationUiState(
                        dialog =
                            ConversationDialogState(
                                conversationId = ConversationId("c1"),
                                title = "Streaming",
                                phase = TurnPhase.GENERATING,
                                liveAssistantText = "Hello wor",
                            ),
                    ),
                actions = NoOpConversationActions,
            )
        }

        composeRule.onNodeWithText("Hello wor").assertIsDisplayed()
        // The generating state is visible and interruptible while streaming.
        composeRule.onNodeWithTag(CANCEL).assertIsDisplayed()
        composeRule.onAllNodesWithText("Generating…").onFirst().assertIsDisplayed()
    }

    @Test
    fun switchingConversationsFromTheDrawerShowsEachHistory() {
        runBlocking {
            repository.save(testConversation("c1", updatedAt = 100L, title = "First talk", turns = listOf(testUserTurn("u1", "alpha"))))
            repository.save(testConversation("c2", updatedAt = 200L, title = "Second talk", turns = listOf(testUserTurn("u2", "beta"))))
        }
        setConversationApp()

        openDrawer()
        composeRule.onNodeWithTag(conversationRow("c1")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("alpha").assertIsDisplayed()

        // The drawer closes on selection, so the second switch reopens it.
        openDrawer()
        composeRule.onNodeWithTag(conversationRow("c2")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("beta").assertIsDisplayed()
    }

    @Test
    fun deletingAConversationAsksForConfirmationThenRemovesIt() {
        runBlocking {
            repository.save(testConversation("c1", updatedAt = 100L, title = "Delete me", turns = listOf(testUserTurn("u1", "hello"))))
        }
        setConversationApp()

        openDrawer()
        composeRule.onNodeWithTag(deleteRow("c1")).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithText("Delete conversation?").assertIsDisplayed()

        composeRule.onNodeWithTag(CONFIRM_DELETE).performClick()
        composeRule.waitForIdle()

        assertNull(runBlocking { repository.load(ConversationId("c1")) })
        composeRule.onNodeWithText("Delete me").assertDoesNotExist()
    }

    @Test
    fun restoredHistoryShowsCommittedAndInterruptedTurns() {
        val interrupted =
            testAssistantTurn(
                id = "a1",
                generated = "partial answer",
                delivered = "partial",
                generationState = GenerationState.CANCELLED,
                deliveryState = DeliveryState.INTERRUPTED,
            )
        runBlocking {
            repository.save(
                testConversation("c1", updatedAt = 100L, title = "Restored", turns = listOf(testUserTurn("u1", "hello"), interrupted)),
            )
        }
        setConversationApp()

        openDrawer()
        composeRule.onNodeWithTag(conversationRow("c1")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("hello").assertIsDisplayed()
        // Only the delivered prefix is shown, and the cut-off state is explicit.
        composeRule.onNodeWithText("partial").assertIsDisplayed()
        composeRule.onAllNodesWithText("Interrupted").onFirst().assertIsDisplayed()
    }

    @Test
    fun historySurvivesAViewModelRestartAndReopensInTheUi() {
        setConversationApp()
        openNewConversation()
        typeMessage("persisted question")
        composeRule.onNodeWithTag(SEND).performClick()
        composeRule.waitForIdle()
        val conversationId =
            viewModel.uiState.value.dialog!!
                .conversationId!!

        // A fresh state holder over the same repository stands in for a restart.
        restartWithANewViewModel()
        openDrawer()
        composeRule.onNodeWithTag(conversationRow(conversationId.value)).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("persisted question").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("Echo reply").assertIsDisplayed()
    }

    @Test
    fun loadingStateExposesAnAccessibleLabel() {
        composeRule.setContent {
            ConversationApp(
                state = ConversationUiState(list = ConversationListState(isLoading = true)),
                actions = NoOpConversationActions,
            )
        }
        composeRule.onNodeWithTag(LOADING).assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Loading conversations").assertIsDisplayed()
    }

    @Test
    fun anUnconfiguredModelSurfacesARecoverableErrorNotice() {
        viewModel = createViewModel(NotConfiguredLanguageModel())
        setConversationApp()
        openNewConversation()
        typeMessage("Hello")
        composeRule.onNodeWithTag(SEND).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithText("No language model is configured yet.").assertIsDisplayed()
        composeRule.onNodeWithTag(RETRY).assertIsDisplayed()
        assertEquals(
            ErrorCode.LLM_NOT_CONFIGURED,
            (
                viewModel.uiState.value.dialog!!
                    .notice as ConversationNotice.Failure
            ).code,
        )
    }

    @Test
    fun deletingFromTheOpenDialogClosesIt() {
        runBlocking {
            repository.save(testConversation("c1", updatedAt = 100L, title = "Open talk", turns = listOf(testUserTurn("u1", "hello"))))
        }
        setConversationApp()
        openDrawer()
        composeRule.onNodeWithTag(conversationRow("c1")).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(DELETE_CONVERSATION).performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(CONFIRM_DELETE).performClick()
        composeRule.waitForIdle()

        assertNull(runBlocking { repository.load(ConversationId("c1")) })
        assertEquals(ConversationScreen.LIST, viewModel.uiState.value.screen)
    }

    @Test
    fun sendIsDisabledUntilThereIsText() {
        setConversationApp()
        openNewConversation()
        composeRule.onNodeWithTag(SEND).assertIsNotEnabled()
        composeRule.onNodeWithTag(COMPOSER).performClick()
        composeRule.onNodeWithTag(COMPOSER).performTextInput("Hi")
        composeRule.onNodeWithTag(SEND).assertIsEnabled()
    }

    private companion object {
        val REPLY_SCRIPT = listOf(LlmStreamEvent.Delta("Echo reply"), LlmStreamEvent.Completed())
    }
}
