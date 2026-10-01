package com.voicechat.agent.ui

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.CredentialStatus
import com.voicechat.agent.credentials.InMemoryCredentialStore
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
import com.voicechat.agent.settings.InvalidSelection
import com.voicechat.agent.settings.SettingsCapabilities
import com.voicechat.agent.settings.SettingsNotice
import com.voicechat.agent.settings.SettingsValidator
import com.voicechat.agent.settings.SmartTurnState
import com.voicechat.agent.settings.StaticSettingsCapabilityProvider
import com.voicechat.agent.settings.VoiceSettings
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttDownloadStatus
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.tts.TtsVoice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
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
import java.util.Locale

/**
 * JVM acceptance for the M22 settings state holder: capability-only options,
 * unavailable entries disabled with a reason, invalid selections refused, and
 * credential replace/remove through the M13 store. No device, network, or real
 * credential is involved; the store and credential store are in-memory fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val modelId = ModelId("gpt-test")
    private val model = descriptor(modelId, "Test Model")
    private val modelCatalog =
        StaticModelCapabilityCatalog(
            listOf(
                ModelCapabilities(
                    providerId = KnownProviders.OPENAI,
                    modelId = modelId,
                    streaming = true,
                    usageReporting = true,
                    reasoningLevels = setOf(ReasoningLevel.NONE, ReasoningLevel.HIGH),
                ),
            ),
        )

    private fun capabilities(
        models: List<ModelAvailability> = listOf(ModelAvailability.Ready(model)),
        smartTurn: SmartTurnState = SmartTurnState.Available,
        voices: List<TtsVoice> = listOf(embeddedVoice()),
        stt: List<SttAvailability> = listOf(SttAvailability.Ready(SttEngine(SttMode.ADVANCED, Locale.US))),
    ) = SettingsCapabilities(
        sttAvailability = stt,
        ttsVoices = voices,
        smartTurn = smartTurn,
        models = models,
    )

    private class Harness(
        val store: InMemorySettingsStore,
        val credentials: InMemoryCredentialStore,
        val viewModel: SettingsViewModel,
        val scope: CoroutineScope,
    )

    private fun harness(
        scheduler: TestCoroutineScheduler,
        store: InMemorySettingsStore = InMemorySettingsStore(VoiceSettings.EMPTY),
        capabilities: SettingsCapabilities = capabilities(),
        downloader: SttModelDownloader = NoOpSttModelDownloader,
    ): Harness {
        val dispatcher = UnconfinedTestDispatcher(scheduler)
        val scope = CoroutineScope(dispatcher)
        val credentials = InMemoryCredentialStore()
        val viewModel =
            SettingsViewModel(
                store = store,
                registry = registry,
                credentials = credentials,
                capabilityProvider = StaticSettingsCapabilityProvider(capabilities),
                modelCatalog = modelCatalog,
                sttDownloader = downloader,
                dispatcher = dispatcher,
                scope = scope,
            )
        return Harness(store, credentials, viewModel, scope)
    }

    @Test
    fun selectingAProviderAndModelPersistsValidatedSelections() =
        runTest {
            val h = harness(testScheduler)
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            h.viewModel.onSelectLlmModel(modelId)
            h.viewModel.onSelectReasoningLevel(ReasoningLevel.HIGH)
            advanceUntilIdle()

            val persisted = h.store.observe().first()
            assertEquals(KnownProviders.OPENAI, persisted.llmProviderId)
            assertEquals(modelId, persisted.llmModelId)
            assertEquals(ReasoningLevel.HIGH, persisted.reasoningLevel)
            assertEquals(modelId, h.viewModel.uiState.value.llm.selectedModelId)
            h.scope.cancel()
        }

    @Test
    fun anUnsupportedReasoningLevelIsRefusedAndNotPersisted() =
        runTest {
            val h = harness(testScheduler)
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            h.viewModel.onSelectLlmModel(modelId)
            // The UI never offers this, but a direct call must still be rejected.
            h.viewModel.onSelectReasoningLevel(ReasoningLevel.LOW)
            advanceUntilIdle()

            assertNull(
                h.store
                    .observe()
                    .first()
                    .reasoningLevel,
            )
            assertEquals(
                SettingsNotice.Info(SettingsNotice.Info.InfoKind.INVALID_SELECTION_CLEARED),
                h.viewModel.uiState.value.notice,
            )
            h.scope.cancel()
        }

    @Test
    fun unsupportedAuthMethodsAndReasoningLevelsAreAbsentFromTheState() =
        runTest {
            val h = harness(testScheduler)
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            h.viewModel.onSelectLlmModel(modelId)
            advanceUntilIdle()

            val llm = h.viewModel.uiState.value.llm
            assertEquals(listOf(AuthMethod.API_KEY), llm.authMethods.map { it.value })
            assertEquals(
                listOf(ReasoningLevel.NONE, ReasoningLevel.HIGH),
                llm.reasoning.map { it.value },
            )
            h.scope.cancel()
        }

    @Test
    fun anEmptyModelCatalogIsAFirstClassEmptyStateNotAConfiguredProvider() =
        runTest {
            val h = harness(testScheduler, capabilities = capabilities(models = emptyList()))
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            advanceUntilIdle()

            val llm = h.viewModel.uiState.value.llm
            assertTrue(llm.models.isEmpty())
            val state = llm.modelCatalogState
            assertTrue(state is com.voicechat.agent.contracts.ModelCatalogState.Empty)
            assertFalse(state.hasSelectableModel)
            assertTrue(state.needsAttention)
            assertNull(
                h.store
                    .observe()
                    .first()
                    .llmModelId,
            )
            h.scope.cancel()
        }

    @Test
    fun aProviderWithOnlyUnavailableModelsIsReportedAsUnavailableNotConfigured() =
        runTest {
            val blocked = descriptor(ModelId("blocked"), "Blocked Model")
            val h =
                harness(
                    testScheduler,
                    capabilities =
                        capabilities(
                            models =
                                listOf(
                                    ModelAvailability.Unavailable(
                                        blocked,
                                        VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, detail = "device lacks memory"),
                                    ),
                                ),
                        ),
                )
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            advanceUntilIdle()

            val state = h.viewModel.uiState.value.llm.modelCatalogState
            assertTrue(state is com.voicechat.agent.contracts.ModelCatalogState.Unavailable)
            assertFalse(state.hasSelectableModel)
            assertTrue(state.needsAttention)
            h.scope.cancel()
        }

    @Test
    fun aProviderNeverAppearsListableBeforeItsCatalogIsRead() =
        runTest {
            val h = harness(testScheduler)
            advanceUntilIdle()

            // No provider chosen: the catalogs carry an explicit "choose a provider" state.
            val beforeSelect = h.viewModel.uiState.value.llm.modelCatalogState
            assertTrue(beforeSelect is com.voicechat.agent.contracts.ModelCatalogState.Empty)

            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            advanceUntilIdle()
            val afterSelect = h.viewModel.uiState.value.llm.modelCatalogState
            assertTrue(afterSelect is com.voicechat.agent.contracts.ModelCatalogState.Available)
            assertTrue(afterSelect.hasSelectableModel)
            h.scope.cancel()
        }

    @Test
    fun unavailableModelsAreShownDisabledWithAReason() =
        runTest {
            val blocked = descriptor(ModelId("blocked"), "Blocked Model")
            val h =
                harness(
                    testScheduler,
                    capabilities =
                        capabilities(
                            models =
                                listOf(
                                    ModelAvailability.Ready(model),
                                    ModelAvailability.Unavailable(
                                        blocked,
                                        VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, detail = "device lacks memory"),
                                    ),
                                ),
                        ),
                )
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            advanceUntilIdle()

            val options = h.viewModel.uiState.value.llm.models
            assertTrue(options.single { it.value.model.id == modelId }.isAvailable)
            val blockedOption = options.single { it.value.model.id == blocked.id }
            assertFalse(blockedOption.isAvailable)
            assertEquals("device lacks memory", blockedOption.unavailableReason)
            h.scope.cancel()
        }

    @Test
    fun credentialSaveReplaceAndRemoveGoThroughTheCredentialStore() =
        runTest {
            val h = harness(testScheduler)
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            h.viewModel.onSelectAuthMethod(AuthMethod.API_KEY)
            h.viewModel.onSaveCredential("first-key-0123456789")
            advanceUntilIdle()

            assertEquals(
                CredentialStatus.Stored(KnownProviders.OPENAI, CredentialKind.API_KEY),
                h.viewModel.uiState.value.llm.credentialStatus,
            )
            assertEquals("first-key-0123456789", h.credentials.load(KnownProviders.OPENAI)?.secret)

            // Replace.
            h.viewModel.onSaveCredential("second-key-9876543210")
            advanceUntilIdle()
            assertEquals("second-key-9876543210", h.credentials.load(KnownProviders.OPENAI)?.secret)

            // Remove.
            h.viewModel.onRemoveCredential()
            advanceUntilIdle()
            assertEquals(CredentialStatus.NotStored, h.viewModel.uiState.value.llm.credentialStatus)
            assertNull(h.credentials.load(KnownProviders.OPENAI))
            h.scope.cancel()
        }

    @Test
    fun theSecretNeverAppearsInTheUiState() =
        runTest {
            val h = harness(testScheduler)
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            h.viewModel.onSaveCredential("super-secret-value-123456")
            advanceUntilIdle()

            assertFalse(
                h.viewModel.uiState.value
                    .toString()
                    .contains("super-secret-value-123456"),
            )
            h.scope.cancel()
        }

    @Test
    fun aValidatedSelectionIsRestoredAfterARestart() =
        runTest {
            val persistentStore = InMemorySettingsStore(VoiceSettings.EMPTY)
            val h = harness(testScheduler, store = persistentStore)
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENAI)
            h.viewModel.onSelectLlmModel(modelId)
            h.viewModel.onSelectSttMode(SttMode.ADVANCED)
            h.viewModel.onSelectTtsVoice(embeddedVoice().id)
            advanceUntilIdle()
            h.viewModel.shutdown()
            h.scope.cancel()

            // A new state holder over the same durable store value.
            val restarted = harness(testScheduler, store = persistentStore)
            advanceUntilIdle()
            val llm = restarted.viewModel.uiState.value.llm
            assertEquals(KnownProviders.OPENAI, llm.selectedProviderId)
            assertEquals(modelId, llm.selectedModelId)
            assertEquals(SttMode.ADVANCED, restarted.viewModel.uiState.value.stt.selectedMode)
            assertEquals(embeddedVoice().id, restarted.viewModel.uiState.value.tts.selectedVoiceId)
            restarted.scope.cancel()
        }

    @Test
    fun aStoredInvalidSelectionIsDroppedOnLoad() =
        runTest {
            val h =
                harness(
                    testScheduler,
                    store =
                        InMemorySettingsStore(
                            VoiceSettings(
                                llmProviderId = ProviderId("gone"),
                                reasoningLevel = ReasoningLevel.HIGH,
                            ),
                        ),
                )
            advanceUntilIdle()

            assertNull(h.viewModel.uiState.value.llm.selectedProviderId)
            assertNull(
                h.store
                    .observe()
                    .first()
                    .llmProviderId,
            )
            assertEquals(
                SettingsNotice.Info(SettingsNotice.Info.InfoKind.INVALID_SELECTION_CLEARED),
                h.viewModel.uiState.value.notice,
            )
            h.scope.cancel()
        }

    @Test
    fun theDestinationIsValidatedButNotExposedAsASettingsNotice() =
        runTest {
            val h = harness(testScheduler)
            h.viewModel.onSelectLlmProvider(KnownProviders.OPENROUTER)
            advanceUntilIdle()

            val llm = h.viewModel.uiState.value.llm
            assertFalse(llm.needsConfiguredDestination)
            h.scope.cancel()
        }

    @Test
    fun aConfigurableProviderNeedsAValidatedDestination() =
        runTest {
            val h = harness(testScheduler)
            h.viewModel.onSelectLlmProvider(KnownProviders.HERMES)
            advanceUntilIdle()
            assertTrue(h.viewModel.uiState.value.llm.needsConfiguredDestination)

            // Insecure non-local http is refused and not persisted.
            h.viewModel.onDestinationChanged("http://hermes.example.com/v1")
            advanceUntilIdle()
            assertNotNull(h.viewModel.uiState.value.llm.destinationError)
            assertNull(
                h.store
                    .observe()
                    .first()
                    .llmServerUrl,
            )

            // A valid https destination is persisted.
            h.viewModel.onDestinationChanged("https://hermes.example.com/v1")
            advanceUntilIdle()
            assertEquals(
                "https://hermes.example.com/v1",
                h.store
                    .observe()
                    .first()
                    .llmServerUrl,
            )
            h.scope.cancel()
        }

    @Test
    fun smartTurnCannotBeEnabledWhenItIsUnavailable() =
        runTest {
            val h = harness(testScheduler, capabilities = capabilities(smartTurn = SmartTurnState.Unavailable("not installed")))
            h.viewModel.onSetSmartTurnEnabled(true)
            advanceUntilIdle()

            assertFalse(h.viewModel.uiState.value.smartTurn.enabled)
            assertFalse(
                h.store
                    .observe()
                    .first()
                    .smartTurnEnabled,
            )
            assertFalse(h.viewModel.uiState.value.smartTurn.selectable)
            h.scope.cancel()
        }

    @Test
    fun networkOnlyVoicesAreNotOfferedAndTheNoVoiceStateIsExplicit() =
        runTest {
            val network = TtsVoice("voice-cloud", "Cloud", Locale.US, requiresNetwork = true)
            val h = harness(testScheduler, capabilities = capabilities(voices = listOf(network)))
            advanceUntilIdle()

            assertTrue(h.viewModel.uiState.value.tts.noOnDeviceVoice)
            assertTrue(
                h.viewModel.uiState.value.tts.options
                    .isEmpty(),
            )
            h.scope.cancel()
        }

    @Test
    fun theValidatorDropsOnlyTheInvalidParts() {
        val validation =
            SettingsValidator.validate(
                VoiceSettings(
                    sttMode = SttMode.ADVANCED,
                    llmProviderId = KnownProviders.OPENAI,
                    llmModelId = ModelId("not-offered"),
                ),
                registry,
                capabilities(),
                modelCatalog,
            )

        assertEquals(listOf(InvalidSelection.MODEL), validation.invalid)
        assertEquals(SttMode.ADVANCED, validation.settings.sttMode)
        assertEquals(KnownProviders.OPENAI, validation.settings.llmProviderId)
        assertNull(validation.settings.llmModelId)
    }

    @Test
    fun theDefaultSelectionIsOpenCodeGoWithItsFreeModel() =
        runTest {
            // The default provider/model must be present in the capability snapshot
            // for validation to keep it; the app ships that snapshot from the
            // documented OpenCode Go catalog.
            val defaultModel =
                ModelAvailability.Ready(
                    ModelDescriptor(
                        id = VoiceSettings.DEFAULT_MODEL_ID,
                        task = ModelTask.LANGUAGE_MODEL,
                        displayName = "longcat-2.5-preview-free",
                        runtime = ModelRuntime.REMOTE_API,
                        providerId = KnownProviders.OPENCODE_GO,
                    ),
                )
            val h = harness(testScheduler, capabilities = capabilities(models = listOf(defaultModel)))
            advanceUntilIdle()

            val llm = h.viewModel.uiState.value.llm
            assertEquals(KnownProviders.OPENCODE_GO, llm.selectedProviderId)
            assertEquals(VoiceSettings.DEFAULT_MODEL_ID, llm.selectedModelId)
            // The default is durable like any other choice.
            assertEquals(
                KnownProviders.OPENCODE_GO,
                h.store
                    .observe()
                    .first()
                    .llmProviderId,
            )
            assertEquals(
                VoiceSettings.DEFAULT_MODEL_ID,
                h.store
                    .observe()
                    .first()
                    .llmModelId,
            )
            h.scope.cancel()
        }

    @Test
    fun smartTurnIsEnabledByDefaultWhenTheDetectorIsInstalled() =
        runTest {
            val h = harness(testScheduler, capabilities = capabilities(smartTurn = SmartTurnState.Available))
            advanceUntilIdle()

            assertTrue(h.viewModel.uiState.value.smartTurn.enabled)
            assertTrue(h.viewModel.uiState.value.smartTurn.selectable)
            h.scope.cancel()
        }

    @Test
    fun smartTurnDefaultsOffWhenTheDetectorIsNotInstalled() =
        runTest {
            val h = harness(testScheduler, capabilities = capabilities(smartTurn = SmartTurnState.Unavailable("not installed")))
            advanceUntilIdle()

            assertFalse(h.viewModel.uiState.value.smartTurn.enabled)
            h.scope.cancel()
        }

    @Test
    fun voicesOutsideTheSupportedLocalesAreNotOffered() =
        runTest {
            val voices =
                listOf(
                    TtsVoice("en-us", "English (US)", Locale.US, requiresNetwork = false),
                    TtsVoice("de-de", "German", Locale.GERMANY, requiresNetwork = false),
                )
            val h = harness(testScheduler, capabilities = capabilities(voices = voices))
            advanceUntilIdle()

            assertEquals(
                listOf("en-us"),
                h.viewModel.uiState.value.tts.options
                    .map { it.value.id },
            )
            h.scope.cancel()
        }

    @Test
    fun downloadingAnSttModelReportsProgressAndRefreshesAvailability() =
        runTest {
            val engine = SttEngine(SttMode.ADVANCED, Locale.US)
            val downloader =
                SttModelDownloader {
                    flow {
                        emit(SttDownloadStatus.Started(bytesToDownload = 1000))
                        emit(SttDownloadStatus.Progress(bytesDownloaded = 500))
                        emit(SttDownloadStatus.Completed)
                    }
                }
            val h =
                harness(
                    testScheduler,
                    store = InMemorySettingsStore(VoiceSettings(sttMode = SttMode.ADVANCED)),
                    capabilities =
                        capabilities(
                            stt =
                                listOf(
                                    SttAvailability.DownloadRequired(engine),
                                    SttAvailability.Ready(SttEngine(SttMode.BASIC, Locale.US)),
                                ),
                        ),
                    downloader = downloader,
                )
            advanceUntilIdle()
            h.viewModel.onDismissNotice()

            h.viewModel.onDownloadSttModel()
            advanceUntilIdle()

            // The download completed, so the progress bar is gone and no failure
            // notice remains.
            assertNull(h.viewModel.uiState.value.stt.download)
            assertNull(h.viewModel.uiState.value.notice)
            h.scope.cancel()
        }

    @Test
    fun aStoredModelIsNotDroppedBeforeTheCatalogIsRead() =
        runTest {
            // A cold start validates against an empty capability snapshot before
            // the runtime catalog arrives; the stored selection must survive it.
            val h =
                harness(
                    testScheduler,
                    store =
                        InMemorySettingsStore(
                            VoiceSettings(
                                llmProviderId = KnownProviders.OPENCODE_GO,
                                llmModelId = VoiceSettings.DEFAULT_MODEL_ID,
                            ),
                        ),
                    capabilities = capabilities(models = emptyList()),
                )
            advanceUntilIdle()

            assertEquals(VoiceSettings.DEFAULT_MODEL_ID, h.viewModel.uiState.value.llm.selectedModelId)
            assertEquals(
                VoiceSettings.DEFAULT_MODEL_ID,
                h.store
                    .observe()
                    .first()
                    .llmModelId,
            )
            h.scope.cancel()
        }

    @Test
    fun aStoredModelAbsentFromAReadCatalogIsDropped() =
        runTest {
            val h =
                harness(
                    testScheduler,
                    store =
                        InMemorySettingsStore(
                            VoiceSettings(
                                llmProviderId = KnownProviders.OPENAI,
                                llmModelId = ModelId("gone-from-catalog"),
                            ),
                        ),
                    // The provider's catalog was read and does not list this model.
                    capabilities = capabilities(models = listOf(ModelAvailability.Ready(model))),
                )
            advanceUntilIdle()

            assertNull(h.viewModel.uiState.value.llm.selectedModelId)
            h.scope.cancel()
        }

    @Test
    fun anUnreadCapabilitySnapshotDoesNotClearOrWarnAboutAStoredSelection() =
        runTest {
            // Startup validates before the device reports anything. That must not
            // be read as "your stored selection is unsupported".
            val h =
                harness(
                    testScheduler,
                    store =
                        InMemorySettingsStore(
                            VoiceSettings(
                                sttMode = SttMode.ADVANCED,
                                llmProviderId = KnownProviders.OPENCODE_GO,
                                llmModelId = VoiceSettings.DEFAULT_MODEL_ID,
                                smartTurnEnabled = true,
                            ),
                        ),
                    capabilities = SettingsCapabilities.EMPTY,
                )
            advanceUntilIdle()

            val state = h.viewModel.uiState.value
            assertEquals(SttMode.ADVANCED, state.stt.selectedMode)
            assertEquals(VoiceSettings.DEFAULT_MODEL_ID, state.llm.selectedModelId)
            assertNull(state.notice)
            h.scope.cancel()
        }

    @Test
    fun anUnselectedSttModePresentsThePreferredReadyModel() =
        runTest {
            val h =
                harness(
                    testScheduler,
                    capabilities =
                        capabilities(
                            stt =
                                listOf(
                                    SttAvailability.DownloadRequired(SttEngine(SttMode.ADVANCED, Locale.US)),
                                    SttAvailability.Ready(SttEngine(SttMode.BASIC, Locale.US)),
                                ),
                        ),
                )
            advanceUntilIdle()

            // Advanced is preferred, but only Basic is ready, so Basic is shown.
            assertEquals(SttMode.BASIC, h.viewModel.uiState.value.stt.selectedMode)
            h.scope.cancel()
        }

    private fun embeddedVoice() = TtsVoice("voice-on-device", "On-device", Locale.US, requiresNetwork = false)

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
