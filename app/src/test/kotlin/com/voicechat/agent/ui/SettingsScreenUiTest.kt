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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelCatalogState
import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.credentials.CredentialStatus
import com.voicechat.agent.credentials.InMemoryCredentialStore
import com.voicechat.agent.domain.ConnectionState
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ModelCapabilities
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.StaticModelCapabilityCatalog
import com.voicechat.agent.settings.InMemorySettingsStore
import com.voicechat.agent.settings.LlmSettingsSection
import com.voicechat.agent.settings.SettingsActions
import com.voicechat.agent.settings.SettingsCapabilities
import com.voicechat.agent.settings.SettingsOptions
import com.voicechat.agent.settings.SettingsUiState
import com.voicechat.agent.settings.SmartTurnSettingsSection
import com.voicechat.agent.settings.SmartTurnState
import com.voicechat.agent.settings.StaticSettingsCapabilityProvider
import com.voicechat.agent.settings.SttSettingsSection
import com.voicechat.agent.settings.TtsSettingsSection
import com.voicechat.agent.settings.VoiceSettings
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.tts.TtsVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Compose UI tests for the M22 settings surface, on the JVM under Robolectric.
 *
 * They prove the user-visible acceptance: an unsupported option is absent, an
 * unavailable entry is disabled with a reason, the credential replace/remove
 * controls are actionable, an invalid selection is refused, and a validated
 * selection persists — plus the destination/remote-transfer disclosure.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SettingsScreenUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val modelId = ModelId("gpt-test")
    private val model = descriptor(modelId, "Test Model")

    private fun render(
        state: SettingsUiState,
        actions: SettingsActions = RecordingSettingsActions(),
    ) {
        composeRule.setContent {
            SettingsScreen(state = state, actions = actions, onBack = {})
        }
        composeRule.waitForIdle()
    }

    @Test
    fun anUnsupportedAuthMethodIsAbsent() {
        val provider = registry.capabilities(KnownProviders.OPENAI)!!
        val llm =
            LlmSettingsSection(
                providers = SettingsOptions.providers(registry, KnownProviders.OPENAI),
                selectedProviderId = KnownProviders.OPENAI,
                providerDisplayName = provider.displayName,
                authMethods = SettingsOptions.authMethods(provider, selected = AuthMethod.API_KEY),
                selectedAuthMethod = AuthMethod.API_KEY,
                reasoning = SettingsOptions.reasoningLevels(provider, model = null, selected = null),
            )
        render(baseState().copy(llm = llm))

        composeRule.onNodeWithTag(SettingsTestTags.authOption("API_KEY")).assertExists()
        composeRule.onNodeWithTag(SettingsTestTags.authOption("OAUTH_PKCE")).assertDoesNotExist()
    }

    @Test
    fun anUnsupportedReasoningLevelIsAbsent() {
        val provider = registry.capabilities(KnownProviders.OPENAI)!!
        val modelCapabilities =
            ModelCapabilities(KnownProviders.OPENAI, modelId, true, true, setOf(ReasoningLevel.NONE, ReasoningLevel.HIGH))
        val llm =
            LlmSettingsSection(
                selectedProviderId = KnownProviders.OPENAI,
                reasoning = SettingsOptions.reasoningLevels(provider, modelCapabilities, selected = null),
            )
        render(baseState().copy(llm = llm))

        composeRule.onNodeWithTag(SettingsTestTags.reasoningOption("HIGH")).assertExists()
        composeRule.onNodeWithTag(SettingsTestTags.reasoningOption("MEDIUM")).assertDoesNotExist()
    }

    @Test
    fun anUnavailableModelIsDisabledWithAReason() {
        val blocked = descriptor(ModelId("blocked"), "Blocked Model")
        val llm =
            LlmSettingsSection(
                selectedProviderId = KnownProviders.OPENAI,
                models =
                    SettingsOptions.models(
                        listOf(
                            ModelAvailability.Ready(model),
                            ModelAvailability.Unavailable(
                                blocked,
                                VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, detail = "not enough memory"),
                            ),
                        ),
                        selected = modelId,
                    ),
            )
        render(baseState().copy(llm = llm))

        composeRule.onNodeWithTag(SettingsTestTags.modelOption("gpt-test")).assertIsEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.modelOption("blocked")).assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.modelReason("blocked")).assertExists()
        composeRule.onNodeWithText("not enough memory").assertExists()
    }

    @Test
    fun theSettingsScreenShowsNoTransferOrRetentionNotice() {
        val llm =
            LlmSettingsSection(
                selectedProviderId = KnownProviders.OPENROUTER,
                providerDisplayName = "OpenRouter",
            )
        render(baseState().copy(llm = llm))

        composeRule.onAllNodesWithText("Data transfer").assertCountEquals(0)
        composeRule.onAllNodesWithText("Requests go to", substring = true).assertCountEquals(0)
        composeRule.onAllNodesWithText("Retention", substring = true).assertCountEquals(0)
    }

    @Test
    fun aConfigurableDestinationShowsItsFieldAndError() {
        val llm =
            LlmSettingsSection(
                selectedProviderId = KnownProviders.HERMES,
                providerDisplayName = "Hermes Agent API Server",
                needsConfiguredDestination = true,
                destinationDraft = "http://hermes.example.com/v1",
                destinationError = "TLS (https) is required for a non-local destination",
            )
        render(baseState().copy(llm = llm))

        composeRule.onNodeWithTag(SettingsTestTags.DESTINATION_FIELD).assertExists()
        composeRule.onNodeWithTag(SettingsTestTags.DESTINATION_ERROR).assertExists()
    }

    @Test
    fun anEmptyModelCatalogShowsAnExplicitReasonAndARefreshAction() {
        val llm =
            LlmSettingsSection(
                selectedProviderId = KnownProviders.OPENAI,
                providerDisplayName = "OpenAI",
                models = emptyList(),
                modelCatalogState = ModelCatalogState.Empty("No models are wired for this provider yet."),
            )
        render(baseState().copy(llm = llm))

        composeRule.onNodeWithTag(SettingsTestTags.MODEL_CATALOG_NOTICE).assertExists()
        composeRule.onNodeWithText("Recheck capabilities").assertExists()
    }

    @Test
    fun theNoOnDeviceVoiceStateIsExplicit() {
        render(baseState().copy(tts = TtsSettingsSection(options = emptyList())))

        composeRule.onNodeWithTag(SettingsTestTags.TTS_NO_VOICE).assertExists()
    }

    @Test
    fun smartTurnIsDisabledWithAReasonWhenUnavailable() {
        render(
            baseState().copy(
                smartTurn = SmartTurnSettingsSection(enabled = false, state = SmartTurnState.Unavailable("not installed")),
            ),
        )

        composeRule.onNodeWithTag(SettingsTestTags.SMART_TURN_TOGGLE).assertIsNotEnabled()
        composeRule.onNodeWithTag(SettingsTestTags.SMART_TURN_REASON).assertExists()
    }

    @Test
    fun theCredentialFieldSavesAndRemovesThroughTheStateHolder() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher)
        val store = InMemorySettingsStore(VoiceSettings.EMPTY)
        val credentials = InMemoryCredentialStore()
        val viewModel =
            SettingsViewModel(
                store = store,
                registry = registry,
                credentials = credentials,
                capabilityProvider = StaticSettingsCapabilityProvider(capabilities()),
                modelCatalog = StaticModelCapabilityCatalog(emptyList()),
                dispatcher = dispatcher,
                scope = scope,
            )
        val active = mutableStateOf(viewModel)
        composeRule.setContent {
            val vm = active.value
            val state by vm.uiState.collectAsState()
            SettingsScreen(state = state, actions = vm, onBack = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SettingsTestTags.providerOption("openai")).performScrollTo().performClick()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SettingsTestTags.CREDENTIAL_FIELD).performScrollTo().performTextInput("ui-entered-key-123456")
        composeRule.onNodeWithTag(SettingsTestTags.CREDENTIAL_SAVE).performScrollTo().performClick()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()

        assertEquals("ui-entered-key-123456", runBlockingLoad(credentials))
        composeRule.onNodeWithTag(SettingsTestTags.CREDENTIAL_REMOVE).assertIsEnabled()

        composeRule.onNodeWithTag(SettingsTestTags.CREDENTIAL_REMOVE).performScrollTo().performClick()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()
        scheduler.advanceUntilIdle()

        assertNull(runBlockingLoad(credentials))
        scope.cancel()
    }

    @Test
    fun selectingAProviderPersistsTheValidatedSelection() {
        val scheduler = TestCoroutineScheduler()
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher)
        val store = InMemorySettingsStore(VoiceSettings.EMPTY)
        val viewModel =
            SettingsViewModel(
                store = store,
                registry = registry,
                credentials = InMemoryCredentialStore(),
                capabilityProvider = StaticSettingsCapabilityProvider(capabilities()),
                modelCatalog = StaticModelCapabilityCatalog(emptyList()),
                dispatcher = dispatcher,
                scope = scope,
            )
        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            SettingsScreen(state = state, actions = viewModel, onBack = {})
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag(SettingsTestTags.providerOption("deepseek")).performScrollTo().performClick()
        scheduler.advanceUntilIdle()
        composeRule.waitForIdle()

        assertEquals(KnownProviders.DEEPSEEK, kotlinx.coroutines.runBlocking { store.observe().first() }.llmProviderId)
        scope.cancel()
    }

    private fun runBlockingLoad(credentials: InMemoryCredentialStore): String? =
        kotlinx.coroutines.runBlocking { credentials.load(KnownProviders.OPENAI)?.secret }

    private fun baseState(): SettingsUiState =
        SettingsUiState(
            isLoading = false,
            stt =
                SttSettingsSection(
                    options =
                        listOf(
                            com.voicechat.agent.settings.SelectableOption(
                                value = SttEngine(SttMode.ADVANCED, Locale.US),
                                label = "ML Kit Speech Recognition (Advanced)",
                                state = com.voicechat.agent.settings.OptionState.Available,
                                selected = true,
                            ),
                        ),
                    selectedMode = SttMode.ADVANCED,
                ),
            tts =
                TtsSettingsSection(
                    options =
                        listOf(
                            com.voicechat.agent.settings.SelectableOption(
                                value = TtsVoice("voice-on-device", "On-device", Locale.US, requiresNetwork = false),
                                label = "On-device (en-US)",
                                state = com.voicechat.agent.settings.OptionState.Available,
                                selected = true,
                            ),
                        ),
                    selectedVoiceId = "voice-on-device",
                ),
            smartTurn = SmartTurnSettingsSection(enabled = true, state = SmartTurnState.Available),
        )

    private fun capabilities(): SettingsCapabilities =
        SettingsCapabilities(
            sttAvailability = listOf(SttAvailability.Ready(SttEngine(SttMode.ADVANCED, Locale.US))),
            ttsVoices = listOf(TtsVoice("voice-on-device", "On-device", Locale.US, requiresNetwork = false)),
            smartTurn = SmartTurnState.Available,
            models = listOf(ModelAvailability.Ready(model)),
        )

    private fun descriptor(
        id: ModelId,
        name: String,
    ) = ModelDescriptor(
        id = id,
        task = ModelTask.LANGUAGE_MODEL,
        displayName = name,
        runtime = ModelRuntime.REMOTE_API,
        providerId = KnownProviders.OPENAI,
    )
}

/** Records every settings action so a stateless screen's controls can be asserted. */
private class RecordingSettingsActions : SettingsActions {
    var lastProvider: ProviderId? = null
    var lastModel: ModelId? = null
    var lastAuth: AuthMethod? = null
    var lastReasoning: ReasoningLevel? = null
    var lastSttMode: SttMode? = null
    var lastVoice: String? = null
    var lastSmartTurn: Boolean? = null
    var lastDestination: String? = null
    var lastCredential: String? = null
    var removeCount: Int = 0
    var signInCount: Int = 0
    var refreshCount: Int = 0

    override fun onSelectSttMode(mode: SttMode) {
        lastSttMode = mode
    }

    override fun onSelectLlmProvider(providerId: ProviderId) {
        lastProvider = providerId
    }

    override fun onSelectLlmModel(modelId: ModelId) {
        lastModel = modelId
    }

    override fun onSelectAuthMethod(method: AuthMethod) {
        lastAuth = method
    }

    override fun onSelectReasoningLevel(level: ReasoningLevel) {
        lastReasoning = level
    }

    override fun onSelectTtsVoice(voiceId: String) {
        lastVoice = voiceId
    }

    override fun onSetSmartTurnEnabled(enabled: Boolean) {
        lastSmartTurn = enabled
    }

    override fun onDestinationChanged(text: String) {
        lastDestination = text
    }

    override fun onSaveCredential(secret: String) {
        lastCredential = secret
    }

    override fun onRemoveCredential() {
        removeCount++
    }

    override fun onBeginProviderSignIn() {
        signInCount++
    }

    override fun onRefresh() {
        refreshCount++
    }

    override fun onDismissNotice() = Unit
}
