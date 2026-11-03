package com.voicechat.assistant.realtime

import com.voicechat.assistant.data.model.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.util.Base64

/**
 * Tolerant parser for DashScope realtime server events. Unknown fields and
 * events are preserved rather than rejected: the vendor evolves the protocol
 * without notice.
 */
object RealtimeEventParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(raw: String): RealtimeEvent? {
        val element = runCatching { json.parseToJsonElement(raw) }.getOrNull()
        val obj = element as? JsonObject
            ?: return RealtimeEvent.Unknown("unparseable", raw.take(400))
        val type = obj.string("type") ?: return RealtimeEvent.Unknown("missing-type", raw.take(400))
        return when (type) {
            "session.created", "session.updated" -> {
                val session = obj["session"] as? JsonObject
                RealtimeEvent.SessionUpdated(session?.string("id"))
            }

            "input_audio_buffer.speech_started" ->
                RealtimeEvent.SpeechStarted(obj.string("item_id"))

            "input_audio_buffer.speech_stopped" ->
                RealtimeEvent.SpeechStopped(obj.string("item_id"))

            "conversation.item.input_audio_transcription.delta" -> {
                RealtimeEvent.UserTranscriptDelta(
                    text = obj.string("text").orEmpty(),
                    stash = obj.string("stash").orEmpty(),
                )
            }

            "conversation.item.input_audio_transcription.completed" -> {
                RealtimeEvent.UserTranscriptCompleted(
                    obj.string("transcript")
                        ?: obj.string("text")
                        ?: "",
                )
            }

            "response.created" -> {
                val response = obj["response"] as? JsonObject
                RealtimeEvent.ResponseCreated(response?.string("id") ?: obj.string("response_id"))
            }

            "response.done" -> {
                val response = obj["response"] as? JsonObject
                val usage = parseUsage((response ?: obj)["usage"] as? JsonObject)
                RealtimeEvent.ResponseDone(
                    responseId = response?.string("id"),
                    status = response?.string("status"),
                    usage = usage,
                )
            }

            "response.cancelled", "response.canceled" ->
                RealtimeEvent.ResponseCancelled((obj["response"] as? JsonObject)?.string("status"))

            "response.text.delta" -> RealtimeEvent.AssistantTextDelta(obj.string("delta").orEmpty())

            "response.text.done" -> RealtimeEvent.AssistantTextDone(
                obj.string("text") ?: obj.string("delta") ?: "",
            )

            "response.audio.delta" -> {
                val data = obj.string("delta")
                val bytes = data?.let {
                    runCatching { Base64.getDecoder().decode(it) }.getOrNull()
                }
                if (bytes == null) null else RealtimeEvent.AssistantAudioDelta(bytes)
            }

            "response.audio_transcript.delta" ->
                RealtimeEvent.AssistantTranscriptDelta(obj.string("delta").orEmpty())

            "response.audio_transcript.done" ->
                RealtimeEvent.AssistantTranscriptDone(obj.string("transcript") ?: "")

            "easy.input_audio_buffer.speech_started" -> RealtimeEvent.SpeechStarted(null)

            "response.output_item.done", "conversation.item.created" -> {
                val item = obj["item"] as? JsonObject
                val isCall = item?.string("type") == "function_call"
                val completed = item?.string("status") == "completed"
                val arguments = item?.string("arguments").orEmpty()
                if (isCall && completed && arguments.isNotBlank()) {
                    RealtimeEvent.FunctionCall(
                        callId = item.string("call_id").orEmpty(),
                        name = item.string("name").orEmpty(),
                        arguments = arguments,
                    )
                } else {
                    null
                }
            }

            "response.function_call_arguments.done" -> {
                val callId = obj.string("call_id").orEmpty()
                val name = obj.string("name").orEmpty()
                val arguments = obj.string("arguments").orEmpty()
                if (callId.isBlank() || name.isBlank()) {
                    null
                } else {
                    RealtimeEvent.FunctionCall(callId, name, arguments)
                }
            }

            "error" -> {
                val error = obj["error"] as? JsonObject
                RealtimeEvent.Error(
                    error?.string("message") ?: obj.string("message") ?: raw.take(400),
                )
            }

            else -> RealtimeEvent.Unknown(type, raw.take(400))
        }
    }

    private fun parseUsage(obj: JsonObject?): TokenUsage? {
        if (obj == null) return null
        val input = obj.int("input_tokens")
        val output = obj.int("output_tokens")
        val total = obj.int("total_tokens")
        if (input == null && output == null && total == null) return null
        return TokenUsage(
            inputTokens = input ?: 0,
            outputTokens = output ?: 0,
            totalTokens = total ?: ((input ?: 0) + (output ?: 0)),
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull

    fun pretty(raw: String): String = runCatching {
        json.encodeToString(JsonElement.serializer(), json.parseToJsonElement(raw))
    }.getOrDefault(raw)
}
