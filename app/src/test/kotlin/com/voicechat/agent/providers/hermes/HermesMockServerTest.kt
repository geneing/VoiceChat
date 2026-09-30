package com.voicechat.agent.providers.hermes

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.consume
import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.CredentialStore
import com.voicechat.agent.credentials.InMemoryCredentialStore
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ServerDestination
import com.voicechat.agent.remote.OkHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

/**
 * M19 integration acceptance over an **in-process loopback Hermes mock server**
 * (no external network, no real credential, no device).
 *
 * These tests drive the real [OkHttpStreamingEngine] and [RemoteTransport]
 * against [FakeHermesServer], proving the documented endpoint path, bearer
 * authentication, the SSE stream shape, typed server errors, that a 302 is never
 * followed (R-0074), and that cancelling collection tears the stream down. The
 * deterministic fixture suite in [HermesLanguageModelTest] covers the remaining
 * protocol cases without a socket.
 */
class HermesMockServerTest {
    private val secret = "hermes-local-test-key"

    @Test
    fun aRealStreamOverLoopbackCompletesWithTheDocumentedShape() =
        runBlocking {
            FakeHermesServer(apiKey = secret).use { server ->
                val engine = OkHttpStreamingEngine()
                try {
                    val model = model(server, engine, secret)
                    val result = model.consume(request())

                    assertTrue(result.completed)
                    assertEquals("Hello, world", result.text)
                    assertEquals(ModelId("hermes-agent"), result.model)
                    assertEquals(19, result.usage?.totalTokens)

                    val sent = server.requests.single()
                    assertEquals("POST", sent.method)
                    assertEquals("/v1/chat/completions", sent.path)
                    assertEquals("Bearer $secret", sent.authorization)
                    assertTrue(sent.body.contains("\"stream\":true"))
                } finally {
                    engine.close()
                }
            }
        }

    @Test
    fun aWrongKeyIsAnAuthenticationFailure() =
        runBlocking {
            FakeHermesServer(apiKey = secret).use { server ->
                val engine = OkHttpStreamingEngine()
                try {
                    val model = model(server, engine, "wrong-key")
                    val result = model.consume(request())

                    assertTrue(!result.completed)
                    assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, result.error?.code)
                } finally {
                    engine.close()
                }
            }
        }

    @Test
    fun aServerErrorIsUnavailable() =
        runBlocking {
            FakeHermesServer(apiKey = secret, behavior = FakeHermesBehavior.SERVER_ERROR).use { server ->
                val engine = OkHttpStreamingEngine()
                try {
                    val result = model(server, engine, secret).consume(request())

                    assertTrue(!result.completed)
                    assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
                } finally {
                    engine.close()
                }
            }
        }

    @Test
    fun aRedirectIsNeverFollowed() =
        runBlocking {
            FakeHermesServer(apiKey = secret, behavior = FakeHermesBehavior.REDIRECT).use { server ->
                val engine = OkHttpStreamingEngine()
                try {
                    val result = model(server, engine, secret).consume(request())

                    assertTrue(!result.completed)
                    // A 302 is surfaced as a typed failure, not followed.
                    assertEquals(ErrorCode.LLM_REQUEST_FAILED, result.error?.code)
                    assertEquals("the redirect target must never be reached", 0, server.redirectHits.get())
                    assertEquals(1, server.requests.size)
                } finally {
                    engine.close()
                }
            }
        }

    @Test
    fun cancellingARealStalledStreamStopsItWithoutATerminalEvent() =
        runBlocking {
            FakeHermesServer(apiKey = secret, behavior = FakeHermesBehavior.STALL).use { server ->
                val engine = OkHttpStreamingEngine()
                try {
                    val model = model(server, engine, secret)
                    val events = Collections.synchronizedList(mutableListOf<LlmStreamEvent>())
                    val job =
                        launch(Dispatchers.IO) {
                            model.stream(request()).collect { events += it }
                        }
                    withContext(Dispatchers.Default) { delay(500) }
                    job.cancel()
                    job.join()

                    assertTrue("the first delta must have arrived", events.any { it is LlmStreamEvent.Delta })
                    assertTrue("no terminal event may follow a cancellation", events.none { it !is LlmStreamEvent.Delta })
                } finally {
                    engine.close()
                }
            }
        }

    private suspend fun model(
        server: FakeHermesServer,
        engine: OkHttpStreamingEngine,
        secret: String,
    ): HermesLanguageModel {
        val store: CredentialStore =
            InMemoryCredentialStore().apply {
                store(Credential(KnownProviders.HERMES, CredentialKind.API_KEY, secret))
            }
        return HermesLanguageModel(store, RemoteTransport(engine), destination(server.baseUrl))
    }

    private fun request() =
        LlmRequest(
            model = ProviderModelSelection(KnownProviders.HERMES, ModelId("hermes-agent")),
            messages = listOf(LlmMessage(LlmRole.USER, "hello")),
        )

    private fun destination(raw: String): ServerDestination =
        when (val result = HermesServerAddress.config(raw)) {
            is HermesServerConfigResult.Valid -> result.config.destination
            is HermesServerConfigResult.Invalid -> error("loopback destination was invalid: ${result.error.code}")
        }
}
