package com.voicechat.agent.settings

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
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
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.tts.TtsVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * M22 acceptance for selection validation: a stored selection that the registry
 * or runtime no longer supports is cleared (never trusted), and a valid one is
 * preserved. This is what makes "persist only validated selections" mechanical.
 */
class SettingsValidatorTest {
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
        stt: List<SttAvailability> = listOf(SttAvailability.Ready(sttEngine(SttMode.ADVANCED))),
        voices: List<TtsVoice> = listOf(embeddedVoice()),
        smartTurn: SmartTurnState = SmartTurnState.Available,
    ) = SettingsCapabilities(sttAvailability = stt, ttsVoices = voices, smartTurn = smartTurn, models = models)

    @Test
    fun aFullyValidSelectionIsPreserved() {
        val settings =
            VoiceSettings(
                sttMode = SttMode.ADVANCED,
                llmProviderId = KnownProviders.OPENAI,
                llmModelId = modelId,
                llmAuthMethod = AuthMethod.API_KEY,
                reasoningLevel = ReasoningLevel.HIGH,
                ttsVoiceId = embeddedVoice().id,
                smartTurnEnabled = true,
            )

        val validation = SettingsValidator.validate(settings, registry, capabilities(), modelCatalog)

        assertTrue(validation.isValid)
        assertEquals(settings, validation.settings)
    }

    @Test
    fun anUnknownProviderIsCleared() {
        val settings =
            VoiceSettings(
                llmProviderId = ProviderId("not-a-provider"),
                llmModelId = modelId,
                llmAuthMethod = AuthMethod.API_KEY,
                reasoningLevel = ReasoningLevel.HIGH,
            )

        val validation = SettingsValidator.validate(settings, registry, capabilities(), modelCatalog)

        assertEquals(listOf(InvalidSelection.PROVIDER), validation.invalid)
        assertNull(validation.settings.llmProviderId)
        assertNull(validation.settings.llmModelId)
        assertNull(validation.settings.llmAuthMethod)
        assertNull(validation.settings.reasoningLevel)
    }

    @Test
    fun anUndocumentedAuthMethodIsCleared() {
        val settings =
            VoiceSettings(
                llmProviderId = KnownProviders.OPENAI,
                llmAuthMethod = AuthMethod.OAUTH_PKCE, // OpenAI documents API key only
            )

        val validation = SettingsValidator.validate(settings, registry, capabilities(), modelCatalog)

        assertEquals(listOf(InvalidSelection.AUTH_METHOD), validation.invalid)
        assertNull(validation.settings.llmAuthMethod)
    }

    @Test
    fun aModelNotOfferedByTheProviderIsCleared() {
        val settings =
            VoiceSettings(
                llmProviderId = KnownProviders.OPENAI,
                llmModelId = ModelId("not-offered"),
                reasoningLevel = ReasoningLevel.HIGH,
            )

        val validation = SettingsValidator.validate(settings, registry, capabilities(), modelCatalog)

        assertEquals(listOf(InvalidSelection.MODEL), validation.invalid)
        assertNull(validation.settings.llmModelId)
        assertNull(validation.settings.reasoningLevel)
    }

    @Test
    fun anUnavailableModelIsCleared() {
        val settings = VoiceSettings(llmProviderId = KnownProviders.OPENAI, llmModelId = modelId)
        val availability =
            listOf(
                ModelAvailability.Unavailable(model, VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE)),
            )

        val validation = SettingsValidator.validate(settings, registry, capabilities(models = availability), modelCatalog)

        assertEquals(listOf(InvalidSelection.MODEL), validation.invalid)
        assertNull(validation.settings.llmModelId)
    }

    @Test
    fun anUnsupportedReasoningLevelIsCleared() {
        val settings =
            VoiceSettings(
                llmProviderId = KnownProviders.OPENAI,
                llmModelId = modelId,
                reasoningLevel = ReasoningLevel.LOW, // model exposes only HIGH
            )

        val validation = SettingsValidator.validate(settings, registry, capabilities(), modelCatalog)

        assertEquals(listOf(InvalidSelection.REASONING), validation.invalid)
        assertNull(validation.settings.reasoningLevel)
    }

    @Test
    fun anSttModeThatIsNotReadyIsCleared() {
        val settings = VoiceSettings(sttMode = SttMode.ADVANCED)
        val stt =
            listOf(
                SttAvailability.Unavailable(
                    sttEngine(SttMode.ADVANCED),
                    reason = com.voicechat.agent.domain.UnavailableReason.DEVICE_UNSUPPORTED,
                    error = VoiceAgentError(ErrorCode.STT_UNAVAILABLE),
                ),
            )

        val validation = SettingsValidator.validate(settings, registry, capabilities(stt = stt), modelCatalog)

        assertEquals(listOf(InvalidSelection.STT_MODE), validation.invalid)
        assertNull(validation.settings.sttMode)
    }

    @Test
    fun aNetworkOnlyTtsVoiceSelectionIsCleared() {
        val settings = VoiceSettings(ttsVoiceId = "voice-cloud")
        val network = TtsVoice("voice-cloud", "Cloud", Locale.US, requiresNetwork = true)

        val validation = SettingsValidator.validate(settings, registry, capabilities(voices = listOf(network)), modelCatalog)

        assertEquals(listOf(InvalidSelection.TTS_VOICE), validation.invalid)
        assertNull(validation.settings.ttsVoiceId)
    }

    @Test
    fun smartTurnIsClearedWhenUnavailableWithoutAnInvalidSelectionNotice() {
        val settings = VoiceSettings(smartTurnEnabled = true)
        val smartTurn = SmartTurnState.Unavailable("not installed")

        val validation = SettingsValidator.validate(settings, registry, capabilities(smartTurn = smartTurn), modelCatalog)

        // Clearing the on-by-default flag is a normal fallback, not a rejected
        // user selection: the detector is simply not installed on this device.
        assertTrue(validation.invalid.isEmpty())
        assertFalse(validation.settings.smartTurnEnabled)
    }

    @Test
    fun noAuthMethodInTheRegistryIsAQrMethod() {
        assertTrue(registry.allAuthMethods().none { it.name.contains("QR") })
    }

    private fun sttEngine(mode: SttMode) = SttEngine(mode, Locale.US)

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
