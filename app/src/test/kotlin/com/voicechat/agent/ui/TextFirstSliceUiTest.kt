package com.voicechat.agent.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.InMemoryCredentialStore
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.RegisteredProviderLanguageModelFactory
import com.voicechat.agent.providers.opencodego.OpenCodeGoFixtures
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.remote.scriptedResponse
import com.voicechat.agent.settings.VoiceSettings
import com.voicechat.agent.ui.ConversationTestTags.COMPOSER
import com.voicechat.agent.ui.ConversationTestTags.NEW_CONVERSATION
import com.voicechat.agent.ui.ConversationTestTags.PROVIDER_DISCLOSURE
import com.voicechat.agent.ui.ConversationTestTags.REMOTE_TRANSFER_NOTICE
import com.voicechat.agent.ui.ConversationTestTags.RETENTION_NOTICE
import com.voicechat.agent.ui.ConversationTestTags.SEND
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M23 Compose acceptance: the disclosure and the visible result.
 *
 * Runs on the JVM under Robolectric with the real [ConversationViewModel], the
 * real registry-driven provider factory, and the real OpenCode Go adapter over a
 * recorded SSE fixture. No device, network, or real credential is used.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TextFirstSliceUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val scheduler = TestCoroutineScheduler()
    private lateinit var scope: CoroutineScope
    private val activeViewModel = mutableStateOf<ConversationViewModel?>(null)
    private val settings = MutableStateFlow<VoiceSettings>(VoiceSettings.EMPTY)

    private val registry = ProviderCapabilityRegistry.verifiedDefaults()

    @Before
    fun setUp() {
        scope = CoroutineScope(UnconfinedTestDispatcher(scheduler))
    }

    @After
    fun tearDown() {
        activeViewModel.value?.shutdown()
        scope.cancel()
    }

    @Test
    fun theDialogDisclosesTheProviderModelAndRemoteTransferBeforeSend() {
        settings.value = goSettings()
        setConversationApp()

        composeRule.onNodeWithTag(NEW_CONVERSATION).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PROVIDER_DISCLOSURE).assertIsDisplayed()
        composeRule.onNodeWithText("Provider: OpenCode Go · Model: glm-5.3-flash").assertIsDisplayed()
        composeRule.onNodeWithTag(REMOTE_TRANSFER_NOTICE).assertIsDisplayed()
        // R-0139: the registry's retention note is shown before any send.
        composeRule.onNodeWithTag(RETENTION_NOTICE).assertIsDisplayed()
    }

    @Test
    fun anUnselectedProviderShowsTheHonestNotConfiguredHint() {
        settings.value = VoiceSettings.EMPTY
        setConversationApp()

        composeRule.onNodeWithTag(NEW_CONVERSATION).performClick()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(PROVIDER_DISCLOSURE).assertIsDisplayed()
        composeRule.onNodeWithText("No provider or model is selected. Choose one in Settings before sending.").assertIsDisplayed()
    }

    @Test
    fun sendingThroughTheFixtureProviderRendersAndPersistsTheReply() {
        settings.value = goSettings()
        val repository =
            com.voicechat.agent.fake
                .InMemoryConversationRepository()
        setConversationApp(repository = repository)

        composeRule.onNodeWithTag(NEW_CONVERSATION).performClick()
        composeRule.onNodeWithTag(COMPOSER).performClick()
        composeRule.onNodeWithTag(COMPOSER).performTextInput("Hello provider")
        composeRule.onNodeWithTag(SEND).performClick()
        composeRule.waitForIdle()

        composeRule.onAllNodesWithText("Hello, world").onFirst().assertIsDisplayed()

        val conversationId =
            activeViewModel.value!!
                .uiState.value.dialog!!
                .conversationId!!
        val persisted = runBlocking { repository.load(conversationId) }!!
        assertEquals(2, persisted.turns.size)
        assertTrue(persisted.turns.last() is AssistantTurn)
        assertEquals("Hello, world", (persisted.turns.last() as AssistantTurn).generated.text)
    }

    private fun goSettings(): VoiceSettings =
        VoiceSettings(
            llmProviderId = KnownProviders.OPENCODE_GO,
            llmModelId = ModelId("glm-5.3-flash"),
            llmAuthMethod = AuthMethod.API_KEY,
        )

    private fun setConversationApp(
        repository: com.voicechat.agent.contracts.ConversationRepository =
            com.voicechat.agent.fake
                .InMemoryConversationRepository(),
    ) {
        val credentials = InMemoryCredentialStore()
        runBlocking {
            credentials.store(Credential(KnownProviders.OPENCODE_GO, CredentialKind.API_KEY, "test-key-not-a-secret"))
        }
        val engine = FakeHttpStreamingEngine { scriptedResponse(body = OpenCodeGoFixtures.text("chat_normal.sse")) }
        val factory = RegisteredProviderLanguageModelFactory(registry, credentials, RemoteTransport(engine))
        val viewModel =
            ConversationViewModel(
                repository = repository,
                languageModel = NotConfiguredLanguageModel(),
                selection = ConversationDefaults.selection,
                dispatcher = UnconfinedTestDispatcher(scheduler),
                idFactory = SequentialConversationIdFactory(),
                wallClock = { 1_000L },
                settingsFlow = settings,
                providerRegistry = registry,
                providerFactory = factory,
                scope = scope,
            )
        activeViewModel.value = viewModel
        composeRule.setContent {
            val vm = requireNotNull(activeViewModel.value)
            val state by vm.uiState.collectAsState()
            ConversationApp(state = state, actions = vm)
        }
        composeRule.waitForIdle()
    }
}
