package com.voicechat.agent.providers.openai

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
 * Protocol-level tests for the OpenAI Responses mapping: the request payload the
 * adapter sends and the interpretation of each streamed frame. These run with no
 * HTTP and no credential.
 */
class OpenAiResponsesTest {
    private fun request(
        messages: List<LlmMessage> = listOf(LlmMessage(LlmRole.USER, "hello")),
        reasoning: ReasoningLevel? = null,
    ) = LlmRequest(
        model = ProviderModelSelection(KnownProviders.OPENAI, ModelId("gpt-test-1")),
        messages = messages,
        reasoning = reasoning,
    )

    @Test
    fun theRequestAlwaysDisablesStorageAndEnablesStreaming() {
        val root = RemoteJson.parse(OpenAiResponses.encodeRequest(request())) as JsonNode.Obj

        assertEquals("gpt-test-1", root.stringField("model"))
        assertEquals(false, (root.get("store") as JsonNode.Bool).value)
        assertEquals(true, (root.get("stream") as JsonNode.Bool).value)
    }

    @Test
    fun systemMessagesUseTheDeveloperRole() {
        val messages =
            listOf(
                LlmMessage(LlmRole.SYSTEM, "be brief"),
                LlmMessage(LlmRole.USER, "hi"),
                LlmMessage(LlmRole.ASSISTANT, "hey"),
            )

        val root = RemoteJson.parse(OpenAiResponses.encodeRequest(request(messages))) as JsonNode.Obj

        assertEquals(
            listOf("developer", "user", "assistant"),
            root.arrField("input")!!.items.map { (it as JsonNode.Obj).stringField("role") },
        )
    }

    @Test
    fun aRealReasoningLevelIsSentAsEffortAndNoneIsOmitted() {
        val medium =
            RemoteJson.parse(OpenAiResponses.encodeRequest(request(reasoning = ReasoningLevel.MEDIUM))) as JsonNode.Obj
        assertEquals("medium", medium.objField("reasoning")?.stringField("effort"))

        // "none" is model-dependent and can be rejected with 400; omitting it is the
        // honest way to express "do not reason".
        val none = RemoteJson.parse(OpenAiResponses.encodeRequest(request(reasoning = ReasoningLevel.NONE))) as JsonNode.Obj
        assertNull(none.get("reasoning"))
    }

    @Test
    fun outputTextDeltasBecomeTextAndReasoningDeltasAreASeparateChannel() {
        val text =
            OpenAiResponses.parse(
                SseFrame(
                    event = "response.output_text.delta",
                    data = "{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}",
                ),
            )
        assertEquals(OpenAiStreamFrame.Text("Hi"), text)

        val reasoning =
            OpenAiResponses.parse(
                SseFrame(
                    event = "response.reasoning_summary_text.delta",
                    data = "{\"type\":\"response.reasoning_summary_text.delta\",\"delta\":\"thinking\"}",
                ),
            )
        assertEquals(OpenAiStreamFrame.Reasoning("thinking"), reasoning)
    }

    @Test
    fun completionCarriesUsageModelAndReasoning() {
        val frame =
            SseFrame(
                event = "response.completed",
                data =
                    "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-test-1\"," +
                        "\"reasoning\":{\"effort\":\"low\"},\"usage\":{\"input_tokens\":12,\"output_tokens\":7," +
                        "\"total_tokens\":19,\"output_tokens_details\":{\"reasoning_tokens\":3}}}}",
            )

        val completed = OpenAiResponses.parse(frame) as OpenAiStreamFrame.Completed

        assertEquals(ModelId("gpt-test-1"), completed.model)
        assertEquals(12, completed.usage?.promptTokens)
        assertEquals(7, completed.usage?.completionTokens)
        assertEquals(19, completed.usage?.totalTokens)
        assertEquals("3", completed.usage?.details?.get("reasoningTokens"))
        assertEquals(ReasoningLevel.LOW, completed.reasoning)
    }

    @Test
    fun aCompletionWithoutUsageReportsNullNotZero() {
        val frame =
            SseFrame(
                event = "response.completed",
                data = "{\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"model\":\"gpt-test-1\"}}",
            )

        val completed = OpenAiResponses.parse(frame) as OpenAiStreamFrame.Completed

        assertNull(completed.usage)
    }

    @Test
    fun errorEventsMapToTypedFailures() {
        val rateLimited =
            OpenAiResponses.parse(
                SseFrame("error", "{\"type\":\"error\",\"code\":\"rate_limit_exceeded\",\"message\":\"x\"}"),
            )
        assertEquals(ErrorCode.LLM_RATE_LIMITED, (rateLimited as OpenAiStreamFrame.Failed).error.code)

        val server =
            OpenAiResponses.parse(SseFrame("error", "{\"type\":\"error\",\"code\":\"server_error\"}"))
        assertEquals(ErrorCode.LLM_UNAVAILABLE, (server as OpenAiStreamFrame.Failed).error.code)
    }

    @Test
    fun aMalformedFrameIsATypedFailureAndIgnoredEventsAreDropped() {
        val malformed = OpenAiResponses.parse(SseFrame("response.output_text.delta", "{not json"))
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (malformed as OpenAiStreamFrame.Failed).error.code)

        val ignored =
            OpenAiResponses.parse(
                SseFrame("response.created", "{\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\"}}"),
            )
        assertEquals(OpenAiStreamFrame.Ignored, ignored)
    }

    @Test
    fun aFailedResponseEventMapsTheProviderError() {
        val failed =
            OpenAiResponses.parse(
                SseFrame(
                    "response.failed",
                    "{\"type\":\"response.failed\",\"response\":{\"error\":{\"code\":\"server_error\"}}}",
                ),
            )
        assertEquals(ErrorCode.LLM_UNAVAILABLE, (failed as OpenAiStreamFrame.Failed).error.code)
    }

    @Test
    fun effortWireAndReasoningFromWireRoundTrip() {
        ReasoningLevel.entries.forEach { level ->
            assertEquals(level, OpenAiResponses.reasoningFromWire(OpenAiResponses.effortWire(level)))
        }
        assertNull(OpenAiResponses.reasoningFromWire("hyper"))
    }

    @Test
    fun theModelsEndpointListsModelIdentities() {
        val body =
            "{\"object\":\"list\",\"data\":[{\"id\":\"gpt-test-1\",\"object\":\"model\"}," +
                "{\"id\":\"gpt-test-2\",\"object\":\"model\"},{\"object\":\"model\"}]}"

        assertEquals(listOf(ModelId("gpt-test-1"), ModelId("gpt-test-2")), OpenAiModels.parseModelIds(body))
        assertTrue(OpenAiModels.parseModelIds("{\"data\":[]}").isEmpty())
        assertEquals("/models", OpenAiModels.PATH)
    }
}
