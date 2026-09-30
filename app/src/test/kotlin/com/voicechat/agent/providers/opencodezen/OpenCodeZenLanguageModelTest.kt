package com.voicechat.agent.providers.opencodezen

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
import com.voicechat.agent.providers.CredentialValidationPolicy
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.ProviderCapabilityRegistry
import com.voicechat.agent.providers.UnverifiedCapability
import com.voicechat.agent.remote.FakeHttpStreamingEngine
import com.voicechat.agent.remote.HttpStreamingEngine
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
 * M18 acceptance for the OpenCode Zen adapter, driven by recorded SSE fixtures
 * and a scripted fake engine. No socket, DNS, clock, or real credential is
 * involved.
 *
 * The three chat protocol families are exercised separately, because Zen is not
 * one wire protocol: Chat Completions for GLM/DeepSeek/MiniMax/Kimi, Responses
 * for GPT/Grok/Muse, and Anthropic Messages for Claude/Qwen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OpenCodeZenLanguageModelTest {
    private val secret = "oc-zen-DO-NOT-LEAK-0123456789"
    private val prompt = "my private medical question"

    @After
    fun tearDown() {
        AppLog.reset()
    }

    private suspend fun credentialStore(): CredentialStore =
        InMemoryCredentialStore().apply {
            store(Credential(KnownProviders.OPENCODE_ZEN, CredentialKind.API_KEY, secret))
        }

    private fun request(
        model: String = "glm-5.3-flash",
        reasoning: ReasoningLevel? = null,
    ) = LlmRequest(
        model = ProviderModelSelection(KnownProviders.OPENCODE_ZEN, ModelId(model)),
        messages = listOf(LlmMessage(LlmRole.USER, prompt)),
        reasoning = reasoning,
    )

    private fun adapter(
        engine: HttpStreamingEngine,
        store: CredentialStore,
    ) = OpenCodeZenLanguageModel(store, RemoteTransport(engine))

    private fun sseEngine(
        body: String,
        chunkSize: Int = 7,
    ) = FakeHttpStreamingEngine { scriptedResponse(body = body, chunkSize = chunkSize) }

    // --- verified flow, one case per family ----------------------------------

    @Test
    fun aChatCompletionsStreamCompletesWithTextModelAndUsage() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "glm-5.3-flash"))

            assertTrue(result.completed)
            assertEquals("Hello, world", result.text)
            assertEquals(2, result.deltaCount)
            assertEquals(ModelId("glm-5.3-flash"), result.model)
            assertEquals(14, result.usage?.totalTokens)
        }

    @Test
    fun aResponsesStreamCompletesWithTextModelAndUsage() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("responses_normal.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "gpt-5.6-luna"))

            assertTrue(result.completed)
            assertEquals("Hello, world", result.text)
            assertEquals(ModelId("gpt-5.6-luna"), result.model)
            assertEquals(10, result.usage?.promptTokens)
            assertEquals(4, result.usage?.completionTokens)
        }

    @Test
    fun aMessagesStreamCompletesWithMergedInputAndOutputUsage() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("messages_normal.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "claude-sonnet-4-5"))

            assertTrue(result.completed)
            assertEquals("Hello, world", result.text)
            assertEquals(ModelId("claude-sonnet-4-5"), result.model)
            // Input tokens arrive on message_start, output tokens on message_delta.
            assertEquals(8, result.usage?.promptTokens)
            assertEquals(5, result.usage?.completionTokens)
        }

    @Test
    fun eachFamilyGoesToItsDocumentedEndpointWithBearerAuthAndNoSessionHeader() =
        runTest {
            val cases =
                listOf(
                    Triple("glm-5.3-flash", "chat_normal.sse", "https://opencode.ai/zen/v1/chat/completions"),
                    Triple("gpt-5.6-luna", "responses_normal.sse", "https://opencode.ai/zen/v1/responses"),
                    Triple("claude-sonnet-4-5", "messages_normal.sse", "https://opencode.ai/zen/v1/messages"),
                )
            cases.forEach { (model, fixture, expectedUrl) ->
                val engine = sseEngine(OpenCodeZenFixtures.text(fixture))
                adapter(engine, credentialStore()).consume(request(model = model))

                val sent = engine.requests.single()
                assertEquals(expectedUrl, sent.url)
                assertEquals("POST", sent.method)
                assertEquals("Bearer $secret", sent.headers.first { it.name == "Authorization" }.value)
                assertEquals("application/json", sent.headers.first { it.name == "Content-Type" }.value)
                assertEquals("text/event-stream", sent.headers.first { it.name == "Accept" }.value)
                // Zen documents no client-session header; Go-specific assumptions
                // are deliberately not reused.
                assertFalse(sent.headers.any { it.name.equals("x-opencode-session", ignoreCase = true) })
            }
        }

    @Test
    fun theProviderIdentityIsOpenCodeZen() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            val model = adapter(engine, credentialStore())

            assertEquals(KnownProviders.OPENCODE_ZEN, model.providerId)
            assertEquals(
                KnownProviders.OPENCODE_ZEN,
                request().model.providerId,
            )
        }

    @Test
    fun theReasoningChannelIsExcludedFromAssistantText() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("responses_reasoning.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "grok-4.7"))

            assertTrue(result.completed)
            assertEquals("answer", result.text)
            assertFalse(result.text.contains("thinking"))
        }

    // --- ordinary provider errors --------------------------------------------

    @Test
    fun aChatProviderErrorMapsToRateLimited() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_error.sse"))
            val result = adapter(engine, credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_RATE_LIMITED, result.error?.code)
            assertEquals(LlmFailureReason.RATE_LIMITED, result.failureReason)
        }

    @Test
    fun aMessagesProviderErrorMapsToUnavailableAndKeepsThePrefix() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("messages_error.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "claude-sonnet-4-5"))

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_UNAVAILABLE, result.error?.code)
            assertEquals("part", result.text)
            assertTrue(result.isPartial)
        }

    @Test
    fun httpStatusesMapToTypedFailures() =
        runTest {
            val cases =
                mapOf(
                    400 to ErrorCode.LLM_INVALID_REQUEST,
                    401 to ErrorCode.LLM_AUTHENTICATION_FAILED,
                    429 to ErrorCode.LLM_RATE_LIMITED,
                    503 to ErrorCode.LLM_UNAVAILABLE,
                )
            cases.forEach { (status, expected) ->
                val engine = FakeHttpStreamingEngine { scriptedResponse(status = status, body = "{}") }
                val result = adapter(engine, credentialStore()).consume(request())
                assertEquals("HTTP $status", expected, result.error?.code)
            }
        }

    @Test
    fun aNetworkLossKeepsThePartialTextAndReportsNetwork() =
        runTest {
            val prefix =
                "data: {\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
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
            val engine = sseEngine(OpenCodeZenFixtures.text("terminal_less.sse"))
            val result = adapter(engine, credentialStore()).consume(request())

            assertFalse(result.completed)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, result.error?.code)
            assertEquals("partial", result.text)
        }

    // --- refusals before any request -----------------------------------------

    @Test
    fun theSystemOneDecisionModelIsRefusedBeforeAnyRequest() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "jev-1.13"))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue("/systemone must never be sent as a chat completion", engine.requests.isEmpty())
        }

    @Test
    fun theGoogleFamilyModelIsRefusedBeforeAnyRequest() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "gemini-3.1-pro"))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue("the unimplemented Google family must not be forced through a chat protocol", engine.requests.isEmpty())
        }

    @Test
    fun aModelWithNoVerifiedProtocolFamilyIsRefusedBeforeAnyRequest() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            val result = adapter(engine, credentialStore()).consume(request(model = "deepseek-v4-flash-free"))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue("an unplaced model must not be forced through a protocol", engine.requests.isEmpty())
        }

    @Test
    fun aReasoningLevelTheAdapterDoesNotSupportIsRefusedBeforeAnyRequest() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            val result = adapter(engine, credentialStore()).consume(request(reasoning = ReasoningLevel.HIGH))

            assertEquals(ErrorCode.LLM_INVALID_REQUEST, result.error?.code)
            assertTrue(engine.requests.isEmpty())
        }

    @Test
    fun aMissingCredentialFailsNotConfiguredWithoutSending() =
        runTest {
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            val result = adapter(engine, InMemoryCredentialStore()).consume(request())

            assertEquals(ErrorCode.LLM_NOT_CONFIGURED, result.error?.code)
            assertTrue(engine.requests.isEmpty())
        }

    @Test
    fun cancellingMidStreamCancelsTheEngineAndEmitsNoTerminalEvent() =
        runTest {
            val cancelled = AtomicBoolean(false)
            val prefix =
                "data: {\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"partial\"}}]}\n\n"
            val engine = FakeHttpStreamingEngine { stallingResponse(prefix) { cancelled.set(true) } }
            val model = adapter(engine, credentialStore())

            val events = mutableListOf<LlmStreamEvent>()
            val job = launch { model.stream(request()).collect { events += it } }
            advanceUntilIdle()
            job.cancel()
            job.join()

            assertTrue(events.any { it is LlmStreamEvent.Delta })
            assertTrue(events.none { it is LlmStreamEvent.Completed })
            assertTrue("the engine must observe the cancellation", cancelled.get())
        }

    // --- capability, privacy, and the public model list ----------------------

    @Test
    fun theAdapterDeclaresOnlyTheVerifiedCapabilities() =
        runTest {
            val model =
                OpenCodeZenLanguageModel(
                    credentialStore(),
                    RemoteTransport(FakeHttpStreamingEngine { scriptedResponse() }),
                )

            assertTrue(model.capabilities.streaming)
            assertFalse("Zen does not promise usage reporting", model.capabilities.usageReporting)
            assertTrue("Zen documents no reasoning control", model.capabilities.reasoningLevels.isEmpty())
        }

    @Test
    fun noCredentialValidatorIsOfferedBecauseTheModelListNeedsNoKey() {
        val provider = ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENCODE_ZEN)!!

        // `GET /models` answers without a credential, so it lists but cannot
        // validate one; the app must not claim a key is valid for Zen.
        assertFalse(CredentialValidationPolicy.supports(provider))
    }

    @Test
    fun theRegistryZenRowClaimsNoReasoningAndMarksWhatIsUnverified() {
        val zen = ProviderCapabilityRegistry.verifiedDefaults().capabilities(KnownProviders.OPENCODE_ZEN)!!

        assertTrue(zen.models.reasoningLevels.isEmpty())
        assertFalse(zen.models.usageReporting)
        assertTrue(zen.models.unverified.contains(UnverifiedCapability.REASONING))
        assertTrue(zen.models.unverified.contains(UnverifiedCapability.USAGE))
        // M18 verified API-key auth, so it is no longer marked unverified.
        assertFalse(zen.models.unverified.contains(UnverifiedCapability.AUTH))
        assertTrue(zen.transport.unverified.contains(UnverifiedCapability.STREAMING))
    }

    @Test
    fun theRequestAndStreamNeverPutTheCredentialOrContentInLogs() =
        runTest {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            val engine = sseEngine(OpenCodeZenFixtures.text("chat_normal.sse"))
            adapter(engine, credentialStore()).consume(request())

            val text = sink.messages.joinToString(separator = "\n")
            assertTrue(text.contains("opencode-zen"))
            assertFalse("credential leaked: $text", text.contains(secret))
            assertFalse("prompt leaked: $text", text.contains(prompt))
            assertFalse("assistant text leaked: $text", text.contains("Hello, world"))
        }
}
