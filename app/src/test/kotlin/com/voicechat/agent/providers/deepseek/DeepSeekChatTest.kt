package com.voicechat.agent.providers.deepseek

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.SseFrame
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.get
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for the DeepSeek Chat Completions mapping: the request
 * payload the adapter sends and the interpretation of each streamed frame. These
 * run with no HTTP and no credential and pin DeepSeek's own wire shapes rather
 * than any other provider's.
 */
class DeepSeekChatTest {
    private fun request(
        messages: List<LlmMessage> = listOf(LlmMessage(LlmRole.USER, "hello")),
        reasoning: ReasoningLevel? = null,
    ) = LlmRequest(
        model = ProviderModelSelection(KnownProviders.DEEPSEEK, ModelId("deepseek-flash")),
        messages = messages,
        reasoning = reasoning,
    )

    private fun chunk(
        delta: String,
        finishReason: String? = null,
        usage: String? = null,
    ): String =
        "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":1," +
            "\"model\":\"deepseek-flash\",\"choices\":[{\"index\":0,\"delta\":$delta," +
            "\"finish_reason\":${finishReason?.let { "\"$it\"" } ?: "null"}}]" +
            (usage?.let { ",\"usage\":$it" } ?: "") +
            "}"

    @Test
    fun theRequestSendsTheSelectedModelMessagesAndStreamingAndNoStorageField() {
        val root = RemoteJson.parse(DeepSeekChat.encodeRequest(request())) as JsonNode.Obj

        assertEquals("deepseek-flash", root.stringField("model"))
        assertEquals(true, (root.get("stream") as JsonNode.Bool).value)
        // DeepSeek's stateless Chat Completions API documents no storage option.
        assertNull(root.get("store"))
        assertNull(root.get("thinking"))
        assertNull(root.get("reasoning_effort"))
    }

    @Test
    fun systemMessagesUseTheSystemRole() {
        val messages =
            listOf(
                LlmMessage(LlmRole.SYSTEM, "be brief"),
                LlmMessage(LlmRole.USER, "hi"),
                LlmMessage(LlmRole.ASSISTANT, "hey"),
            )

        val root = RemoteJson.parse(DeepSeekChat.encodeRequest(request(messages))) as JsonNode.Obj

        assertEquals(
            listOf("system", "user", "assistant"),
            root.arrField("messages")!!.items.map { (it as JsonNode.Obj).stringField("role") },
        )
    }

    @Test
    fun noneDisablesThinkingAndARealLevelEnablesItWithTheDocumentedEffort() {
        val none = RemoteJson.parse(DeepSeekChat.encodeRequest(request(reasoning = ReasoningLevel.NONE))) as JsonNode.Obj
        assertEquals("disabled", none.objField("thinking")?.stringField("type"))
        assertNull(none.get("reasoning_effort"))

        val high = RemoteJson.parse(DeepSeekChat.encodeRequest(request(reasoning = ReasoningLevel.HIGH))) as JsonNode.Obj
        assertEquals("enabled", high.objField("thinking")?.stringField("type"))
        assertEquals("high", high.stringField("reasoning_effort"))
    }

    @Test
    fun everyReasoningLevelMapsToItsDocumentedSpelling() {
        assertEquals("none", DeepSeekChat.effortWire(ReasoningLevel.NONE))
        assertEquals("minimal", DeepSeekChat.effortWire(ReasoningLevel.MINIMAL))
        assertEquals("low", DeepSeekChat.effortWire(ReasoningLevel.LOW))
        assertEquals("medium", DeepSeekChat.effortWire(ReasoningLevel.MEDIUM))
        assertEquals("high", DeepSeekChat.effortWire(ReasoningLevel.HIGH))
        assertEquals("xhigh", DeepSeekChat.effortWire(ReasoningLevel.XHIGH))
        assertEquals("max", DeepSeekChat.effortWire(ReasoningLevel.MAX))
    }

    @Test
    fun aContentDeltaBecomesTextAndAReasoningContentDeltaIsASeparateChannel() {
        val text = DeepSeekChat.parse(SseFrame(null, chunk("{\"content\":\"Hi\"}")))
        assertEquals(listOf<DeepSeekStreamFrame>(DeepSeekStreamFrame.Text("Hi")), text)

        val reasoning = DeepSeekChat.parse(SseFrame(null, chunk("{\"reasoning_content\":\"thinking\"}")))
        assertEquals(listOf<DeepSeekStreamFrame>(DeepSeekStreamFrame.Reasoning("thinking")), reasoning)
    }

    @Test
    fun theFinishReasonChunkCarriesUsageAndModelAsTheCompletion() {
        val frames =
            DeepSeekChat.parse(
                SseFrame(
                    null,
                    chunk(
                        delta = "{}",
                        finishReason = "stop",
                        usage =
                            "{\"prompt_tokens\":12,\"completion_tokens\":7,\"total_tokens\":19," +
                                "\"prompt_cache_hit_tokens\":2,\"prompt_cache_miss_tokens\":10," +
                                "\"completion_tokens_details\":{\"reasoning_tokens\":3}}",
                    ),
                ),
            )

        val completed = frames.single() as DeepSeekStreamFrame.Completed
        assertEquals(ModelId("deepseek-flash"), completed.model)
        assertEquals("stop", completed.finishReason)
        assertEquals(12, completed.usage?.promptTokens)
        assertEquals(7, completed.usage?.completionTokens)
        assertEquals(19, completed.usage?.totalTokens)
        assertEquals("2", completed.usage?.details?.get("cachedInputTokens"))
        assertEquals("10", completed.usage?.details?.get("uncachedInputTokens"))
        assertEquals("3", completed.usage?.details?.get("reasoningTokens"))
    }

    @Test
    fun aTruncatedFinishReasonIsATypedFailureNotACompletion() {
        val frames = DeepSeekChat.parse(SseFrame(null, chunk("{}", finishReason = "length", usage = "{\"total_tokens\":13}")))

        val failed = frames.single() as DeepSeekStreamFrame.Failed
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, failed.error.code)
    }

    @Test
    fun aStreamedErrorBodyMapsToATypedFailureAndNeverTheProviderMessage() {
        val frames =
            DeepSeekChat.parse(
                SseFrame(null, "{\"error\":{\"message\":\"secret prompt echo\",\"type\":\"insufficient_balance\"}}"),
            )

        val failed = frames.single() as DeepSeekStreamFrame.Failed
        assertEquals(ErrorCode.LLM_UNAVAILABLE, failed.error.code)
        assertTrue("the provider message must not be copied", failed.error.detail?.contains("secret prompt echo") != true)
    }

    @Test
    fun aMalformedFrameIsATypedFailureAndTheDoneSentinelIsSeparate() {
        val malformed = DeepSeekChat.parse(SseFrame(null, "{not json"))
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (malformed.single() as DeepSeekStreamFrame.Failed).error.code)

        assertEquals(listOf<DeepSeekStreamFrame>(DeepSeekStreamFrame.Done), DeepSeekChat.parse(SseFrame(null, "[DONE]")))
    }

    @Test
    fun aRoleOnlyChunkIsIgnored() {
        val frames = DeepSeekChat.parse(SseFrame(null, chunk("{\"role\":\"assistant\",\"content\":\"\"}")))

        assertEquals(listOf<DeepSeekStreamFrame>(DeepSeekStreamFrame.Ignored), frames)
    }

    @Test
    fun theModelsEndpointListsModelIdentities() {
        val body =
            "{\"object\":\"list\",\"data\":[{\"id\":\"deepseek-flash\",\"object\":\"model\"}," +
                "{\"id\":\"deepseek-v4-pro\",\"object\":\"model\"},{\"object\":\"model\"}]}"

        assertEquals(listOf(ModelId("deepseek-flash"), ModelId("deepseek-v4-pro")), DeepSeekModels.parseModelIds(body))
        assertTrue(DeepSeekModels.parseModelIds("{\"data\":[]}").isEmpty())
        assertEquals("/models", DeepSeekModels.PATH)
    }
}
