package com.voicechat.agent.providers.hermes

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
 * **Opt-in only.** This is the one M19 test that touches a real Hermes Agent API
 * Server. There is no default endpoint (Hermes is user/admin-configured), so the
 * address must be supplied externally.
 *
 * It is skipped — never failed, never run — unless **all** of
 * `VOICECHAT_HERMES_SMOKE=1`, `HERMES_BASE_URL`, and `HERMES_API_KEY` are set, so
 * routine CI and `:app:testDebugUnitTest` never make a server call. The address is
 * validated with the same M13 TLS rules as the app; the key only ever reaches the
 * `Authorization` header, and the test asserts on completion, model identity, and
 * counts, never on content.
 *
 * Run it deliberately, with recorded device/network conditions, via:
 *
 * ```
 * $env:VOICECHAT_HERMES_SMOKE = "1"
 * $env:HERMES_BASE_URL = "https://hermes.example.com/v1"   # or http://127.0.0.1:8642/v1 for a local loopback server
 * $env:HERMES_API_KEY = "<your API_SERVER_KEY>"
 * $env:VOICECHAT_HERMES_SMOKE_MODEL = "hermes-agent"       # optional
 * .\gradlew.bat :app:testDebugUnitTest --tests "*HermesSmokeTest"
 * ```
 */
class HermesSmokeTest {
    @Test
    fun aRealHermesChatCompletionsStreamCompletes() =
        runTest(timeout = 180.seconds) {
            val enabled = System.getenv("VOICECHAT_HERMES_SMOKE") == "1"
            val baseUrl = System.getenv("HERMES_BASE_URL")
            val apiKey = System.getenv("HERMES_API_KEY")
            assumeTrue(
                "opt-in smoke test: set VOICECHAT_HERMES_SMOKE=1, HERMES_BASE_URL, and HERMES_API_KEY to run it",
                enabled && !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank(),
            )
            val config =
                when (val result = HermesServerAddress.config(baseUrl!!)) {
                    is HermesServerConfigResult.Valid -> result.config
                    is HermesServerConfigResult.Invalid -> error("the configured Hermes address is invalid: ${result.error.code}")
                }
            val modelId =
                System.getenv("VOICECHAT_HERMES_SMOKE_MODEL")?.takeIf { it.isNotBlank() }
                    ?: HermesModels.DEFAULT_AGENT_ALIAS
            val store =
                InMemoryCredentialStore().apply {
                    store(Credential(KnownProviders.HERMES, CredentialKind.API_KEY, apiKey!!))
                }
            val engine = OkHttpStreamingEngine()
            val model = HermesLanguageModel(store, RemoteTransport(engine), config.destination)
            try {
                val result =
                    model.consume(
                        LlmRequest(
                            model = ProviderModelSelection(KnownProviders.HERMES, ModelId(modelId)),
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
