package com.voicechat.agent.providers.openrouter

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.consume
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.InMemoryCredentialStore
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.remote.OkHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/**
 * **Opt-in only.** This is the one M15 test that touches the real OpenRouter
 * service.
 *
 * It is skipped — never failed, never run — unless **both**
 * `VOICECHAT_OPENROUTER_SMOKE=1` and `OPENROUTER_API_KEY` are set in the
 * environment, so routine CI and `:app:testDebugUnitTest` never make a provider
 * call. The key is supplied externally and only ever reaches the `Authorization`
 * header; the test asserts on completion, model identity, and counts, never on
 * content, and its assertion messages carry no prompt or response text.
 *
 * Run it deliberately, with recorded device/network conditions, via:
 *
 * ```
 * $env:VOICECHAT_OPENROUTER_SMOKE = "1"
 * $env:OPENROUTER_API_KEY = "<your key>"
 * $env:VOICECHAT_OPENROUTER_SMOKE_MODEL = "anthropic/claude-sonnet-4.5"   # optional
 * .\gradlew.bat :app:testDebugUnitTest --tests "*OpenRouterSmokeTest"
 * ```
 */
class OpenRouterSmokeTest {
    @Test
    fun aRealOpenRouterChatCompletionsStreamCompletes() =
        runTest(timeout = 120.seconds) {
            val apiKey = System.getenv("OPENROUTER_API_KEY")
            val enabled = System.getenv("VOICECHAT_OPENROUTER_SMOKE") == "1"
            assumeTrue(
                "opt-in smoke test: set VOICECHAT_OPENROUTER_SMOKE=1 and OPENROUTER_API_KEY to run it",
                enabled && !apiKey.isNullOrBlank(),
            )
            val modelId =
                System.getenv("VOICECHAT_OPENROUTER_SMOKE_MODEL")?.takeIf { it.isNotBlank() }
                    ?: "anthropic/claude-sonnet-4.5"
            val store =
                InMemoryCredentialStore().apply {
                    store(Credential(KnownProviders.OPENROUTER, CredentialKind.API_KEY, apiKey!!))
                }
            val engine = OkHttpStreamingEngine()
            val model = OpenRouterLanguageModel(store, RemoteTransport(engine))
            try {
                val result =
                    model.consume(
                        LlmRequest(
                            model = ProviderModelSelection(KnownProviders.OPENROUTER, ModelId(modelId)),
                            messages = listOf(LlmMessage(LlmRole.USER, "Reply with one short word.")),
                        ),
                    )
                assertTrue(
                    "the real stream did not complete: reason=${result.failureReason} code=${result.error?.code}",
                    result.completed,
                )
                // Routing is constrained, so the reported model must be the selected one.
                assertTrue(
                    "the provider reported a different model than the selection: ${result.model}",
                    result.model == null || result.model == ModelId(modelId),
                )
            } finally {
                model.close()
            }
        }
}
