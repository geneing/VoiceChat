package com.voicechat.agent.providers.opencodezen

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Protocol-level tests for the OpenCode Zen mapping (M18): the model surface, the
 * per-model family dispatch, each chat family's request payload, and each
 * family's stream interpretation. No HTTP and no credential are involved.
 */
class OpenCodeZenProtocolTest {
    private fun request(
        model: String = "glm-5.3-flash",
        messages: List<LlmMessage> = listOf(LlmMessage(LlmRole.USER, "hello")),
    ) = LlmRequest(
        model = ProviderModelSelection(KnownProviders.OPENCODE_ZEN, ModelId(model)),
        messages = messages,
    )

    // --- model surface and family dispatch -----------------------------------

    @Test
    fun theModelsEndpointListsIdentitiesOnly() {
        val body =
            "{\"object\":\"list\",\"data\":[" +
                "{\"id\":\"glm-5.3-flash\",\"object\":\"model\",\"created\":1,\"owned_by\":\"opencode\"}," +
                "{\"id\":\"claude-sonnet-4-5\",\"object\":\"model\"},{\"object\":\"model\"}]}"

        assertEquals(
            listOf(ModelId("glm-5.3-flash"), ModelId("claude-sonnet-4-5")),
            OpenCodeZenModels.parseModelIds(body),
        )
        assertEquals("/models", OpenCodeZenModels.PATH)
    }

    @Test
    fun eachDocumentedModelIsDispatchedToItsDocumentedFamily() {
        assertEquals(OpenCodeZenFamily.CHAT_COMPLETIONS, OpenCodeZenModels.familyFor(ModelId("glm-5.3-flash")))
        assertEquals(OpenCodeZenFamily.CHAT_COMPLETIONS, OpenCodeZenModels.familyFor(ModelId("deepseek-v4-flash")))
        assertEquals(OpenCodeZenFamily.RESPONSES, OpenCodeZenModels.familyFor(ModelId("gpt-5.6-luna")))
        assertEquals(OpenCodeZenFamily.RESPONSES, OpenCodeZenModels.familyFor(ModelId("grok-4.7")))
        assertEquals(OpenCodeZenFamily.MESSAGES, OpenCodeZenModels.familyFor(ModelId("claude-sonnet-4-5")))
        assertEquals(OpenCodeZenFamily.MESSAGES, OpenCodeZenModels.familyFor(ModelId("qwen3.8-flash")))

        assertEquals("/chat/completions", OpenCodeZenFamily.CHAT_COMPLETIONS.path)
        assertEquals("/responses", OpenCodeZenFamily.RESPONSES.path)
        assertEquals("/messages", OpenCodeZenFamily.MESSAGES.path)
    }

    @Test
    fun aModelTheTableDoesNotPlaceHasNoAssumedFamily() {
        // Present in GET /models but not placed by the Zen endpoint table, so its
        // protocol must not be guessed (this is the "no invented parity" rule).
        assertNull(OpenCodeZenModels.familyFor(ModelId("claude-sonnet-5-5")))
        assertNull(OpenCodeZenModels.familyFor(ModelId("deepseek-v4-flash-free")))
    }

    @Test
    fun theSystemOneDecisionModelsAreNeverChatModels() {
        OpenCodeZenModels.SYSTEM_ONE_MODEL_IDS.forEach { id ->
            assertNull("/systemone ($id) is a decision model, not a chat completion", OpenCodeZenModels.familyFor(ModelId(id)))
            assertFalse(OpenCodeZenModels.documentedFamilies().containsKey(id))
        }
        // The documented ids are real ids the Zen page lists.
        assertEquals(setOf("jev-1.13", "jev-1.13-free"), OpenCodeZenModels.SYSTEM_ONE_MODEL_IDS)
    }

    @Test
    fun theGoogleFamilyModelsAreRefusedBecauseThatFamilyIsNotImplemented() {
        OpenCodeZenModels.GOOGLE_FAMILY_MODEL_IDS.forEach { id ->
            assertNull("the Google generateContent family is not implemented", OpenCodeZenModels.familyFor(ModelId(id)))
            assertFalse(OpenCodeZenModels.documentedFamilies().containsKey(id))
        }
        assertTrue(OpenCodeZenModels.GOOGLE_FAMILY_MODEL_IDS.contains("gemini-3.1-pro"))
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
        val root = RemoteJson.parse(OpenCodeZenChatCompletions.encodeRequest(request(messages = messages))) as JsonNode.Obj

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
        val root = RemoteJson.parse(OpenCodeZenResponses.encodeRequest(request("gpt-5.6-luna", messages))) as JsonNode.Obj

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
        val root = RemoteJson.parse(OpenCodeZenMessages.encodeRequest(request("claude-sonnet-4-5", messages))) as JsonNode.Obj

        assertEquals("be brief\n\nbe kind", root.stringField("system"))
        assertEquals(OpenCodeZenMessages.DEFAULT_MAX_TOKENS, root.intField("max_tokens"))
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
            OpenCodeZenChatCompletions.parse(
                SseFrame(
                    null,
                    "{\"model\":\"glm-5.3-flash\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hi\"}}]}",
                ),
            ) as OpenCodeZenStreamFrame.Delta
        assertEquals("Hi", delta.text)
        assertEquals(ModelId("glm-5.3-flash"), delta.model)

        assertEquals(
            OpenCodeZenStreamFrame.Completed(model = null, usage = null),
            OpenCodeZenChatCompletions.parse(SseFrame(null, "[DONE]")),
        )
    }

    @Test
    fun theResponsesFamilyReadsDeltasAndTheCompletedEvent() {
        val delta =
            OpenCodeZenResponses.parse(
                SseFrame("response.output_text.delta", "{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}"),
            )
        assertEquals(OpenCodeZenStreamFrame.Delta("Hi"), delta)

        val completed =
            OpenCodeZenResponses.parse(
                SseFrame(
                    "response.completed",
                    "{\"type\":\"response.completed\",\"response\":{\"model\":\"grok-4.7\"," +
                        "\"usage\":{\"input_tokens\":3,\"output_tokens\":2,\"total_tokens\":5}}}",
                ),
            ) as OpenCodeZenStreamFrame.Completed
        assertEquals(ModelId("grok-4.7"), completed.model)
        assertEquals(5, completed.usage?.totalTokens)
    }

    @Test
    fun theMessagesFamilyReadsTextDeltasAndIgnoresTheThinkingChannel() {
        val delta =
            OpenCodeZenMessages.parse(
                SseFrame(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}",
                ),
            )
        assertEquals(OpenCodeZenStreamFrame.Delta("Hi"), delta)

        val thinking =
            OpenCodeZenMessages.parse(
                SseFrame(
                    "content_block_delta",
                    "{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"hmm\"}}",
                ),
            )
        assertEquals(OpenCodeZenStreamFrame.Ignored, thinking)

        assertEquals(
            OpenCodeZenStreamFrame.Completed(model = null, usage = null),
            OpenCodeZenMessages.parse(SseFrame("message_stop", "{\"type\":\"message_stop\"}")),
        )
    }

    // --- errors --------------------------------------------------------------

    @Test
    fun bothErrorVocabulariesMapToTypedReasons() {
        assertEquals(ErrorCode.LLM_RATE_LIMITED, OpenCodeZenErrors.forCode("rate_limit_exceeded")?.code)
        assertEquals(ErrorCode.LLM_RATE_LIMITED, OpenCodeZenErrors.forCode("rate_limit_error")?.code)
        assertEquals(ErrorCode.LLM_UNAVAILABLE, OpenCodeZenErrors.forCode("overloaded_error")?.code)
        assertEquals(ErrorCode.LLM_UNAVAILABLE, OpenCodeZenErrors.forCode("server_error")?.code)
        assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, OpenCodeZenErrors.forCode("authentication_error")?.code)
        assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, OpenCodeZenErrors.forCode("permission_error")?.code)
        assertEquals(ErrorCode.LLM_INVALID_REQUEST, OpenCodeZenErrors.forCode("invalid_request_error")?.code)
        assertEquals(ErrorCode.LLM_REQUEST_FAILED, OpenCodeZenErrors.forCode("something_new")?.code)
        assertNull(OpenCodeZenErrors.forCode(null))
    }

    @Test
    fun aMalformedFrameIsATypedFailureNotAnException() {
        val malformed = OpenCodeZenResponses.parse(SseFrame("response.output_text.delta", "{not json"))
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (malformed as OpenCodeZenStreamFrame.Failed).error.code)

        val chatMalformed = OpenCodeZenChatCompletions.parse(SseFrame(null, "{not json"))
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (chatMalformed as OpenCodeZenStreamFrame.Failed).error.code)
    }

    @Test
    fun theFamilyMapCoversExactlyTheThreeDocumentedChatProtocols() {
        assertTrue(OpenCodeZenModels.documentedFamilies().values.toSet() == OpenCodeZenFamily.entries.toSet())
        assertTrue(OpenCodeZenModels.documentedFamilies().isNotEmpty())
    }
}
