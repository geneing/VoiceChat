package com.voicechat.assistant.realtime.tools

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal fun JsonObject.string(key: String): String =
    (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

internal fun JsonObject.obj(key: String): JsonObject? = get(key) as? JsonObject
internal fun JsonObject.array(key: String): List<JsonObject> =
    (get(key) as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

internal fun toolError(message: String): String = buildJsonObject {
    put("ok", false)
    put("error", message)
}.toString()

internal suspend fun OkHttpClient.toolResponse(request: Request): String =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val result = runCatching {
                    response.use {
                        if (!it.isSuccessful) throw IOException("Service returned HTTP ${it.code}.")
                        it.body?.string() ?: throw IOException("Service returned an empty response.")
                    }
                }
                continuation.resumeWith(result)
            }
        })
    }

internal suspend fun OkHttpClient.toolJson(request: Request): JsonObject =
    Json.parseToJsonElement(toolResponse(request)) as JsonObject
