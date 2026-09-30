package com.voicechat.agent.settings

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
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
 * M22 acceptance for capability-aware option building:
 *
 * - an unsupported capability (undocumented auth method, unsupported reasoning
 *   level, network TTS voice) is **never** produced;
 * - a known but currently unavailable entry is produced **disabled with a
 *   reason**;
 * - a ready entry is selectable.
 */
class SettingsOptionsTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()

    @Test
    fun undocumentedAuthMethodsAreAbsentNotDisabled() {
        val openAi = registry.capabilities(KnownProviders.OPENAI)!!
        val openRouter = registry.capabilities(KnownProviders.OPENROUTER)!!

        assertEquals(
            listOf(AuthMethod.API_KEY),
            SettingsOptions.authMethods(openAi, selected = null).map { it.value },
        )
        assertEquals(
            listOf(AuthMethod.API_KEY, AuthMethod.OAUTH_PKCE),
            SettingsOptions.authMethods(openRouter, selected = null).map { it.value },
        )
        // The enum itself has no QR value, so no provider can produce one.
        assertTrue(SettingsOptions.authMethods(openRouter, null).none { it.value.name.contains("QR") })
    }

    @Test
    fun unsupportedReasoningLevelsAreHiddenAndOnlyTheReconciledOnesAreOffered() {
        val openAi = registry.capabilities(KnownProviders.OPENAI)!!
        val model =
            ModelCapabilities(
                providerId = KnownProviders.OPENAI,
                modelId = ModelId("gpt-test"),
                streaming = true,
                usageReporting = true,
                reasoningLevels = setOf(ReasoningLevel.NONE, ReasoningLevel.HIGH),
            )

        val offered = SettingsOptions.reasoningLevels(openAi, model, selected = null).map { it.value }

        assertEquals(listOf(ReasoningLevel.NONE, ReasoningLevel.HIGH), offered)
        // A level the model does not expose is absent, not shown-as-disabled.
        assertFalse(offered.contains(ReasoningLevel.MEDIUM))
        assertTrue(SettingsOptions.hasReasoningChoices(openAi, model))
    }

    @Test
    fun anUnknownModelOffersOnlyTheAlwaysAvailableDefault() {
        val openAi = registry.capabilities(KnownProviders.OPENAI)!!

        val offered = SettingsOptions.reasoningLevels(openAi, model = null, selected = null).map { it.value }

        assertEquals(listOf(ReasoningLevel.NONE), offered)
        assertFalse(SettingsOptions.hasReasoningChoices(openAi, null))
    }

    @Test
    fun networkTtsVoicesAreAbsentAndEmbeddedVoicesAreSelectable() {
        val embedded = TtsVoice("voice-on-device", "On-device", Locale.US, requiresNetwork = false)
        val network = TtsVoice("voice-cloud", "Cloud", Locale.US, requiresNetwork = true)

        val options = SettingsOptions.ttsVoices(listOf(embedded, network), selectedId = null)

        assertEquals(listOf("voice-on-device"), options.map { it.value.id })
        assertTrue(options.single().isAvailable)
    }

    @Test
    fun unavailableModelsAreShownDisabledWithAReason() {
        val ready = descriptor("model-ready", "Ready model")
        val downloadable = descriptor("model-download", "Downloadable model")
        val unavailable = descriptor("model-blocked", "Blocked model")
        val availabilities =
            listOf(
                ModelAvailability.Ready(ready),
                ModelAvailability.DownloadRequired(downloadable),
                ModelAvailability.Unavailable(
                    unavailable,
                    VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE, detail = "not supported on this device"),
                ),
            )

        val options = SettingsOptions.models(availabilities, selected = ready.id)

        assertEquals(3, options.size)
        assertTrue(options[0].isAvailable)
        assertTrue(options[0].isSelected)
        assertEquals("Model download required", options[1].unavailableReason)
        assertEquals("not supported on this device", options[2].unavailableReason)
    }

    @Test
    fun unavailableSttModesAreShownDisabledWithAReason() {
        val advanced = SttEngine(SttMode.ADVANCED, Locale.US)
        val basic = SttEngine(SttMode.BASIC, Locale.US)
        val availabilities =
            listOf(
                SttAvailability.DownloadRequired(advanced),
                SttAvailability.Unavailable(
                    basic,
                    reason = com.voicechat.agent.domain.UnavailableReason.DEVICE_UNSUPPORTED,
                    error = VoiceAgentError(ErrorCode.STT_UNAVAILABLE, detail = "needs API 31+"),
                ),
            )

        val options = SettingsOptions.sttModes(availabilities, selected = SttMode.ADVANCED)

        assertEquals("Model download required", options[0].unavailableReason)
        assertFalse(options[0].isAvailable)
        assertTrue(options[0].isSelected)
        assertEquals("needs API 31+", options[1].unavailableReason)
    }

    @Test
    fun providersAreAllSelectableAndMarkTheSelection() {
        val options = SettingsOptions.providers(registry, selected = KnownProviders.DEEPSEEK)

        assertEquals(registry.all().size, options.size)
        assertTrue(options.all { it.isAvailable })
        assertEquals(
            KnownProviders.DEEPSEEK,
            options.single { it.isSelected }.value.providerId,
        )
    }

    @Test
    fun capabilitiesForReturnsNullUntilBothIdsAreKnown() {
        val catalog =
            StaticModelCapabilityCatalog(
                listOf(
                    ModelCapabilities(
                        KnownProviders.OPENAI,
                        ModelId("gpt-test"),
                        streaming = true,
                        usageReporting = true,
                        reasoningLevels = setOf(ReasoningLevel.HIGH),
                    ),
                ),
            )

        assertNull(catalog.capabilitiesFor(KnownProviders.OPENAI, ModelId("gpt-unknown")))
        assertNull(catalog.capabilitiesFor(null, ModelId("gpt-test")))
        assertEquals(
            setOf(ReasoningLevel.HIGH),
            catalog.capabilitiesFor(KnownProviders.OPENAI, ModelId("gpt-test"))!!.reasoningLevels,
        )
    }

    private fun descriptor(
        id: String,
        name: String,
    ): ModelDescriptor =
        ModelDescriptor(
            id = ModelId(id),
            task = ModelTask.LANGUAGE_MODEL,
            displayName = name,
            runtime = ModelRuntime.REMOTE_API,
            providerId = KnownProviders.OPENAI,
        )
}
