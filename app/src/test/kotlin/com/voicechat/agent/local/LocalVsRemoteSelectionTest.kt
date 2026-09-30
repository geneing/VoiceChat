package com.voicechat.agent.local

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.fake.FakeLanguageModel
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ProviderLanguageModelFactory
import com.voicechat.agent.settings.VoiceSettings
import com.voicechat.agent.ui.ProviderTurnResolver
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M20 acceptance that the on-device and remote backends are an explicit,
 * mutually-exclusive choice and never silently cross over.
 */
class LocalVsRemoteSelectionTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val conversationId = ConversationId("conv-1")

    private val localModel = AicoreLanguageModel(FakeLocalTextGenerator(chunks = listOf("on-device")))

    private class RecordingRemoteFactory : ProviderLanguageModelFactory {
        var calls = 0

        override fun create(
            selection: com.voicechat.agent.domain.ProviderModelSelection,
            sessionHint: String?,
            configuredServerUrl: String?,
        ): com.voicechat.agent.contracts.LanguageModel {
            calls++
            return FakeLanguageModel(providerId = selection.providerId)
        }
    }

    private fun localFactory(available: Boolean = true): LocalLanguageModelFactory =
        LocalLanguageModelFactory { id -> localModel.takeIf { available && id == LocalModels.GEMINI_NANO_ID } }

    @Test
    fun anOnDeviceSelectionResolvesLocallyAndNeverTouchesTheRemoteFactory() {
        val remote = RecordingRemoteFactory()
        val settings = VoiceSettings(llmLocalModelId = LocalModels.GEMINI_NANO_ID)

        val turn =
            ProviderTurnResolver.resolve(
                settings = settings,
                conversationId = conversationId,
                registry = registry,
                factory = remote,
                localFactory = localFactory(),
            )

        assertTrue(turn.configured)
        assertTrue(turn.onDevice)
        assertEquals(LocalProviders.AICORE, turn.selection.providerId)
        assertEquals(LocalModels.GEMINI_NANO_ID, turn.selection.modelId)
        assertEquals(0, remote.calls)
    }

    @Test
    fun anUnavailableOnDeviceSelectionDoesNotFallBackToRemote() {
        val remote = RecordingRemoteFactory()
        // Both a local and a remote selection are present, but local is chosen and
        // cannot be built: the result is the honest not-configured state, and the
        // remote adapter is never constructed.
        val settings =
            VoiceSettings(
                llmLocalModelId = LocalModels.GEMINI_NANO_ID,
                llmProviderId = KnownProviders.OPENAI,
                llmModelId = ModelId("gpt-x"),
            )

        val turn =
            ProviderTurnResolver.resolve(
                settings = settings,
                conversationId = conversationId,
                registry = registry,
                factory = remote,
                localFactory = localFactory(available = false),
            )

        assertFalse(turn.configured)
        assertTrue(turn.onDevice)
        assertEquals(0, remote.calls)
    }

    @Test
    fun aRemoteSelectionResolvesRemotelyAndNeverTouchesTheLocalFactory() {
        val remote = RecordingRemoteFactory()
        var localCalls = 0
        val countingLocalFactory =
            LocalLanguageModelFactory { id ->
                localCalls++
                localModel.takeIf { id == LocalModels.GEMINI_NANO_ID }
            }
        val settings =
            VoiceSettings(
                llmProviderId = KnownProviders.OPENAI,
                llmModelId = ModelId("gpt-x"),
            )

        val turn =
            ProviderTurnResolver.resolve(
                settings = settings,
                conversationId = conversationId,
                registry = registry,
                factory = remote,
                localFactory = countingLocalFactory,
            )

        assertTrue(turn.configured)
        assertFalse(turn.onDevice)
        assertEquals(1, remote.calls)
        assertEquals(0, localCalls)
    }

    @Test
    fun selectingALocalModelClearsTheRemoteSelection() {
        val remote =
            VoiceSettings(
                llmProviderId = KnownProviders.OPENAI,
                llmModelId = ModelId("gpt-x"),
                reasoningLevel = com.voicechat.agent.domain.ReasoningLevel.HIGH,
            )

        val local = LocalModelSelection.selectLocal(remote, LocalModels.GEMINI_NANO_ID)

        assertEquals(LocalModels.GEMINI_NANO_ID, local.llmLocalModelId)
        assertEquals(null, local.llmProviderId)
        assertEquals(null, local.llmModelId)
        assertEquals(null, local.llmAuthMethod)
        assertEquals(null, local.reasoningLevel)
        // The remote pair no longer resolves, so a turn cannot silently use it.
        assertEquals(null, local.llmSelection)
        assertTrue(local.usesLocalModel)
        assertTrue(LocalModelSelection.backendOf(local) is LlmBackend.OnDevice)
    }

    @Test
    fun clearingTheLocalModelRestoresTheRemoteBackend() {
        val local = VoiceSettings(llmLocalModelId = LocalModels.GEMINI_NANO_ID)
        val remote =
            VoiceSettings(
                llmProviderId = KnownProviders.OPENAI,
                llmModelId = ModelId("gpt-x"),
            )

        assertTrue(LocalModelSelection.backendOf(LocalModelSelection.clearLocal(local)) is LlmBackend.None)
        assertTrue(LocalModelSelection.backendOf(remote) is LlmBackend.Remote)
    }

    @Test
    fun localOptionsAreClearlyLabelledAsOnDevice() {
        val ready = LocalAvailabilityMapper.fromProbe(LocalModels.geminiNano, LocalProbeResult.Ready, systemManaged = true)
        val options =
            LocalModelOptions.options(
                availabilities = listOf(ready.asModelAvailability()),
                selected = LocalModels.GEMINI_NANO_ID,
            )

        val option = options.single()
        assertTrue(option.label.endsWith("(on-device)"))
        assertTrue(option.isAvailable)
        assertTrue(option.isSelected)
    }

    @Test
    fun anUnavailableLocalOptionCarriesItsReason() {
        val unavailable =
            LocalAvailabilityMapper
                .fromProbe(
                    LocalModels.geminiNano,
                    LocalProbeResult.Unavailable(com.voicechat.agent.domain.UnavailableReason.DEVICE_UNSUPPORTED, "unsupported"),
                    systemManaged = true,
                ).asModelAvailability()

        val option = LocalModelOptions.options(listOf(unavailable), selected = null).single()

        assertFalse(option.isAvailable)
        assertEquals("unsupported", option.unavailableReason)
    }

    @Test
    fun theAvailabilityProviderNeverReportsAnUnprovisionedModelAsReady() =
        runTest {
            val provider =
                LocalModelAvailabilityProvider(
                    aicoreProbe = LocalRuntimeProbe { LocalProbeResult.DownloadRequired },
                    installer = LocalModelInstaller(InMemoryLocalModelFileStore()),
                )

            val models = provider.observe(ModelTask.LANGUAGE_MODEL).collectFirst()

            assertEquals(1, models.size)
            assertFalse(models.single() is ModelAvailability.Ready)
        }

    @Test
    fun theAvailabilityProviderReportsReadyOnlyWhenTheProbeIsReady() =
        runTest {
            val provider =
                LocalModelAvailabilityProvider(
                    aicoreProbe = LocalRuntimeProbe { LocalProbeResult.Ready },
                    installer = LocalModelInstaller(InMemoryLocalModelFileStore()),
                )

            val models = provider.observe(ModelTask.LANGUAGE_MODEL).collectFirst()

            assertTrue(models.single() is ModelAvailability.Ready)
            assertEquals(LocalModels.GEMINI_NANO_ID, models.single().model.id)
        }

    private suspend fun kotlinx.coroutines.flow.Flow<List<ModelAvailability>>.collectFirst(): List<ModelAvailability> {
        var result: List<ModelAvailability>? = null
        collect { result = it }
        return result ?: error("the flow emitted nothing")
    }
}
