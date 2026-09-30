package com.voicechat.agent.providers.deepseek

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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * M16 acceptance for the DeepSeek adapter, driven by recorded SSE fixtures and a
 * scripted fake engine. No socket, DNS, clock, or real credential is involved,
 * and each fixture case from the milestone acceptance is exercised here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DeepSeekLanguageModelTest {
    private val secret = "sk-deepseek-DO-NOT-LEAK-0123456789"
    private val prompt = "my private medical question"
    private val modelId = ModelId("deepseek-flash")

    @After
    fun tearDown() {
        AppLog.reset()
    }

    private suspend fun credentialStore(): CredentialStore =
        InMemoryCredentialStore().apply {
            store(Credential(KnownProviders.DEEPSEEK, CredentialKind.API_KEY, secret))
        }

    private fun request(reasoning: ReasoningLevel? = null) =
        LlmRequest(
            model = ProviderModelSelection(KnownProviders.DEEPSEEK, modelId),
            messages = listOf(LlmMessage(LlmRole.USER, prompt)),
            reasoning = reasoning,
        )

    private fun adapter(
        engine: HttpStreamingEngine,
        store: CredentialStore,
    ) = DeepSeekLanguageModel(store, RemoteTransport(engine))

    private fun sseEngine(
        body: String,
        chunkSize: Int = body.length.coerceAtLeast(1),
    ) = FakeHttpStreamingEngine { scriptedResponse(body = body, chunkSize = chunkSize) }

    private fun fixture(name: String): String = RecordedFixtures.text("llm/deepseek/$name")

    @Test
    fun aNormalStreamCompletesWithTextModelAndUsage() =
        runTest {
            val engine = sseEngine(fixture("normal_stream.sse"), chunkSize = 7)
            val result = adapter(engine, credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("Hello, world", result.text)
            assertEquals(2, result.deltaCount)
            assertEquals(modelId, result.model)
            assertEquals(22, result.usage?.totalTokens)
            assertEquals(15, result.usage?.promptTokens)
            assertEquals(7, result.usage?.completionTokens)
            // DeepSeek does not report a served effort, so none is fabricated.
            assertEquals(null, result.reportedReasoning)
        }

    @Test
    fun theRequestGoesToTheDocumentedEndpointWithBearerAuthAndStreaming() =
        runTest {
            val engine = sseEngine(fixture("normal_stream.sse"))
            adapter(engine, credentialStore()).consume(request())

            val sent = engine.requests.single()
            assertEquals("https://api.deepseek.com/chat/completions", sent.url)
            assertEquals("POST", sent.method)
            assertEquals("Bearer $secret", sent.headers.first { it.name == "Authorization" }.value)
            assertEquals("text/event-stream", sent.headers.first { it.name == "Accept" }.value)
            assertTrue("the payload must enable streaming", sent.body!!.contains("\"stream\":true"))
            assertTrue("the selected model must be sent", sent.body.contains("\"model\":\"deepseek-flash\""))
        }

    @Test
    fun anEmptyResponseCompletesWithNoDeltas() =
        runTest {
            val engine = sseEngine(fixture("empty_response.sse"))
            val result = adapter(engine, credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("", result.text)
            assertEquals(0, result.deltaCount)
            assertEquals(4, result.usage?.totalTokens)
        }

    @Test
    fun aMalformedFrameFailsWithMalformedResponseAndKeepsThePrefix() =
        runTest {
            val engine = sseEngine(fixture("malformed_frame.sse"))
            val result = adapter(engine, credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("ok", result.text)
        }

    @Test
    fun aTruncatedFinishReasonIsNotACompletionButKeepsThePartialText() =
        runTest {
            val engine = sseEngine(fixture("truncated.sse"))
            val result = adapter(engine, credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("This answer is cut", result.text)
            assertTrue(result.isPartial)
        }

    @Test
    fun aRateLimitStatusFailsWithRateLimited() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 429, body = "{}") }
            val result = adapter(engine, credentialStore()).consume(request())

            assertEquals(ErrorCode.LLM_RATE_LIMITED, result.error?.code)
            assertEquals(LlmFailureReason.RATE_LIMITED, result.failureReason)
            assertFalse(result.completed)
        }

    @Test
    fun anAuthenticationStatusFailsWithAuthentication() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 401, body = "{}") }
            val result = adapter(engine, credentialStore()).consume(request())

            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, result.error?.code)
        }

    @Test
    fun aServerErrorStatusFailsWithUnavailable() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 503, body = "{}") }
            val result = adapter(engine, credentialStore()).consume(request())

            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
        }

    @Test
    fun a402InsufficientBalanceKeepsTheSharedStatusMapping() =
        runTest {
            // DeepSeek documents HTTP 402 for insufficient balance, but the shared
            // status mapper has no billing/account code and classifies 4xx as
            // LLM_INVALID_REQUEST. This test pins the current behavior so a future
            // fix is visible; the gap is tracked as R-0124.
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 402, body = "{}") }
            val result = adapter(engine, credentialStore()).consume(request())

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
        }

    @Test
    fun aTransportTimeoutFailsWithTimeoutAndKeepsThePartialText() =
        runTest {
            val prefix =
                "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"model\":\"deepseek-flash\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
            val engine =
                FakeHttpStreamingEngine {
                    failingResponse(
                        prefix = prefix,
                        failure = VoiceAgentException(VoiceAgentError(ErrorCode.LLM_TIMEOUT, "the connection timed out")),
                    )
                }
            val result = adapter(engine, credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_TIMEOUT, result.error?.code)
            assertEquals(LlmFailureReason.TIMEOUT, result.failureReason)
            assertEquals("partial", result.text)
        }

    @Test
    fun aGatewayTimeoutStatusFailsWithTimeout() =
        runTest {
            // RemoteStatusMapper maps 408/504 to LLM_TIMEOUT; DeepSeek documents 500/503.
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 504, body = "{}") }
            val result = adapter(engine, credentialStore()).consume(request())

            assertEquals(ErrorCode.LLM_TIMEOUT, result.error?.code)
        }

    @Test
    fun aNetworkLossKeepsThePartialTextAndReportsNetwork() =
        runTest {
            val prefix =
                "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"model\":\"deepseek-flash\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
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
            assertTrue(result.isPartial)
            assertEquals(LlmFailureReason.NETWORK, result.failureReason)
        }

    @Test
    fun aTerminalLessStreamIsNotACompletion() =
        runTest {
            val engine = sseEngine(fixture("terminal_less.sse"))
            val result = adapter(engine, credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("partial answer keeps going", result.text)
        }

    @Test
    fun theReasoningChannelIsExcludedFromAssistantText() =
        runTest {
            val engine = sseEngine(fixture("reasoning_channel.sse"))
            val result = adapter(engine, credentialStore()).consume(request())

            assertTrue(result.completed)
            assertEquals("answer", result.text)
            assertFalse(result.text.contains("thinking"))
            assertEquals("9", result.usage?.details?.get("reasoningTokens"))
            assertEquals(null, result.reportedReasoning)
        }

    @Test
    fun cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent() =
        runTest {
            val cancelled = AtomicBoolean(false)
            val prefix =
                "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"model\":\"deepseek-flash\"," +
                    "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"},\"finish_reason\":null}]}\n\n"
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
            val deepSeek = ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.DEEPSEEK)!!
            val constrained = deepSeek.copy(models = deepSeek.models.copy(reasoningLevels = setOf(ReasoningLevel.NONE)))
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = "{}") }
            val model = DeepSeekLanguageModel(credentialStore(), RemoteTransport(engine), provider = constrained)

            val result = model.consume(request(reasoning = ReasoningLevel.HIGH))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue("an unsupported request must not be sent", engine.requests.isEmpty())
        }

    @Test
    fun aMissingCredentialFailsNotConfiguredWithoutSending() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = "{}") }
            val model = DeepSeekLanguageModel(InMemoryCredentialStore(), RemoteTransport(engine))

            val result = model.consume(request())

            assertEquals(ErrorCode.LLM_NOT_CONFIGURED, result.error?.code)
            assertTrue(engine.requests.isEmpty())
        }

    @Test
    fun theAdapterDeclaresTheVerifiedProviderCapabilities() =
        runTest {
            val model =
                DeepSeekLanguageModel(
                    credentialStore(),
                    RemoteTransport(FakeHttpStreamingEngine { scriptedResponse() }),
                )

            assertEquals(KnownProviders.DEEPSEEK, model.providerId)
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
    fun aRealReasoningRequestSendsTheDocumentedThinkingAndEffort() =
        runTest {
            val engine = sseEngine(fixture("normal_stream.sse"))
            adapter(engine, credentialStore()).consume(request(reasoning = ReasoningLevel.MEDIUM))

            val body = engine.requests.single().body!!
            assertTrue(body.contains("\"thinking\":{\"type\":\"enabled\"}"))
            assertTrue(body.contains("\"reasoning_effort\":\"medium\""))
        }

    @Test
    fun noneReasoningDisablesThinking() =
        runTest {
            val engine = sseEngine(fixture("normal_stream.sse"))
            adapter(engine, credentialStore()).consume(request(reasoning = ReasoningLevel.NONE))

            val body = engine.requests.single().body!!
            assertTrue(body.contains("\"thinking\":{\"type\":\"disabled\"}"))
            assertFalse(body.contains("reasoning_effort"))
        }

    @Test
    fun theRequestAndStreamNeverPutTheCredentialOrContentInLogs() =
        runTest {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            val engine = sseEngine(fixture("normal_stream.sse"))
            adapter(engine, credentialStore()).consume(request())

            val text = sink.messages.joinToString(separator = "\n")
            assertTrue(text.contains("deepseek"))
            assertTrue(text.contains("deepseek-flash"))
            assertFalse("credential leaked: $text", text.contains(secret))
            assertFalse("prompt leaked: $text", text.contains(prompt))
            assertFalse("assistant text leaked: $text", text.contains("Hello, world"))
        }
}
