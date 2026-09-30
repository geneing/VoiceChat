package com.voicechat.agent.remote

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A minimal, library-free JSON tree.
 *
 * The transport and provider adapters parse and build provider payloads with
 * [RemoteJson] and navigate [JsonNode] values, so **no kotlinx-serialization
 * type ever leaves this package** (`RemoteSourcePurityTest`). This is the whole
 * point of the wrapper: a provider adapter stays in this project's vocabulary
 * and the app-facing contract never sees a JSON library type.
 */
sealed interface JsonNode {
    /** A JSON object; field order is preserved by the linked maps the parser builds. */
    data class Obj(
        val fields: Map<String, JsonNode>,
    ) : JsonNode

    /** A JSON array. */
    data class Arr(
        val items: List<JsonNode>,
    ) : JsonNode

    /** A JSON string. */
    data class Str(
        val value: String,
    ) : JsonNode

    /** A JSON number. Integral values stringify without a decimal point. */
    data class Num(
        val value: Double,
    ) : JsonNode

    /** A JSON boolean. */
    data class Bool(
        val value: Boolean,
    ) : JsonNode

    /** JSON `null`. */
    data object Null : JsonNode
}

/** The field [name] of an object node, or `null` when absent. */
operator fun JsonNode.get(name: String): JsonNode? = (this as? JsonNode.Obj)?.fields?.get(name)

/** The string value of the field [name], or `null` when absent or not a string. */
fun JsonNode.stringField(name: String): String? = (get(name) as? JsonNode.Str)?.value

/** The integral value of the field [name], or `null` when absent, non-numeric, or non-finite. */
fun JsonNode.intField(name: String): Int? = (get(name) as? JsonNode.Num)?.value?.takeIf { it.isFinite() }?.toInt()

/** The object value of the field [name], or `null`. */
fun JsonNode.objField(name: String): JsonNode.Obj? = get(name) as? JsonNode.Obj

/** The array value of the field [name], or `null`. */
fun JsonNode.arrField(name: String): JsonNode.Arr? = get(name) as? JsonNode.Arr

/**
 * Parses and renders JSON for the transport without leaking the library type.
 *
 * Parsing a provider frame that is not valid JSON raises a typed
 * `LLM_MALFORMED_RESPONSE` `VoiceAgentException` rather than a library
 * exception, so an adapter maps it to the same failure it would any other
 * malformed input.
 */
object RemoteJson {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    /** Parses [text] into a [JsonNode] tree. */
    fun parse(text: String): JsonNode =
        try {
            json.parseToJsonElement(text).toNode()
        } catch (_: SerializationException) {
            throw malformed("a provider frame was not valid JSON")
        } catch (_: IllegalArgumentException) {
            throw malformed("a provider frame was not valid JSON")
        }

    /** Renders [node] as compact JSON. */
    fun stringify(node: JsonNode): String = json.encodeToString(JsonElement.serializer(), node.toElement())

    /** Builds an object node from ordered [fields]. */
    fun obj(vararg fields: Pair<String, JsonNode>): JsonNode.Obj = JsonNode.Obj(linkedMapOf(*fields))

    /** Builds an object node from [fields], preserving iteration order. */
    fun obj(fields: Map<String, JsonNode>): JsonNode.Obj = JsonNode.Obj(LinkedHashMap(fields))

    /** Builds an array node. */
    fun arr(items: List<JsonNode>): JsonNode.Arr = JsonNode.Arr(items)

    /** Builds a string node. */
    fun str(value: String): JsonNode.Str = JsonNode.Str(value)

    /** Builds a numeric node. */
    fun num(value: Double): JsonNode.Num = JsonNode.Num(value)

    /** Builds an integer numeric node. */
    fun int(value: Int): JsonNode.Num = JsonNode.Num(value.toDouble())

    /** Builds a boolean node. */
    fun bool(value: Boolean): JsonNode.Bool = JsonNode.Bool(value)

    private fun malformed(detail: String) = VoiceAgentException(VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, detail))

    private fun JsonElement.toNode(): JsonNode =
        when (this) {
            is JsonNull -> {
                JsonNode.Null
            }

            is JsonObject -> {
                JsonNode.Obj(LinkedHashMap(mapValues { it.value.toNode() }))
            }

            is JsonArray -> {
                JsonNode.Arr(map { it.toNode() })
            }

            is JsonPrimitive -> {
                when {
                    isString -> JsonNode.Str(content)
                    booleanOrNull != null -> JsonNode.Bool(booleanOrNull!!)
                    doubleOrNull != null -> JsonNode.Num(doubleOrNull!!)
                    else -> JsonNode.Str(content)
                }
            }
        }

    private fun JsonNode.toElement(): JsonElement =
        when (this) {
            is JsonNode.Obj -> {
                JsonObject(LinkedHashMap(fields.mapValues { it.value.toElement() }))
            }

            is JsonNode.Arr -> {
                JsonArray(items.map { it.toElement() })
            }

            is JsonNode.Str -> {
                JsonPrimitive(value)
            }

            is JsonNode.Num -> {
                if (value.isFinite() && value == Math.floor(value)) {
                    JsonPrimitive(value.toLong())
                } else {
                    JsonPrimitive(value)
                }
            }

            is JsonNode.Bool -> {
                JsonPrimitive(value)
            }

            JsonNode.Null -> {
                JsonNull
            }
        }
}
