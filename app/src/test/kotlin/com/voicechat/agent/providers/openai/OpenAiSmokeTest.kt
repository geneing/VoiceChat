package com.voicechat.agent.providers.openai

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
 * **Opt-in only.** This is the one M14 test that touches the real OpenAI service.
 *
 * It is skipped — never failed, never run — unless **both**
 * `VOICECHAT_OPENAI_SMOKE=1` and `OPENAI_API_KEY` are set in the environment, so
 * routine CI and `:app:testDebugUnitTest` never make a provider call. The key is
 * supplied externally and only ever reaches the `Authorization` header; the test
 * asserts on completion and counts, never on content, and its assertion messages
 * carry no prompt or response text.
 *
 * Run it deliberately, with recorded device/network conditions, via:
 *
 * ```
 * $env:VOICECHAT_OPENAI_SMOKE = "1"
 * $env:OPENAI_API_KEY = "<your key>"
 * $env:VOICECHAT_OPENAI_SMOKE_MODEL = "gpt-5.6-luna"   # optional
 * .\gradlew.bat :app:testDebugUnitTest --tests "*OpenAiSmokeTest"
 * ```
 */
class OpenAiSmokeTest {
    @Test
    fun aRealOpenAiResponsesStreamCompletes() =
        runTest(timeout = 120.seconds) {
            val apiKey = System.getenv("OPENAI_API_KEY")
            val enabled = System.getenv("VOICECHAT_OPENAI_SMOKE") == "1"
            assumeTrue(
                "opt-in smoke test: set VOICECHAT_OPENAI_SMOKE=1 and OPENAI_API_KEY to run it",
                enabled && !apiKey.isNullOrBlank(),
            )
            val modelId = System.getenv("VOICECHAT_OPENAI_SMOKE_MODEL")?.takeIf { it.isNotBlank() } ?: "gpt-5.6-luna"
            val store =
                InMemoryCredentialStore().apply {
                    store(Credential(KnownProviders.OPENAI, CredentialKind.API_KEY, apiKey!!))
                }
            val engine = OkHttpStreamingEngine()
            val model = OpenAiLanguageModel(store, RemoteTransport(engine))
            try {
                val result =
                    model.consume(
                        LlmRequest(
                            model = ProviderModelSelection(KnownProviders.OPENAI, ModelId(modelId)),
                            messages = listOf(LlmMessage(LlmRole.USER, "Reply with one short word.")),
                        ),
                    )
                assertTrue(
                    "the real stream did not complete: reason=${result.failureReason} code=${result.error?.code}",
                    result.completed,
                )
            } finally {
                model.close()
            }
        }
}
