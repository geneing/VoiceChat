package com.voicechat.agent.providers.openrouter

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RecordedFixtures
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.SseFrame
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.get
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M15 protocol mapping for the OpenRouter Chat Completions adapter. No HTTP,
 * socket, clock, or credential is involved: `encodeRequest` and `parse` are pure.
 */
class OpenRouterChatCompletionsTest {
    private val modelId = ModelId("anthropic/claude-test-1")

    private fun request(reasoning: ReasoningLevel? = null) =
        LlmRequest(
            model = ProviderModelSelection(KnownProviders.OPENROUTER, modelId),
            messages = listOf(LlmMessage(LlmRole.SYSTEM, "be brief"), LlmMessage(LlmRole.USER, "hello")),
            reasoning = reasoning,
        )

    private fun encoded(reasoning: ReasoningLevel? = null): JsonNode.Obj =
        RemoteJson.parse(OpenRouterChatCompletions.encodeRequest(request(reasoning))) as JsonNode.Obj

    @Test
    fun theRequestSelectsTheModelStreamsAndDisablesFallbacksWithoutAModelList() {
        val body = OpenRouterChatCompletions.encodeRequest(request())

        val root = encoded()
        assertEquals(modelId.value, root.stringField("model"))
        assertTrue((root.get("stream") as JsonNode.Bool).value)

        val provider = root.objField("provider")!!
        assertFalse((provider.get("allow_fallbacks") as JsonNode.Bool).value)

        // The selected model must be the only routing candidate: no model list and
        // no cross-model fallback route.
        assertFalse(body.contains("\"models\""))
        assertFalse(body.contains("\"route\""))
        assertFalse(body.contains(":nitro"))
        assertFalse(body.contains(":floor"))
    }

    @Test
    fun messagesKeepTheirRoleAndContent() {
        val messages = encoded().arrField("messages")!!.items
        assertEquals(2, messages.size)
        assertEquals("system", (messages[0] as JsonNode.Obj).stringField("role"))
        assertEquals("be brief", (messages[0] as JsonNode.Obj).stringField("content"))
        assertEquals("user", (messages[1] as JsonNode.Obj).stringField("role"))
    }

    @Test
    fun aReasoningLevelIsSentAsTheDocumentedEffort() {
        assertEquals("medium", encoded(ReasoningLevel.MEDIUM).objField("reasoning")!!.stringField("effort"))
        assertEquals("minimal", OpenRouterChatCompletions.effortWire(ReasoningLevel.MINIMAL))
        assertEquals("xhigh", OpenRouterChatCompletions.effortWire(ReasoningLevel.XHIGH))
        assertEquals("max", OpenRouterChatCompletions.effortWire(ReasoningLevel.MAX))
    }

    @Test
    fun reasoningNoneOmitsTheReasoningObject() {
        assertNull(encoded(ReasoningLevel.NONE).objField("reasoning"))
        assertNull(encoded(null).objField("reasoning"))
    }

    @Test
    fun aContentDeltaBecomesText() {
        val frame = SseFrame(event = null, data = chunk(delta = """{"content":"Hi"}"""))

        assertEquals(OpenRouterStreamFrame.Text("Hi"), OpenRouterChatCompletions.parse(frame))
    }

    @Test
    fun theDoneSentinelIsTerminal() {
        assertEquals(OpenRouterStreamFrame.Done, OpenRouterChatCompletions.parse(SseFrame(event = null, data = "[DONE]")))
        assertEquals(OpenRouterStreamFrame.Done, OpenRouterChatCompletions.parse(SseFrame(event = null, data = " [DONE] ")))
    }

    @Test
    fun aReasoningDetailsDeltaIsReasoningNotAssistantText() {
        val frame =
            SseFrame(
                event = null,
                data = chunk(delta = """{"reasoning_details":[{"type":"reasoning.text","text":"thinking","index":0}]}"""),
            )

        val parsed = OpenRouterChatCompletions.parse(frame)

        assertEquals(OpenRouterStreamFrame.Reasoning(""), parsed)
    }

    @Test
    fun aPlainReasoningStringIsReasoning() {
        val frame = SseFrame(event = null, data = chunk(delta = """{"reasoning":"thinking"}"""))

        assertEquals(OpenRouterStreamFrame.Reasoning("thinking"), OpenRouterChatCompletions.parse(frame))
    }

    @Test
    fun theAccountingChunkCarriesUsageModelAndCost() {
        val frame =
            SseFrame(
                event = null,
                data =
                    """{"id":"gen","object":"chat.completion.chunk","model":"$modelId",""" +
                        """"choices":[{"index":0,"delta":{"content":""},"finish_reason":"stop"}],""" +
                        """"usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19,""" +
                        """"prompt_tokens_details":{"cached_tokens":1},""" +
                        """"completion_tokens_details":{"reasoning_tokens":2},"cost":0.00014}}""",
            )

        val parsed = OpenRouterChatCompletions.parse(frame)

        assertTrue(parsed is OpenRouterStreamFrame.Accounting)
        val accounting = parsed as OpenRouterStreamFrame.Accounting
        assertEquals(modelId, accounting.model)
        assertEquals(12, accounting.usage.promptTokens)
        assertEquals(7, accounting.usage.completionTokens)
        assertEquals(19, accounting.usage.totalTokens)
        assertEquals("1", accounting.usage.details["cachedInputTokens"])
        assertEquals("2", accounting.usage.details["reasoningTokens"])
        assertEquals("0.00014", accounting.usage.details["cost"])
    }

    @Test
    fun aMidStreamErrorObjectMapsToItsTypedReason() {
        assertEquals(ErrorCode.LLM_RATE_LIMITED, errorCode("rate_limit_exceeded", 429))
        assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, errorCode("authentication", 401))
        assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, errorCode("permission_denied", 403))
        assertEquals(ErrorCode.LLM_INVALID_REQUEST, errorCode("payment_required", 402))
        assertEquals(ErrorCode.LLM_UNAVAILABLE, errorCode("provider_unavailable", 502))
        assertEquals(ErrorCode.LLM_UNAVAILABLE, errorCode("provider_overloaded", 503))
        assertEquals(ErrorCode.LLM_UNAVAILABLE, errorCode("server", 500))
        assertEquals(ErrorCode.LLM_TIMEOUT, errorCode("timeout", 504))
        assertEquals(ErrorCode.LLM_INVALID_REQUEST, errorCode("context_length_exceeded", 400))
        assertEquals(ErrorCode.LLM_INVALID_REQUEST, errorCode("content_policy_violation", 403))
        // No typed error_type: fall back to the HTTP status code.
        assertEquals(ErrorCode.LLM_RATE_LIMITED, errorCode(null, 429))
        assertEquals(ErrorCode.LLM_UNAVAILABLE, errorCode(null, 500))
    }

    @Test
    fun aFrameThatIsNotValidJsonBecomesMalformedResponse() {
        val parsed = OpenRouterChatCompletions.parse(SseFrame(event = null, data = "{not json"))

        assertTrue(parsed is OpenRouterStreamFrame.Failed)
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (parsed as OpenRouterStreamFrame.Failed).error.code)
    }

    @Test
    fun theModelCatalogCarriesPerModelReasoningEfforts() {
        val models = OpenRouterModels.parse(RecordedFixtures.text("llm/openrouter/models_list.json"))

        val claude = models.first { it.id.value == "anthropic/claude-test-1" }
        assertEquals(
            setOf(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            claude.reasoningLevels,
        )
        assertFalse(claude.reasoningMandatory)
        assertTrue(claude.supportsReasoningMaxTokens)

        // mandatory = true rejects `effort: "none"`, so NONE is not offered.
        val mandatory = models.first { it.id.value == "openai/gpt-test-reasoning" }
        assertTrue(mandatory.reasoningMandatory)
        assertFalse(mandatory.reasoningLevels.contains(ReasoningLevel.NONE))
        assertTrue(mandatory.reasoningLevels.contains(ReasoningLevel.MAX))

        // A null supported_efforts means every gateway effort is accepted.
        val allEfforts = models.first { it.id.value == "openai/gpt-test-all-efforts" }
        assertEquals(OpenRouterModels.GATEWAY_EFFORTS, allEfforts.reasoningLevels)

        // A model with no reasoning object claims no reasoning level.
        val plain = models.first { it.id.value == "meta-llama/llama-test-plain" }
        assertTrue(plain.reasoningLevels.isEmpty())
    }

    @Test
    fun theCatalogRefinesReasoningPerModelAndClaimsNothingForAnUnknownModel() {
        val catalog = OpenRouterModelCatalog.fromModelsBody(RecordedFixtures.text("llm/openrouter/models_list.json"))

        val claude = catalog.modelCapabilities(KnownProviders.OPENROUTER, ModelId("anthropic/claude-test-1"))!!
        assertEquals(
            setOf(ReasoningLevel.MINIMAL, ReasoningLevel.LOW, ReasoningLevel.MEDIUM, ReasoningLevel.HIGH),
            claude.reasoningLevels,
        )
        assertTrue(claude.streaming)
        assertTrue(claude.usageReporting)

        assertNull(catalog.modelCapabilities(KnownProviders.OPENROUTER, ModelId("not/a-model")))
        assertNull(catalog.modelCapabilities(ProviderId("openai"), ModelId("anthropic/claude-test-1")))
    }

    @Test
    fun parseModelIdsReturnsOnlyTheIdentities() {
        val ids = OpenRouterModels.parseModelIds(RecordedFixtures.text("llm/openrouter/models_list.json"))

        assertEquals(
            listOf(
                ModelId("anthropic/claude-test-1"),
                ModelId("openai/gpt-test-reasoning"),
                ModelId("openai/gpt-test-all-efforts"),
                ModelId("meta-llama/llama-test-plain"),
            ),
            ids,
        )
    }

    private fun errorCode(
        errorType: String?,
        status: Int,
    ): ErrorCode {
        val metadata = if (errorType == null) "" else ""","metadata":{"error_type":"$errorType"}"""
        val data =
            """{"id":"gen","object":"chat.completion.chunk","created":1,"model":"m","provider":"P",""" +
                """"error":{"code":$status,"message":"upstream detail"""" +
                metadata +
                """},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}"""
        val parsed = OpenRouterChatCompletions.parse(SseFrame(event = null, data = data))
        assertTrue(parsed is OpenRouterStreamFrame.Failed)
        return (parsed as OpenRouterStreamFrame.Failed).error.code
    }

    private fun chunk(delta: String): String =
        """{"id":"gen","object":"chat.completion.chunk","created":1,"model":"$modelId","provider":"P",""" +
            """"choices":[{"index":0,"delta":$delta,"finish_reason":null}]}"""
}
