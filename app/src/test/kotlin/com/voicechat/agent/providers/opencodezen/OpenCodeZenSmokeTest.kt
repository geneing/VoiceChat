package com.voicechat.agent.providers.opencodezen

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
 * **Opt-in only.** This is the one M18 test that touches the real OpenCode Zen
 * service.
 *
 * It is skipped — never failed, never run — unless **both**
 * `VOICECHAT_OPENCODE_ZEN_SMOKE=1` and `OPENCODE_ZEN_API_KEY` are set in the
 * environment, so routine CI and `:app:testDebugUnitTest` never make a provider
 * call. The key is supplied externally and only ever reaches the `Authorization`
 * header; the test asserts on completion and counts, never on content, and its
 * assertion messages carry no prompt or response text.
 *
 * The default model is a Chat Completions-family id; set
 * `VOICECHAT_OPENCODE_ZEN_SMOKE_MODEL` to smoke a Responses or Messages family
 * (`gpt-5.6-luna`, `claude-sonnet-4-5`) individually.
 *
 * Run it deliberately, with recorded device/network conditions, via:
 *
 * ```
 * $env:VOICECHAT_OPENCODE_ZEN_SMOKE = "1"
 * $env:OPENCODE_ZEN_API_KEY = "<your key>"
 * $env:VOICECHAT_OPENCODE_ZEN_SMOKE_MODEL = "glm-5.3-flash" # optional
 * .\gradlew.bat :app:testDebugUnitTest --tests "*OpenCodeZenSmokeTest"
 * ```
 */
class OpenCodeZenSmokeTest {
    @Test
    fun aRealOpenCodeZenStreamCompletes() =
        runTest(timeout = 120.seconds) {
            val apiKey = System.getenv("OPENCODE_ZEN_API_KEY")
            val enabled = System.getenv("VOICECHAT_OPENCODE_ZEN_SMOKE") == "1"
            assumeTrue(
                "opt-in smoke test: set VOICECHAT_OPENCODE_ZEN_SMOKE=1 and OPENCODE_ZEN_API_KEY to run it",
                enabled && !apiKey.isNullOrBlank(),
            )
            val modelId =
                System.getenv("VOICECHAT_OPENCODE_ZEN_SMOKE_MODEL")?.takeIf { it.isNotBlank() }
                    ?: "glm-5.3-flash"
            val store =
                InMemoryCredentialStore().apply {
                    store(Credential(KnownProviders.OPENCODE_ZEN, CredentialKind.API_KEY, apiKey!!))
                }
            val model = OpenCodeZenLanguageModel(store, RemoteTransport(OkHttpStreamingEngine()))
            try {
                val result =
                    model.consume(
                        LlmRequest(
                            model = ProviderModelSelection(KnownProviders.OPENCODE_ZEN, ModelId(modelId)),
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
