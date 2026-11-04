package com.voicechat.assistant.realtime

import com.voicechat.assistant.data.model.TransportKind
import com.voicechat.assistant.realtime.tools.ToolDefinitions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class RealtimeEventParserTest {

    @Test
    fun `parses session updated`() {
        val event = RealtimeEventParser.parse(
            """{"type":"session.updated","session":{"id":"sess_1"}}""",
        )
        assertEquals(RealtimeEvent.SessionUpdated("sess_1"), event)
    }

    @Test
    fun `parses transcript delta with stash`() {
        val event = RealtimeEventParser.parse(
            """{"type":"conversation.item.input_audio_transcription.delta","text":"hel","stash":"lo"}""",
        )
        assertEquals(RealtimeEvent.UserTranscriptDelta("hel", "lo"), event)
    }

    @Test
    fun `parses response done usage`() {
        val event = RealtimeEventParser.parse(
            """
            {"type":"response.done","response":{"id":"resp_1","status":"completed",
             "usage":{"input_tokens":336,"output_tokens":41,"total_tokens":377}}}
            """.trimIndent(),
        )
        val done = event as RealtimeEvent.ResponseDone
        assertEquals("resp_1", done.responseId)
        assertEquals("completed", done.status)
        assertEquals(336, done.usage?.inputTokens)
        assertEquals(41, done.usage?.outputTokens)
        assertEquals(377, done.usage?.totalTokens)
    }

    @Test
    fun `parses function call from output item`() {
        val event = RealtimeEventParser.parse(
            """
            {"type":"response.output_item.done","item":{"type":"function_call",
             "status":"completed","call_id":"call_1","name":"web_search",
             "arguments":"{\"query\":\"news\"}"}}
            """.trimIndent(),
        )
        val call = event as RealtimeEvent.FunctionCall
        assertEquals("call_1", call.callId)
        assertEquals("web_search", call.name)
        assertTrue(call.arguments.contains("news"))
    }

    @Test
    fun `in-progress function calls are ignored`() {
        val event = RealtimeEventParser.parse(
            """
            {"type":"conversation.item.created","item":{"type":"function_call",
             "status":"in_progress","call_id":"call_1","name":"web_search","arguments":""}}
            """.trimIndent(),
        )
        assertEquals(null, event)
    }

    @Test
    fun `function call arguments done carries the payload`() {
        val event = RealtimeEventParser.parse(
            """
            {"type":"response.function_call_arguments.done","call_id":"call_2",
             "name":"web_search","arguments":"{\"query\":\"weather\"}"}
            """.trimIndent(),
        )
        val call = event as RealtimeEvent.FunctionCall
        assertEquals("call_2", call.callId)
        assertEquals("web_search", call.name)
        assertTrue(call.arguments.contains("weather"))
    }

    @Test
    fun `decodes audio delta`() {
        val payload = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))
        val event = RealtimeEventParser.parse(
            """{"type":"response.audio.delta","delta":"$payload"}""",
        )
        val audio = event as RealtimeEvent.AssistantAudioDelta
        assertEquals(4, audio.audio.size)
        assertEquals(3, audio.audio[2].toInt())
    }

    @Test
    fun `unknown events are preserved`() {
        val event = RealtimeEventParser.parse("""{"type":"something.new","value":1}""")
        val unknown = event as RealtimeEvent.Unknown
        assertEquals("something.new", unknown.type)
    }

    @Test
    fun `malformed payload does not throw`() {
        val event = RealtimeEventParser.parse("not json at all")
        assertTrue(event is RealtimeEvent.Unknown)
    }

    @Test
    fun `server error is surfaced`() {
        val event = RealtimeEventParser.parse(
            """{"type":"error","error":{"message":"invalid api key"}}""",
        )
        assertEquals(RealtimeEvent.Error("invalid api key"), event)
    }
}

class SessionPayloadsTest {

    private val config = SessionConfig(
        model = "qwen3.8-omni-flash-realtime",
        voice = "Tina",
        instructions = "Be helpful.",
        transport = TransportKind.WEBSOCKET,
        apiKey = "test",
        host = "ws-test.example.com",
        workspaceId = "ws-test",
        turnDetection = "semantic_vad",
        vadThreshold = 0.4f,
        silenceMs = 650,
        inputTranscription = true,
        toolsJson = """[{"type":"function","function":{"name":"web_search"}}]""",
        nativeSearch = false,
        searchSources = true,
        priceInputPerMillion = 1.0,
        priceOutputPerMillion = 2.0,
    )

    @Test
    fun `session update carries audio formats and vad`() {
        val json = SessionPayloads.sessionUpdate(config)
        assertTrue(json.contains("\"type\":\"session.update\""))
        assertTrue(json.contains("\"sample_rate\":16000"))
        assertTrue(json.contains("\"sample_rate\":24000"))
        assertTrue(json.contains("\"type\":\"semantic_vad\""))
        assertTrue(json.contains("\"silence_duration_ms\":650"))
        assertTrue(json.contains("\"prefix_padding_ms\":800"))
        assertTrue(json.contains("\"tools\""))
        assertTrue(!json.contains("enable_search"))
    }

    @Test
    fun `native search ships without the client web search tool`() {
        val json = SessionPayloads.sessionUpdate(
            config.copy(
                toolsJson = ToolDefinitions.json(includeWebSearch = false),
                nativeSearch = true,
            ),
        )
        assertTrue(json.contains("\"enable_search\":true"))
        assertTrue(json.contains("get_current_datetime"))
        assertTrue(json.contains("get_location"))
        assertTrue(!json.contains("web_search"))
    }

    @Test
    fun `client web search is offered when native search is off`() {
        val json = SessionPayloads.sessionUpdate(
            config.copy(toolsJson = ToolDefinitions.json(includeWebSearch = true)),
        )
        assertTrue(json.contains("web_search"))
        assertTrue(!json.contains("enable_search"))
    }

    @Test
    fun `smart turn and speech rate are sent for audio model`() {
        val json = SessionPayloads.sessionUpdate(
            config.copy(
                model = "qwen-audio-3.1-realtime-flash",
                turnDetection = "smart_turn_v2",
                speechRate = 1.25,
            ),
        )
        assertTrue(json.contains("\"type\":\"smart_turn_v2\""))
        assertTrue(json.contains("\"speech_rate\":1.25"))
        assertTrue(!json.contains("\"silence_duration_ms\""))
        assertTrue(!json.contains("\"prefix_padding_ms\""))
        assertTrue(json.contains("slightly faster"))
    }

    @Test
    fun `text turn asks for text modality only`() {
        val events = SessionPayloads.textTurn("hello")
        assertEquals(2, events.size)
        assertTrue(events[0].contains("\"input_text\""))
        assertTrue(events[1].contains("\"modalities\":[\"text\"]"))
        assertTrue(!events[1].contains("audio"))
    }

    @Test
    fun `image turn sends silence, image, commit, text then response`() {
        val events = SessionPayloads.textTurn("look", listOf("AAAA"))
        assertEquals(5, events.size)
        assertTrue(events[0].contains("input_audio_buffer.append"))
        assertTrue(events[1].contains("input_image_buffer.append"))
        assertTrue(events[2].contains("input_audio_buffer.commit"))
        assertTrue(events[3].contains("conversation.item.create"))
        assertTrue(events[4].contains("\"modalities\":[\"text\"]"))
    }
}

class ApiEndpointsTest {

    @Test
    fun `workspace is prefixed to the host`() {
        assertEquals(
            "ws-abc.ap-southeast-1.maas.aliyuncs.com",
            ApiEndpoints.host("ap-southeast-1.maas.aliyuncs.com", "ws-abc"),
        )
    }

    @Test
    fun `workspace is not prefixed twice`() {
        assertEquals(
            "ws-abc.ap-southeast-1.maas.aliyuncs.com",
            ApiEndpoints.host("ws-abc.ap-southeast-1.maas.aliyuncs.com", "ws-abc"),
        )
    }

    @Test
    fun `scheme and path are stripped from the host`() {
        assertEquals(
            "ws-abc.example.com",
            ApiEndpoints.host("https://ws-abc.example.com/api", "ws-abc"),
        )
    }

    @Test
    fun `websocket url includes model`() {
        assertEquals(
            "wss://ws-abc.example.com/api-ws/v1/realtime?model=qwen3.8-omni-flash-realtime",
            ApiEndpoints.webSocketUrl("example.com", "ws-abc", "qwen3.8-omni-flash-realtime"),
        )
    }

    @Test
    fun `webrtc signalling url`() {
        assertEquals(
            "https://ws-abc.example.com/api/v1/webrtc/realtime?model=qwen-audio-3.1-realtime-flash",
            ApiEndpoints.webRtcSignalingUrl(
                "example.com",
                "ws-abc",
                "qwen-audio-3.1-realtime-flash",
            ),
        )
    }
}
