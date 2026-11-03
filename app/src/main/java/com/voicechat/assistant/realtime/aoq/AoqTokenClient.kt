package com.voicechat.assistant.realtime.aoq

import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.realtime.ApiEndpoints
import com.voicechat.assistant.realtime.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

data class AoqRelayEndpoint(
    val endpoint: String,
    val port: Int,
    val routeIndex: Int,
)

data class AoqCredentials(
    val token: String,
    val sid: String,
    val certFingerprint: String,
    val relayEndpoints: List<AoqRelayEndpoint>,
    val workspaceIdHash: String,
    val expiresInSeconds: Int,
)

/**
 * Implements the AppServer half of AOQ token authentication. On a production
 * deployment this call belongs on a server so the API key never reaches the
 * device; this private build performs it in-app because there is no backend.
 *
 * POST /api/v1/webrtc/realtime?model=… with `x-dashscope-rtc-transport: moq`
 * returns a short-lived client token, relay endpoints and a relay certificate
 * fingerprint. The API key is used only for this request.
 */
object AoqTokenClient {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val JSON_TYPE = "application/json".toMediaType()

    suspend fun allocate(
        httpClient: OkHttpClient,
        config: SessionConfig,
    ): Result<AoqCredentials> = withContext(Dispatchers.IO) {
        if (config.apiKey.isBlank()) {
            return@withContext Result.failure(
                IllegalStateException("AOQ requires an API key to allocate a token."),
            )
        }
        val gatewayUrl = ApiEndpoints.aoqBootstrapUrl(config.host, config.workspaceId, config.model)
        val externalEndpoint = config.aoqTokenUrl.trim()
        val url = externalEndpoint.ifBlank { gatewayUrl }
        AppLog.info(
            LogCategory.TRANSPORT,
            "Requesting AOQ token",
            "source=${if (externalEndpoint.isBlank()) "in-app AppServer step" else "AppServer"} url=$url",
        )
        val builder = Request.Builder()
            .url(url)
            .header("x-dashscope-rtc-transport", "moq")
            .post("{}".toRequestBody(JSON_TYPE))
        if (externalEndpoint.isBlank()) {
            // Production should always use an AppServer so the key stays off the
            // device. This private build performs that call locally.
            builder.header("Authorization", "Bearer ${config.apiKey}")
        }
        val request = builder.build()
        val result = runCatching {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw IOException(
                        "AOQ token allocation failed (HTTP ${response.code}): ${body.take(500)}",
                    )
                }
                parse(body, config.model)
            }
        }
        result.onFailure { error ->
            AppLog.error(LogCategory.TRANSPORT, "AOQ token allocation failed", error.message)
        }
        result.onSuccess { credentials ->
            AppLog.info(
                LogCategory.TRANSPORT,
                "AOQ token allocated",
                "sid=${credentials.sid} relays=${credentials.relayEndpoints.size} " +
                    "expires=${credentials.expiresInSeconds}s",
            )
        }
        result
    }

    private fun parse(body: String, model: String): AoqCredentials {        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: throw IOException("AOQ token response is not JSON: ${body.take(300)}")
        val token = root.string("aoqTokenForClient")
        val sid = root.string("sid")
        if (token.isNullOrBlank() || sid.isNullOrBlank()) {
            throw IOException(
                "AOQ is not available for model $model. Use WebSocket instead. " +
                    "Gateway response: ${body.take(300)}",
            )
        }
        val relays = root["clientRelayEndpoints"]?.jsonArray?.mapIndexedNotNull { index, element ->
            val item = element as? JsonObject ?: return@mapIndexedNotNull null
            val endpoint = item.string("endpoint").orEmpty()
            if (endpoint.isBlank()) return@mapIndexedNotNull null
            AoqRelayEndpoint(
                endpoint = endpoint,
                port = (item["port"]?.jsonPrimitive?.intOrNull) ?: 443,
                routeIndex = (item["route_index"]?.jsonPrimitive?.intOrNull) ?: index,
            )
        }.orEmpty()
        if (relays.isEmpty()) {
            throw IOException("AOQ token response has no relay endpoints: ${body.take(300)}")
        }
        val extra = root["extraInfo"] as? JsonObject
        return AoqCredentials(
            token = token,
            sid = sid,
            certFingerprint = root.string("clientRelayCertFingerprint").orEmpty(),
            relayEndpoints = relays,
            workspaceIdHash = extra?.string("workspaceIdHash").orEmpty(),
            expiresInSeconds = root["sidExpiresInSecs"]?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    private fun JsonObject.string(key: String): String? =
        (this[key]?.jsonPrimitive)?.contentOrNull
}
