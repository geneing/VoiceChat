package com.voicechat.agent.providers.hermes

import com.voicechat.agent.contracts.LlmFailureReason
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
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.log.RecordingLogSink
import com.voicechat.agent.providers.EndpointSource
import com.voicechat.agent.providers.EndpointValidation
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.ServerDestination
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.HttpStreamingEngine
import com.voicechat.agent.remote.RecordedFixtures
import com.voicechat.agent.remote.RemoteHttpRequest
import com.voicechat.agent.remote.RemoteTransport
import com.voicechat.agent.remote.failingResponse
import com.voicechat.agent.remote.scriptedResponse
import com.voicechat.agent.remote.stallingResponse
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M19 acceptance for the Hermes adapter, driven by recorded SSE fixtures and a
 * scripted fake engine. No socket, DNS, clock, or real credential is involved.
 * Destination validation/TLS/disclosure uses the M13
 * [com.voicechat.agent.providers.ServerDestinationValidator] rules directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HermesLanguageModelTest {
    private val secret = "hermes-secret-DO-NOT-LEAK-0123456789"
    private val prompt = "my private medical question"
    private val modelId = ModelId("hermes-agent")
    private val destination = destination("https://hermes.example.com/v1")

    @After
    fun tearDown() {
        AppLog.reset()
    }

    private suspend fun credentialStore(): CredentialStore =
        InMemoryCredentialStore().apply {
            store(Credential(KnownProviders.HERMES, CredentialKind.API_KEY, secret))
        }

    private fun request(reasoning: ReasoningLevel? = null) =
        LlmRequest(
            model = ProviderModelSelection(KnownProviders.HERMES, modelId),
            messages = listOf(LlmMessage(LlmRole.USER, prompt)),
            reasoning = reasoning,
        )

    private fun adapter(
        engine: HttpStreamingEngine,
        store: CredentialStore,
        sessionId: String? = null,
    ) = HermesLanguageModel(store, RemoteTransport(engine), destination, sessionId = sessionId)

    private fun sseEngine(
        body: String,
        chunkSize: Int = body.length.coerceAtLeast(1),
    ) = FakeHttpStreamingEngine { scriptedResponse(body = body, chunkSize = chunkSize) }

    private fun fixture(
        name: String,
        chunkSize: Int = 7,
    ) = sseEngine(RecordedFixtures.text("llm/hermes/$name"), chunkSize)

    @Test
    fun aNormalStreamCompletesWithTextModelAndUsage() =
        runTest {
            val result = adapter(fixture("normal_stream.sse"), credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("Hello, world", result.text)
            assertEquals(2, result.deltaCount)
            assertEquals(modelId, result.model)
            assertEquals(19, result.usage?.totalTokens)
        }

    @Test
    fun theRequestGoesToTheConfiguredEndpointWithBearerAuthAndStreaming() =
        runTest {
            val sent = engineRequest(RecordedFixtures.text("llm/hermes/normal_stream.sse"), credentialStore())

            assertEquals("https://hermes.example.com/v1/chat/completions", sent.url)
            assertEquals("POST", sent.method)
            assertEquals("Bearer $secret", sent.headers.first { it.name == "Authorization" }.value)
            assertEquals("text/event-stream", sent.headers.first { it.name == "Accept" }.value)
            val body = requireNotNull(sent.body)
            assertTrue(body.contains("\"model\":\"hermes-agent\""))
            assertTrue(body.contains("\"stream\":true"))
            // No session id was supplied, so none is sent.
            assertTrue(sent.headers.none { it.name == HermesLanguageModel.SESSION_HEADER })
        }

    @Test
    fun anExplicitSessionIdIsSentAsTheDocumentedHeader() =
        runTest {
            val engine = sseEngine(RecordedFixtures.text("llm/hermes/normal_stream.sse"))
            adapter(engine, credentialStore(), sessionId = "transcript-alpha").consume(request())

            val sent = engine.requests.single()
            assertEquals("transcript-alpha", sent.headers.first { it.name == HermesLanguageModel.SESSION_HEADER }.value)
        }

    @Test
    fun theDestinationIsDisclosedBeforeAnyRequest() {
        val model =
            HermesLanguageModel(
                InMemoryCredentialStore(),
                RemoteTransport(FakeHttpStreamingEngine { scriptedResponse() }),
                destination,
            )

        assertEquals("https://hermes.example.com/v1", model.destinationDisclosure)
    }

    @Test
    fun anEmptyResponseCompletesWithNoDeltas() =
        runTest {
            val result = adapter(fixture("empty_response.sse"), credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("", result.text)
            assertEquals(0, result.deltaCount)
            assertEquals(5, result.usage?.totalTokens)
        }

    @Test
    fun aMalformedFrameFailsWithMalformedResponseAndKeepsThePrefix() =
        runTest {
            val result = adapter(fixture("malformed_frame.sse"), credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("ok", result.text)
        }

    @Test
    fun aTerminalLessStreamIsNotACompletion() =
        runTest {
            val result = adapter(fixture("terminal_less.sse"), credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("partial answer", result.text)
        }

    @Test
    fun aTruncatedResponseIsAFailureNotACompletionButKeepsThePartialText() =
        runTest {
            val result = adapter(fixture("truncated.sse"), credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("partial", result.text)
        }

    @Test
    fun httpStatusesMapToTheirTypedReasons() =
        runTest {
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, statusError(401))
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, statusError(403))
            assertEquals(ErrorCode.LLM_RATE_LIMITED, statusError(429))
            assertEquals(ErrorCode.LLM_UNAVAILABLE, statusError(503))
        }

    @Test
    fun midStreamErrorEventsMapToTheirTypedReasons() =
        runTest {
            assertEquals(ErrorCode.LLM_UNAVAILABLE, fixtureError("mid_stream_error.sse"))
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, fixtureError("auth_error_event.sse"))
            assertEquals(ErrorCode.LLM_RATE_LIMITED, fixtureError("rate_limit_event.sse"))
        }

    @Test
    fun aMidStreamServerErrorKeepsThePartialText() =
        runTest {
            val result = adapter(fixture("mid_stream_error.sse"), credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals("partial", result.text)
            assertTrue(result.isPartial)
            assertEquals(LlmFailureReason.UNAVAILABLE, result.failureReason)
        }

    @Test
    fun aNetworkLossKeepsThePartialTextAndReportsNetwork() =
        runTest {
            val prefix =
                "data: {\"id\":\"chatcmpl-x\",\"model\":\"hermes-agent\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
            val engine =
                FakeHttpStreamingEngine {
                    failingResponse(
                        prefix = prefix,
                        failure = VoiceAgentException(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED)),
                    )
                }
            val result = adapter(engine, credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals("partial", result.text)
            assertEquals(LlmFailureReason.NETWORK, result.failureReason)
        }

    @Test
    fun cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent() =
        runTest {
            val cancelled = AtomicBoolean(false)
            val prefix =
                "data: {\"id\":\"chatcmpl-x\",\"model\":\"hermes-agent\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
            val engine = FakeHttpStreamingEngine { stallingResponse(prefix) { cancelled.set(true) } }
            val model = adapter(engine, credentialStore())

            val events = mutableListOf<LlmStreamEvent>()
            val job = launch { model.stream(request()).collect { events += it } }
            advanceUntilIdle()
            job.cancel()
            job.join()

            assertTrue(events.any { it is LlmStreamEvent.Delta })
            assertTrue("no terminal event may follow a cancellation", events.none { it.isTerminalEvent() })
            assertTrue("the engine must observe the cancellation", cancelled.get())
        }

    @Test
    fun theReasoningChannelIsExcludedFromAssistantText() =
        runTest {
            val result = adapter(fixture("reasoning_channel.sse"), credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("answer", result.text)
            assertFalse(result.text.contains("thinking"))
            assertEquals("5", result.usage?.details?.get("reasoningTokens"))
        }

    @Test
    fun aMissingCredentialFailsNotConfiguredWithoutSending() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = "{}") }
            val model = adapter(engine, InMemoryCredentialStore())

            val result = model.consume(request())

            assertEquals(ErrorCode.LLM_NOT_CONFIGURED, result.error?.code)
            assertTrue(engine.requests.isEmpty())
        }

    @Test
    fun aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = "{}") }
            val model = adapter(engine, credentialStore())

            val result = model.consume(request(reasoning = ReasoningLevel.HIGH))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue("an unsupported request must not be sent", engine.requests.isEmpty())
        }

    @Test
    fun aNoneReasoningRequestSendsTheDocumentedOptOut() =
        runTest {
            val sent = engineRequest(RecordedFixtures.text("llm/hermes/normal_stream.sse"), credentialStore(), ReasoningLevel.NONE)

            assertTrue(sent.body!!.contains("\"model_options\":{\"reasoning\":{\"enabled\":false}}"))
        }

    @Test
    fun theAdapterDeclaresOnlyTheVerifiedCapabilities() =
        runTest {
            val model =
                HermesLanguageModel(
                    credentialStore(),
                    RemoteTransport(FakeHttpStreamingEngine { scriptedResponse() }),
                    destination,
                )

            assertTrue(model.capabilities.streaming)
            assertFalse(model.capabilities.usageReporting)
            assertTrue(model.capabilities.reasoningLevels.isEmpty())
        }

    @Test
    fun theRequestAndStreamNeverPutTheCredentialOrContentInLogs() =
        runTest {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            adapter(fixture("normal_stream.sse"), credentialStore()).consume(request())

            val text = sink.messages.joinToString(separator = "\n")
            assertTrue(text.contains("hermes"))
            assertTrue(text.contains(modelId.value))
            assertFalse("credential leaked: $text", text.contains(secret))
            assertFalse("prompt leaked: $text", text.contains(prompt))
            assertFalse("assistant text leaked: $text", text.contains("Hello, world"))
        }

    @Test
    fun validDestinationsIncludeLoopbackHttpAndRemoteHttps() {
        val https = HermesServerAddress.config("https://hermes.example.com/v1")
        assertTrue(https is HermesServerConfigResult.Valid)
        assertEquals("https://hermes.example.com/v1", (https as HermesServerConfigResult.Valid).config.disclosure)
        assertEquals(
            "This Hermes server runs agent tools (terminal, files, browser, MCP) on the server host.",
            https.config.notice,
        )
        assertTrue(https.config.toolsRunOnServerHost)

        val loopback = HermesServerAddress.config("http://127.0.0.1:8642/v1")
        assertTrue(loopback is HermesServerConfigResult.Valid)
        assertEquals("http://127.0.0.1:8642/v1", (loopback as HermesServerConfigResult.Valid).config.disclosure)
    }

    @Test
    fun insecureRemoteNonHttpsAndCredentialBearingDestinationsAreRefused() {
        val insecure = HermesServerAddress.config("http://hermes.example.com/v1")
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INSECURE, (insecure as HermesServerConfigResult.Invalid).error.code)

        val scheme = HermesServerAddress.config("ftp://hermes.example.com/v1")
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, (scheme as HermesServerConfigResult.Invalid).error.code)

        val embedded = HermesServerAddress.config("https://user:pass@hermes.example.com/v1")
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, (embedded as HermesServerConfigResult.Invalid).error.code)

        val empty = HermesServerAddress.config("   ")
        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, (empty as HermesServerConfigResult.Invalid).error.code)
    }

    @Test
    fun aQrSourcedDestinationIsNeverAccepted() {
        val result = HermesServerAddress.validate("https://hermes.example.com/v1", EndpointSource.QR_PAYLOAD)

        assertEquals(ErrorCode.PROVIDER_ENDPOINT_INVALID, (result as EndpointValidation.Invalid).error.code)
        assertNull(HermesServerAddress.disclosure("https://hermes.example.com/v1", EndpointSource.QR_PAYLOAD))
    }

    @Test
    fun theHermesRowClaimsStreamingVerifiedAndKeepsReasoningAndUsageMarked() {
        val hermes = ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.HERMES)!!

        assertTrue(hermes.transport.streaming)
        assertFalse(hermes.transport.unverified.contains(com.voicechat.agent.providers.UnverifiedCapability.STREAMING))
        assertTrue(hermes.transport.unverified.contains(com.voicechat.agent.providers.UnverifiedCapability.REASONING))
        assertTrue(hermes.models.unverified.contains(com.voicechat.agent.providers.UnverifiedCapability.USAGE))
        assertTrue(hermes.toolExecutionOnServer)
    }

    private suspend fun statusError(status: Int): ErrorCode? {
        val engine = FakeHttpStreamingEngine { scriptedResponse(status = status, body = "{\"error\":{\"code\":$status}}") }
        return adapter(engine, credentialStore()).consume(request()).error?.code
    }

    private suspend fun fixtureError(name: String): ErrorCode? = adapter(fixture(name), credentialStore()).consume(request()).error?.code

    private suspend fun engineRequest(
        body: String,
        store: CredentialStore,
        reasoning: ReasoningLevel? = null,
    ): RemoteHttpRequest {
        val engine = sseEngine(body)
        adapter(engine, store).consume(request(reasoning))
        return engine.requests.single()
    }

    private fun LlmStreamEvent.isTerminalEvent(): Boolean = this !is LlmStreamEvent.Delta

    private companion object {
        fun destination(raw: String): ServerDestination =
            when (val result = HermesServerAddress.config(raw)) {
                is HermesServerConfigResult.Valid -> result.config.destination
                is HermesServerConfigResult.Invalid -> error("test destination was invalid: ${result.error.code}")
            }
    }
}
