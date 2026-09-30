package com.voicechat.agent.providers.hermes

import com.voicechat.agent.contracts.LlmMessage
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmRole
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.remote.RecordedFixtures
import com.voicechat.agent.remote.SseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M19 protocol-level acceptance for the Hermes Agent API Server mapping: request
 * encoding, role mapping, the reasoning opt-out, SSE frame interpretation, the
 * custom `hermes.tool.progress` event, the `[DONE]` sentinel, error mapping, and
 * model/capability discovery. No HTTP, socket, clock, or credential is involved.
 */
class HermesProtocolTest {
    private val modelId = ModelId("hermes-agent")

    private fun request(reasoning: ReasoningLevel? = null) =
        LlmRequest(
            model = ProviderModelSelection(KnownProviders.HERMES, modelId),
            messages = listOf(LlmMessage(LlmRole.SYSTEM, "be terse"), LlmMessage(LlmRole.USER, "hello")),
            reasoning = reasoning,
        )

    private fun frame(
        data: String,
        event: String? = null,
    ) = SseFrame(event = event, data = data)

    @Test
    fun theRequestIsAChatCompletionsPayloadForTheSelectedModel() {
        val body = HermesChatCompletions.encodeRequest(request())

        assertTrue(body.contains("\"model\":\"hermes-agent\""))
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("\"role\":\"system\",\"content\":\"be terse\""))
        assertTrue(body.contains("\"role\":\"user\",\"content\":\"hello\""))
        // No reasoning requested: no model_options is fabricated.
        assertFalse(body.contains("model_options"))
    }

    @Test
    fun noneReasoningSendsTheDocumentedOptOut() {
        val body = HermesChatCompletions.encodeRequest(request(reasoning = ReasoningLevel.NONE))

        assertTrue(body.contains("\"model_options\":{\"reasoning\":{\"enabled\":false}}"))
    }

    @Test
    fun aContentDeltaBecomesATextFrame() {
        val parsed = HermesChatCompletions.parse(frame("{\"choices\":[{\"delta\":{\"content\":\"Hi\"}}]}"))

        assertEquals(HermesStreamFrame.Delta("Hi"), parsed)
    }

    @Test
    fun aReasoningDeltaIsASeparateReasoningFrame() {
        val parsed = HermesChatCompletions.parse(frame("{\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}"))

        assertEquals(HermesStreamFrame.Reasoning("thinking"), parsed)
    }

    @Test
    fun aToolProgressNamedEventIsNeverAssistantText() {
        val parsed =
            HermesChatCompletions.parse(
                frame(event = HermesChatCompletions.TOOL_PROGRESS_EVENT, data = "{\"tool\":\"terminal\",\"status\":\"running\"}"),
            )

        assertEquals(HermesStreamFrame.Ignored, parsed)
    }

    @Test
    fun theDoneSentinelIsTerminal() {
        assertEquals(HermesStreamFrame.Done, HermesChatCompletions.parse(frame("[DONE]")))
    }

    @Test
    fun aFinishReasonUsageAndModelAreReported() {
        val parsed =
            HermesChatCompletions.parse(
                frame(
                    "{\"model\":\"hermes-agent\",\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]," +
                        "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2,\"total_tokens\":3}}",
                ),
            )

        val reported = parsed as HermesStreamFrame.Reported
        assertEquals(ModelId("hermes-agent"), reported.model)
        assertEquals(3, reported.usage?.totalTokens)
        assertEquals("stop", reported.finishReason)
    }

    @Test
    fun aRoleOnlyChunkIsIgnored() {
        assertEquals(
            HermesStreamFrame.Ignored,
            HermesChatCompletions.parse(frame("{\"choices\":[{\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}")),
        )
    }

    @Test
    fun aMidStreamErrorObjectMapsToATypedFailure() {
        val parsed = HermesChatCompletions.parse(frame("{\"error\":{\"type\":\"rate_limit_exceeded\"}}"))

        assertEquals(ErrorCode.LLM_RATE_LIMITED, (parsed as HermesStreamFrame.Failed).error.code)
    }

    @Test
    fun anUnrecognizedErrorTypeFallsBackWithoutLeakingTheMessage() {
        val parsed = HermesChatCompletions.parse(frame("{\"error\":{\"type\":\"weird\",\"message\":\"secret prompt\"}}"))

        val error = (parsed as HermesStreamFrame.Failed).error
        assertEquals(ErrorCode.LLM_REQUEST_FAILED, error.code)
        assertFalse("the provider message must never reach the detail", error.detail!!.contains("secret prompt"))
    }

    @Test
    fun aNonJsonFrameIsMalformed() {
        val parsed = HermesChatCompletions.parse(frame("{not-json"))

        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (parsed as HermesStreamFrame.Failed).error.code)
    }

    @Test
    fun outcomeMappingDistinguishesAWholeAnswerFromATruncatedOne() {
        assertTrue(HermesChatCompletions.outcomeFor(null) is HermesCompletion.Completed)
        assertTrue(HermesChatCompletions.outcomeFor("stop") is HermesCompletion.Completed)

        val truncated = HermesChatCompletions.outcomeFor("length")
        assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (truncated as HermesCompletion.Failed).error.code)

        val filtered = HermesChatCompletions.outcomeFor("content_filter")
        assertEquals(ErrorCode.LLM_REQUEST_FAILED, (filtered as HermesCompletion.Failed).error.code)
    }

    @Test
    fun modelsListParsesTheAdvertisedAgentAlias() {
        val ids = HermesModels.parseModelIds(RecordedFixtures.text("llm/hermes/models_list.json"))

        assertEquals(listOf(ModelId("hermes-agent")), ids)
    }

    @Test
    fun theModelCatalogClaimsOnlyTheVerifiedStreamingCapability() {
        val catalog = HermesModelCatalog.fromModelsBody(RecordedFixtures.text("llm/hermes/models_list.json"))

        val caps = catalog.modelCapabilities(KnownProviders.HERMES, ModelId("hermes-agent"))
        assertEquals(true, caps?.streaming)
        assertEquals(false, caps?.usageReporting)
        assertTrue(caps!!.reasoningLevels.isEmpty())
        // An unknown model claims nothing.
        assertEquals(null, catalog.modelCapabilities(KnownProviders.HERMES, ModelId("nope")))
    }

    @Test
    fun capabilitiesParsesBearerAuthAndFeatures() {
        val caps = HermesCapabilities.parse(RecordedFixtures.text("llm/hermes/capabilities.json"))

        assertEquals("bearer", caps.authType)
        assertTrue(caps.authRequired)
        assertTrue(caps.chatCompletions)
        assertTrue(caps.responsesApi)
        assertTrue(caps.reasoningStreaming)
    }
}
