package com.voicechat.agent.providers

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.consume
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.InMemoryCredentialStore
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.providers.opencodego.OpenCodeGoFixtures
import com.voicechat.agent.providers.opencodego.OpenCodeGoLanguageModel
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.remote.scriptedResponse
import com.voicechat.agent.settings.VoiceSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M23 unit tests for the registry-driven provider factory and disclosure.
 *
 * Everything is credential-free and network-free: a fake HTTP engine scripts the
 * bytes (or a recorded SSE fixture) and the M13 credential store is in-memory.
 */
class ProviderLanguageModelFactoryTest {
    private val registry = ProviderCapabilityRegistry.verifiedDefaults()
    private val secret = "oc-go-DO-NOT-LEAK-0123456789"

    private suspend fun credentials(): InMemoryCredentialStore =
        InMemoryCredentialStore().apply {
            store(Credential(KnownProviders.OPENCODE_GO, CredentialKind.API_KEY, secret))
        }

    private fun factory(
        credentials: InMemoryCredentialStore,
        engine: FakeHttpStreamingEngine,
    ) = RegisteredProviderLanguageModelFactory(registry, credentials, RemoteTransport(engine))

    @Test
    fun anOpenCodeGoSelectionBuildsTheAdapterWithAConversationScopedSession() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = OpenCodeGoFixtures.text("chat_normal.sse")) }
            val selection = ProviderModelSelection(KnownProviders.OPENCODE_GO, ModelId("glm-5.3-flash"))

            val model =
                factory(credentials(), engine).create(
                    selection = selection,
                    sessionHint = "conversation-abc",
                    configuredServerUrl = null,
                )

            assertNotNull(model)
            assertEquals(KnownProviders.OPENCODE_GO, model!!.providerId)
            model.consume(
                LlmRequest(model = selection, messages = listOf(LlmMessage(LlmRole.USER, "hello"))),
            )
            val sent = engine.requests.single()
            assertEquals("https://opencode.ai/zen/go/v1/chat/completions", sent.url)
            // The OpenCode Go session hint is conversation-scoped, not per-instance.
            assertEquals(
                "voicechat-conversation-abc",
                sent.headers.first { it.name == OpenCodeGoLanguageModel.SESSION_HEADER }.value,
            )
        }

    @Test
    fun eachKnownProviderWithADestinationResolvesToItsAdapter() {
        val engine = FakeHttpStreamingEngine { scriptedResponse() }
        val factory = RegisteredProviderLanguageModelFactory(registry, InMemoryCredentialStore(), RemoteTransport(engine))
        val fixed =
            listOf(
                KnownProviders.OPENAI,
                KnownProviders.OPENROUTER,
                KnownProviders.OPENCODE_GO,
                KnownProviders.OPENCODE_ZEN,
                KnownProviders.DEEPSEEK,
            )

        fixed.forEach { providerId ->
            val model = factory.create(ProviderModelSelection(providerId, ModelId("some-model")), null, null)
            assertNotNull("no adapter for ${providerId.value}", model)
            assertEquals(providerId, model!!.providerId)
        }
    }

    @Test
    fun aConfigurableProviderNeedsAValidatedDestination() {
        val engine = FakeHttpStreamingEngine { scriptedResponse() }
        val factory = RegisteredProviderLanguageModelFactory(registry, InMemoryCredentialStore(), RemoteTransport(engine))
        val hermes = ProviderModelSelection(KnownProviders.HERMES, ModelId("default"))

        // Hermes has no default endpoint: with no address there is no adapter.
        assertNull(factory.create(hermes, null, null))
        // With a validated https address it resolves.
        assertNotNull(factory.create(hermes, null, "https://hermes.example.com"))
        // A plain-http non-loopback address is refused (TLS required), so no adapter.
        assertNull(factory.create(hermes, null, "http://hermes.example.com"))
    }

    @Test
    fun anUnknownProviderHasNoAdapterAndKeepsTheHonestNotConfiguredState() {
        val engine = FakeHttpStreamingEngine { scriptedResponse() }
        val factory = RegisteredProviderLanguageModelFactory(registry, InMemoryCredentialStore(), RemoteTransport(engine))

        assertNull(factory.create(ProviderModelSelection(ProviderId("not-a-provider"), ModelId("m")), null, null))
    }

    @Test
    fun theDisclosureNamesTheSelectedProviderAndModel() {
        val settings =
            VoiceSettings(
                llmProviderId = KnownProviders.OPENCODE_GO,
                llmModelId = ModelId("glm-5.3-flash"),
            )

        val disclosure = ProviderDisclosure.from(settings, registry)

        assertTrue(disclosure.hasSelection)
        assertEquals("OpenCode Go", disclosure.providerDisplayName)
        assertEquals("glm-5.3-flash", disclosure.modelId)
    }

    @Test
    fun theDisclosureIsNoneUntilProviderAndModelAreBothSelected() {
        // An empty selection has no identity to name.
        assertEquals(ProviderDisclosure.NONE, ProviderDisclosure.from(VoiceSettings(sttLocaleLanguageTag = "en-US"), registry))
        assertEquals(
            ProviderDisclosure.NONE,
            ProviderDisclosure.from(VoiceSettings(llmProviderId = KnownProviders.OPENCODE_GO), registry),
        )
        assertEquals(
            ProviderDisclosure.NONE,
            ProviderDisclosure.from(
                VoiceSettings(llmProviderId = ProviderId("unknown"), llmModelId = ModelId("m")),
                registry,
            ),
        )
    }
}
