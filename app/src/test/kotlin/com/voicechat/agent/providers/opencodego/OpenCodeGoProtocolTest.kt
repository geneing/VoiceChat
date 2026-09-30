package com.voicechat.agent.providers.opencodego

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.remote.JsonNode
import com.voicechat.agent.remote.RemoteJson
import com.voicechat.agent.remote.SseFrame
import com.voicechat.agent.remote.arrField
import com.voicechat.agent.remote.get
import com.voicechat.agent.remote.intField
import com.voicechat.agent.remote.objField
import com.voicechat.agent.remote.stringField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for the OpenCode Go mapping (M17): the model surface, the
 * per-model family dispatch, each family's request payload, and each family's
 * stream interpretation. No HTTP and no credential are involved.
 */
class OpenCodeGoProtocolTest {
    private fun request(
        model: String = "glm-5.3-flash",
        messages: List<LlmMessage> = listOf(LlmMessage(LlmRole.USER, "hello")),
    ) = LlmRequest(
        model = ProviderModelSelection(KnownProviders.OPENCODE_GO, ModelId(model)),
        messages = messages,
    )

    // --- model surface and family dispatch -----------------------------------

    @Test
    fun theModelsEndpointListsIdentitiesOnly() {
        val body =
            "{\"object\":\"list\",\"data\":[" +
                "{\"id\":\"glm-5.3-flash\",\"object\":\"model\",\"owned_by\":\"opencode\"}," +
                "{\"id\":\"qwen3.8-max\",\"object\":\"model\"},{\"object\":\"model\"}]}"

        assertEquals(
            listOf(ModelId("glm-5.3-flash"), ModelId("qwen3.8-max")),
            OpenCodeGoModels.parseModelIds(body),
        )
        assertEquals("/models", OpenCodeGoModels.PATH)
    }

    @Test
    fun eachDocumentedModelIsDispatchedToItsDocumentedFamily() {
        assertEquals(OpenCodeGoFamily.CHAT_COMPLETIONS, OpenCodeGoModels.familyFor(ModelId("deepseek-v4-flash")))
        assertEquals(OpenCodeGoFamily.CHAT_COMPLETIONS, OpenCodeGoModels.familyFor(ModelId("glm-5.3-flash")))
        assertEquals(OpenCodeGoFamily.RESPONSES, OpenCodeGoModels.familyFor(ModelId("gpt-5.6-luna")))
        assertEquals(OpenCodeGoFamily.RESPONSES, OpenCodeGoModels.familyFor(ModelId("grok-4.7")))
        assertEquals(OpenCodeGoFamily.MESSAGES, OpenCodeGoModels.familyFor(ModelId("qwen3.8-flash")))
        assertEquals(OpenCodeGoFamily.MESSAGES, OpenCodeGoModels.familyFor(ModelId("minimax-m3")))

        assertEquals("/chat/completions", OpenCodeGoFamily.CHAT_COMPLETIONS.path)
        assertEquals("/responses", OpenCodeGoFamily.RESPONSES.path)
        assertEquals("/messages", OpenCodeGoFamily.MESSAGES.path)
    }

    @Test
    fun aModelTheTableDoesNotPlaceHasNoAssumedFamily() {
        // Present in GET /models but not placed by the Go endpoint table, so its
        // protocol must not be guessed (this is the "no invented parity" rule).
        assertNull(OpenCodeGoModels.familyFor(ModelId("omen-alpha")))
        assertNull(OpenCodeGoModels.familyFor(ModelId("hy3-preview")))
    }

    // --- request payloads ----------------------------------------------------

    @Test
    fun theChatCompletionsRequestIsTheCompatiblePayload() {
        val messages =
            listOf(
                LlmMessage(LlmRole.SYSTEM, "be brief"),
                LlmMessage(LlmRole.USER, "hi"),
                LlmMessage(LlmRole.ASSISTANT, "hey"),
            )
        val root = RemoteJson.parse(OpenCodeGoChatCompletions.encodeRequest(request(messages = messages))) as JsonNode.Obj

        assertEquals("glm-5.3-flash", root.stringField("model"))
        assertEquals(true, (root.get("stream") as JsonNode.Bool).value)
        assertNull("chat completions does not take max_tokens", root.get("max_tokens"))
        assertEquals(
            listOf("system", "user", "assistant"),
            root.arrField("messages")!!.items.map { (it as JsonNode.Obj).stringField("role") },
        )
    }

    @Test
    fun theResponsesRequestAlwaysDisablesStorageAndMapsSystemToDeveloper() {
        val messages =
            listOf(
                LlmMessage(LlmRole.SYSTEM, "be brief"),
                LlmMessage(LlmRole.USER, "hi"),
            )
        val root = RemoteJson.parse(OpenCodeGoResponses.encodeRequest(request("gpt-5.6-luna", messages))) as JsonNode.Obj

        assertEquals(false, (root.get("store") as JsonNode.Bool).value)
        assertEquals(true, (root.get("stream") as JsonNode.Bool).value)
        assertEquals("gpt-5.6-luna", root.stringField("model"))
        assertEquals(
            listOf("developer", "user"),
            root.arrField("input")!!.items.map { (it as JsonNode.Obj).stringField("role") },
        )
    }

    @Test
    fun theMessagesRequestLiftsSystemAndSendsTheRequiredMaxTokens() {
        val messages =
            listOf(
                LlmMessage(LlmRole.SYSTEM, "be brief"),
                LlmMessage(LlmRole.SYSTEM, "be kind"),
                LlmMessage(LlmRole.USER, "hi"),
                LlmMessage(LlmRole.ASSISTANT, "hey"),
            )
        val root = RemoteJson.parse(OpenCodeGoMessages.encodeRequest(request("qwen3.8-flash", messages))) as JsonNode.Obj

        assertEquals("be brief\n\nbe kind", root.stringField("system"))
        assertEquals(OpenCodeGoMessages.DEFAULT_MAX_TOKENS, root.intField("max_tokens"))
        assertEquals(true, (root.get("stream") as JsonNode.Bool).value)
        assertEquals(
            listOf("user", "assistant"),
            root.arrField("messages")!!.items.map { (it as JsonNode.Obj).stringField("role") },
        )
    }

    // --- stream interpretation ----------------------------------------------

    @Test
    fun theChatFamilyReadsDeltasAndTheDoneSentinel() {
        val delta =
            OpenCodeGoChatCompletions.parse(
                SseFrame(
                    null,
                    "{\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}",
                ),
            ) as OpenCodeGoStreamFrame.Delta
        assertEquals("Hi", delta.text)
        assertEquals(ModelId("glm-5.3-flash"), delta.model)

        assertEquals(
            OpenCodeGoStreamFrame.Completed(model = null, usage = null),
            OpenCodeGoChatCompletions.parse(SseFrame(null, "[DONE]")),
        )
    }

    @Test
    fun theResponsesFamilyReadsDeltasAndTheCompletedEvent() {
        val delta =
            OpenCodeGoResponses.parse(
                SseFrame("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}"),
            )
        assertEquals(OpenCodeGoStreamFrame.Delta("Hi"), delta)

        val completed =
            OpenCodeGoResponses.parse(
                SseFrame(
                    "response.completed",
                    "{\"type\":\"response.completed\",\"response\":{\"model\":\"grok-4.7\"," +
                        "\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}}",
                ),
            ) as OpenCodeGoStreamFrame.Completed
        assertEquals(ModelId("grok-4.7"), completed.model)
        assertEquals(5, completed.usage?.totalTokens)
    }

    @Test
    fun theMessagesFamilyReadsTextDeltasAndIgnoresTheThinkingChannel() {
        val delta =
            OpenCodeGoMessages.parse(
                SseFrame(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}",
                ),
            )
        assertEquals(OpenCodeGoStreamFrame.Delta("Hi"), delta)

        val thinking =
            OpenCodeGoMessages.parse(
                SseFrame(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}",
                ),
            )
        assertEquals(OpenCodeGoStreamFrame.Ignored, thinking)

        assertEquals(
            OpenCodeGoStreamFrame.Completed(model = null, usage = null),
            OpenCodeGoMessages.parse(SseFrame("message_stop", "{\"type\":\"message_stop\"}")),
        )
    }

    // --- errors --------------------------------------------------------------

    @Test
    fun bothErrorVocabulariesMapToTypedReasons() {
        assertEquals(ErrorCode.LLM_RATE_LIMITED, OpenCodeGoErrors.forCode("rate_limit_exceeded")?.code)
        assertEquals(ErrorCode.LLM_RATE_LIMITED, OpenCodeGoErrors.forCode("rate_limit_error")?.code)
        assertEquals(ErrorCode.LLM_UNAVAILABLE, OpenCodeGoErrors.forCode("overloaded_error")?.code)
        assertEquals(ErrorCode.LLM_UNAVAILABLE, OpenCodeGoErrors.forCode("server_error")?.code)
        assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, OpenCodeGoErrors.forCode("authentication_error")?.code)
        assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, OpenCodeGoErrors.forCode("permission_error")?.code)
        assertEquals(ErrorCode.LLM_INVALID_REQUEST, OpenCodeGoErrors.forCode("invalid_request_error")?.code)
        assertEquals(ErrorCode.LLM_REQUEST_FAILED, OpenCodeGoErrors.forCode("something_new")?.code)
        assertNull(OpenCodeGoErrors.forCode(null))
    }

    @Test
    fun aMalformedFrameIsATypedFailureNotAnException() {
        val malformed = OpenCodeGoResponses.parse(SseFrame("response.output_text.delta", "{not json"))
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (malformed as OpenCodeGoStreamFrame.Failed).error.code)

        val chatMalformed = OpenCodeGoChatCompletions.parse(SseFrame(null, "{not json"))
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (chatMalformed as OpenCodeGoStreamFrame.Failed).error.code)
    }

    @Test
    fun theFamilyMapCoversExactlyTheThreeDocumentedProtocols() {
        assertTrue(OpenCodeGoModels.documentedFamilies().values.toSet() == OpenCodeGoFamily.entries.toSet())
        assertTrue(OpenCodeGoModels.documentedFamilies().isNotEmpty())
    }
}
