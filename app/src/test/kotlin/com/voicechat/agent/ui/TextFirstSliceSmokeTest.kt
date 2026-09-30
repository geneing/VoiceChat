package com.voicechat.agent.ui

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.consume
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.InMemoryCredentialStore
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.providers.AuthMethod
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.RegisteredProviderLanguageModelFactory
import com.voicechat.agent.remote.OkHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.settings.VoiceSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Opt-in only.** This is the one M23 test that touches the real OpenCode Go
 * service, through the same registry-driven factory and selection resolver the
 * app uses.
 *
 * It is skipped — never run — unless **both** `VOICECHAT_TEXT_SLICE_SMOKE=1` and
 * `OPENCODE_API_KEY` are set, so routine CI and `:app:testDebugUnitTest` never
 * make a provider call. The key is supplied externally and only ever reaches the
 * `Authorization` header. The test asserts on completion, delta count, and the
 * reported model only; it never prints or asserts on prompt or response text.
 *
 * Run it deliberately, with recorded network conditions, via:
 *
 * ```
 * $env:VOICECHAT_TEXT_SLICE_SMOKE = "1"
 * $env:OPENCODE_API_KEY = "<your key>"
 * $env:VOICECHAT_TEXT_SLICE_SMOKE_MODEL = "glm-5.3-flash" # optional
 * .\gradlew.bat :app:testDebugUnitTest --tests "*TextFirstSliceSmokeTest"
 * ```
 *
 * This is a **host/JVM** smoke of the app wiring. It does **not** replace the
 * opt-in Pixel 10 run documented in [Tests.md](../../../../../../Tests.md); that
 * device run was not performed for M23.
 */
class TextFirstSliceSmokeTest {
    @Test
    fun aRealProviderTurnCompletesThroughTheAppFactoryAndSelectionResolver() =
        runTest(timeout = 120.seconds) {
            val apiKey = System.getenv("OPENCODE_API_KEY")
            val enabled = System.getenv("VOICECHAT_TEXT_SLICE_SMOKE") == "1"
            assumeTrue(
                "opt-in smoke: set VOICECHAT_TEXT_SLICE_SMOKE=1 and OPENCODE_API_KEY to run it",
                enabled && !apiKey.isNullOrBlank(),
            )
            val modelId =
                System.getenv("VOICECHAT_TEXT_SLICE_SMOKE_MODEL")?.takeIf { it.isNotBlank() }
                    ?: "glm-5.3-flash"
            val registry = ProviderCapabilityRegistry.verifiedDefaults()
            val credentials =
                InMemoryCredentialStore().apply {
                    store(Credential(KnownProviders.OPENCODE_GO, CredentialKind.API_KEY, apiKey!!))
                }
            val transport = RemoteTransport(OkHttpStreamingEngine())
            val factory = RegisteredProviderLanguageModelFactory(registry, credentials, transport)
            val settings =
                VoiceSettings(
                    llmProviderId = KnownProviders.OPENCODE_GO,
                    llmModelId = ModelId(modelId),
                    llmAuthMethod = AuthMethod.API_KEY,
                )

            val active =
                ProviderTurnResolver.resolve(
                    settings = settings,
                    conversationId = ConversationId("smoke-conversation"),
                    registry = registry,
                    factory = factory,
                )
            assertTrue("the persisted selection did not resolve to an adapter", active.configured)

            try {
                val result =
                    active.languageModel.consume(
                        LlmRequest(
                            model = active.selection,
                            messages = listOf(LlmMessage(LlmRole.USER, "Reply with one short word.")),
                            reasoning = active.reasoning,
                        ),
                    )
                assertTrue(
                    "the real stream did not complete: reason=${result.failureReason} code=${result.error?.code}",
                    result.completed,
                )
                assertTrue("no deltas arrived", result.deltaCount >= 1)
            } finally {
                active.languageModel.close()
            }
        }
}
