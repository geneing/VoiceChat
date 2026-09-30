package com.voicechat.agent.providers.openrouter

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
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.HttpStreamingEngine
import com.voicechat.agent.remote.RecordedFixtures
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M15 acceptance for the OpenRouter adapter, driven by recorded SSE fixtures and
 * a scripted fake engine. No socket, DNS, clock, or real credential is involved,
 * and each milestone acceptance case is exercised here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenRouterLanguageModelTest {
    private val secret = "sk-or-v1-DO-NOT-LEAK-0123456789"
    private val prompt = "my private medical question"
    private val modelId = ModelId("anthropic/claude-test-1")

    @After
    fun tearDown() {
        AppLog.reset()
    }

    private suspend fun credentialStore(): CredentialStore =
        InMemoryCredentialStore().apply {
            store(Credential(KnownProviders.OPENROUTER, CredentialKind.API_KEY, secret))
        }

    private fun request(reasoning: ReasoningLevel? = null) =
        LlmRequest(
            model = ProviderModelSelection(KnownProviders.OPENROUTER, modelId),
            messages = listOf(LlmMessage(LlmRole.USER, prompt)),
            reasoning = reasoning,
        )

    private fun adapter(
        engine: HttpStreamingEngine,
        store: CredentialStore,
    ) = OpenRouterLanguageModel(store, RemoteTransport(engine))

    private fun sseEngine(
        body: String,
        chunkSize: Int = body.length.coerceAtLeast(1),
    ) = FakeHttpStreamingEngine { scriptedResponse(body = body, chunkSize = chunkSize) }

    private fun fixture(
        name: String,
        chunkSize: Int = 7,
    ) = sseEngine(RecordedFixtures.text("llm/openrouter/$name"), chunkSize)

    @Test
    fun aNormalStreamCompletesWithTextModelUsageAndCost() =
        runTest {
            val result = adapter(fixture("normal_stream.sse"), credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("Hello, world", result.text)
            assertEquals(2, result.deltaCount)
            assertEquals(modelId, result.model)
            assertEquals(19, result.usage?.totalTokens)
            assertEquals("0.00014", result.usage?.details?.get("cost"))
        }

    @Test
    fun theRequestGoesToTheDocumentedEndpointWithBearerAuthAndNoFallbackRouting() =
        runTest {
            val sent = engineRequest(RecordedFixtures.text("llm/openrouter/normal_stream.sse"), credentialStore())

            assertEquals("https://openrouter.ai/api/v1/chat/completions", sent.url)
            assertEquals("POST", sent.method)
            assertEquals("Bearer $secret", sent.headers.first { it.name == "Authorization" }.value)
            assertEquals("text/event-stream", sent.headers.first { it.name == "Accept" }.value)
            val body = requireNotNull(sent.body)
            assertTrue("fallbacks must be disabled (R-0017)", body.contains("\"allow_fallbacks\":false"))
            assertFalse("the selected model must be the only candidate", body.contains("\"models\""))
            assertFalse(body.contains("\"route\""))
        }

    @Test
    fun theModelOpenRouterReportsIsSurfacedInsteadOfTheSelection() =
        runTest {
            // The selected model and the model the response reports are the same
            // when routing is constrained, and the reported id is what the trace sees.
            val result = adapter(fixture("normal_stream.sse"), credentialStore()).consume(request())

            assertEquals(modelId, result.model)
            assertEquals(modelId, result.terminal?.let { (it as LlmStreamEvent.Completed).model })
        }

    @Test
    fun aReportedModelThatDiffersFromTheSelectionIsDetectableNotSilentlyAccepted() =
        runTest {
            val result = adapter(fixture("model_mismatch.sse"), credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("routed elsewhere", result.text)
            // The adapter surfaces what actually served the request; it does not
            // rewrite it to the selection (R-0017 / R-0023).
            assertEquals(ModelId("openai/gpt-other"), result.model)
            assertNotEquals(modelId, result.model)
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
    fun httpStatusesMapToTheirTypedReasons() =
        runTest {
            assertEquals(ErrorCode.LLM_RATE_LIMITED, statusError(429))
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, statusError(401))
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, statusError(403))
            // 402 Payment Required is a non-retryable request problem.
            assertEquals(ErrorCode.LLM_INVALID_REQUEST, statusError(402))
            assertEquals(ErrorCode.LLM_UNAVAILABLE, statusError(503))
        }

    @Test
    fun midStreamErrorEventsMapToTheirTypedReasons() =
        runTest {
            assertEquals(ErrorCode.LLM_RATE_LIMITED, fixtureError("rate_limit_event.sse"))
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, fixtureError("auth_error_event.sse"))
            assertEquals(ErrorCode.LLM_INVALID_REQUEST, fixtureError("payment_required_event.sse"))
            assertEquals(ErrorCode.LLM_UNAVAILABLE, fixtureError("server_error_event.sse"))
        }

    @Test
    fun aMidStreamProviderErrorKeepsThePartialTextAndReportsUnavailable() =
        runTest {
            val result = adapter(fixture("mid_stream_error.sse"), credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals("partial", result.text)
            assertTrue(result.isPartial)
            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
            assertEquals(LlmFailureReason.UNAVAILABLE, result.failureReason)
        }

    @Test
    fun aNetworkLossKeepsThePartialTextAndReportsNetwork() =
        runTest {
            val prefix =
                "data: {\"id\":\"gen\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
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
    fun aTerminalLessStreamIsNotACompletion() =
        runTest {
            // The accounting chunk carries usage, but there is no [DONE]: usage is
            // an accounting frame, not a terminal event (R-0067).
            val result = adapter(fixture("terminal_less.sse"), credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("partial answer", result.text)
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
    fun cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent() =
        runTest {
            val cancelled = AtomicBoolean(false)
            val prefix =
                "data: {\"id\":\"gen\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
            val engine = FakeHttpStreamingEngine { stallingResponse(prefix) { cancelled.set(true) } }
            val model = adapter(engine, credentialStore())

            val events = mutableListOf<LlmStreamEvent>()
            val job = launch { model.stream(request()).collect { events += it } }
            advanceUntilIdle()
            job.cancel()
            job.join()

            assertTrue(events.any { it is LlmStreamEvent.Delta })
            assertTrue("the engine must observe the cancellation", cancelled.get())
        }

    @Test
    fun aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest() =
        runTest {
            val openRouter = ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENROUTER)!!
            val constrained = openRouter.copy(models = openRouter.models.copy(reasoningLevels = setOf(ReasoningLevel.NONE)))
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = "{}") }
            val model = OpenRouterLanguageModel(credentialStore(), RemoteTransport(engine), provider = constrained)

            val result = model.consume(request(reasoning = ReasoningLevel.HIGH))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue("an unsupported request must not be sent", engine.requests.isEmpty())
        }

    @Test
    fun aMissingCredentialFailsNotConfiguredWithoutSending() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = "{}") }
            val model = OpenRouterLanguageModel(InMemoryCredentialStore(), RemoteTransport(engine))

            val result = model.consume(request())

            assertEquals(ErrorCode.LLM_NOT_CONFIGURED, result.error?.code)
            assertTrue(engine.requests.isEmpty())
        }

    @Test
    fun theAdapterDeclaresTheVerifiedProviderCapabilities() =
        runTest {
            val model =
                OpenRouterLanguageModel(
                    credentialStore(),
                    RemoteTransport(FakeHttpStreamingEngine { scriptedResponse() }),
                )

            assertTrue(model.capabilities.streaming)
            assertTrue(model.capabilities.usageReporting)
            assertEquals(
                setOf(
                    ReasoningLevel.NONE,
                    ReasoningLevel.MINIMAL,
                    ReasoningLevel.LOW,
                    ReasoningLevel.MEDIUM,
                    ReasoningLevel.HIGH,
                    ReasoningLevel.XHIGH,
                    ReasoningLevel.MAX,
                ),
                model.capabilities.reasoningLevels,
            )
        }

    @Test
    fun theRequestAndStreamNeverPutTheCredentialOrContentInLogs() =
        runTest {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            adapter(fixture("normal_stream.sse"), credentialStore()).consume(request())

            val text = sink.messages.joinToString(separator = "\n")
            assertTrue(text.contains("openrouter"))
            assertTrue(text.contains(modelId.value))
            assertFalse("credential leaked: $text", text.contains(secret))
            assertFalse("prompt leaked: $text", text.contains(prompt))
            assertFalse("assistant text leaked: $text", text.contains("Hello, world"))
        }

    @Test
    fun aRealReasoningRequestSendsTheDocumentedEffort() =
        runTest {
            val sent = engineRequest(RecordedFixtures.text("llm/openrouter/normal_stream.sse"), credentialStore(), ReasoningLevel.MEDIUM)

            assertTrue(sent.body!!.contains("\"effort\":\"medium\""))
        }

    private suspend fun statusError(status: Int): ErrorCode? {
        val engine = FakeHttpStreamingEngine { scriptedResponse(status = status, body = "{\"error\":{\"code\":$status}}") }
        val result = adapter(engine, credentialStore()).consume(request())
        return result.error?.code
    }

    private suspend fun fixtureError(name: String): ErrorCode? = adapter(fixture(name), credentialStore()).consume(request()).error?.code

    private suspend fun engineRequest(
        body: String,
        store: CredentialStore,
        reasoning: ReasoningLevel? = null,
    ): com.voicechat.agent.remote.RemoteHttpRequest {
        val engine = sseEngine(body)
        adapter(engine, store).consume(request(reasoning))
        return engine.requests.single()
    }
}
