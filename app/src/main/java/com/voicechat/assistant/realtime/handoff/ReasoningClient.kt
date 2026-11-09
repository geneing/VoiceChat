package com.voicechat.assistant.realtime.handoff

import com.voicechat.assistant.BuildConfig
import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.model.TokenUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

/** Failure of a handoff. `cancelled` failures are expected when the user barges in. */
class HandoffException(
    message: String,
    val cancelled: Boolean = false,
) : Exception(message)

data class ReasoningResult(
    val model: String,
    val answer: String,
    val summary: String,
    val reasoning: String,
    val usage: TokenUsage,
    val reasoningTokens: Int,
    val elapsedMs: Long,
)

/**
 * Streams a completion from the higher-reasoning text model over the
 * OpenAI-compatible endpoint. The realtime session is only ever the *client* of
 * this service, so no OkHttp type escapes into the session state machine.
 */
class ReasoningClient(
    private val client: OkHttpClient,
    private val endpoint: String = BuildConfig.QWEN_OPENAI_BASE,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val activeCalls = ConcurrentHashMap.newKeySet<Call>()

    val configured: Boolean get() = endpoint.isNotBlank()

    /** Aborts every in-flight request; the awaiting suspensions then fail fast. */
    fun cancelAll() {
        activeCalls.toList().forEach { it.cancel() }
        activeCalls.clear()
    }

    /** Wire request for one handoff, exposed so a test can lock the auth header. */
    fun request(
        apiKey: String,
        config: HandoffConfig,
        messages: List<HandoffPrompt.Turn>,
    ): Request = Request.Builder()
        .url(completionsUrl())
        .header("Authorization", "Bearer $apiKey")
        .post(
            HandoffPrompt.requestBody(config, messages)
                .toRequestBody("application/json; charset=utf-8".toMediaType()),
        )
        .build()

    private fun completionsUrl(): String = "${endpoint.trimEnd('/')}/chat/completions"

    suspend fun answer(
        apiKey: String,
        config: HandoffConfig,
        transcript: List<HandoffPrompt.Turn>,
        task: String,
        depth: String = "detailed",
        onProgress: (Int) -> Unit = {},
    ): Result<ReasoningResult> = withContext(Dispatchers.IO) {
        if (!configured) {
            return@withContext Result.failure(
                HandoffException("Deep reasoning endpoint is not configured."),
            )
        }
        if (apiKey.isBlank()) {
            return@withContext Result.failure(HandoffException("API key is not configured."))
        }
        val messages = HandoffPrompt.messages(transcript, task, config.contextTurns, depth)
        val request = request(apiKey, config, messages)
        val timeoutClient = client.newBuilder()
            .readTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .callTimeout(config.timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val call = timeoutClient.newCall(request)
        activeCalls.add(call)
        // A barge-in cancels this coroutine; that must abort the HTTP call too
        // instead of leaving it streaming into a result nobody reads.
        val onCancel = coroutineContext.job.invokeOnCompletion { call.cancel() }
        val startedAt = System.nanoTime()
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val detail = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    AppLog.warn(
                        LogCategory.HANDOFF,
                        "Reasoning request rejected",
                        "http=${response.code} ${errorMessage(detail)}",
                    )
                    return@withContext Result.failure(
                        HandoffException(httpMessage(response.code, errorMessage(detail))),
                    )
                }
                val source = response.body?.source()
                    ?: return@withContext Result.failure(
                        HandoffException("Deep reasoning returned no response."),
                    )
                coroutineContext.ensureActive()
                parseStream(source, config.resolvedModel, startedAt, onProgress)
            }
        } catch (error: IOException) {
            if (call.isCanceled() || !coroutineContext.isActive) {
                Result.failure(HandoffException("Deep reasoning was cancelled.", cancelled = true))
            } else {
                AppLog.warn(LogCategory.HANDOFF, "Reasoning request failed", error.message)
                Result.failure(HandoffException(timeoutOrNetworkMessage(error, config.timeoutMs)))
            }
        } finally {
            onCancel.dispose()
            activeCalls.remove(call)
        }
    }

    private fun parseStream(
        source: BufferedSource,
        model: String,
        startedAt: Long,
        onProgress: (Int) -> Unit,
    ): Result<ReasoningResult> {
        val answer = StringBuilder()
        val reasoning = StringBuilder()
        var inputTokens = 0
        var outputTokens = 0
        var totalTokens = 0
        var reasoningTokens = 0
        var reportedAt = 0

        while (true) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty()) continue
            if (payload == "[DONE]") break

            val chunk = runCatching { json.parseToJsonElement(payload).jsonObject }.getOrNull()
                ?: continue
            chunk["error"]?.takeIf { it !is JsonNull }?.let { element ->
                val message = element.jsonObject["message"]?.jsonPrimitive?.contentOrNull
                return Result.failure(
                    HandoffException(message ?: "Deep reasoning returned an error."),
                )
            }
            chunk["usage"]?.takeIf { it !is JsonNull }?.let { element ->
                runCatching {
                    val usage = element.jsonObject
                    inputTokens = usage["prompt_tokens"]?.jsonPrimitive?.intOrNull ?: inputTokens
                    outputTokens = usage["completion_tokens"]?.jsonPrimitive?.intOrNull ?: outputTokens
                    totalTokens = usage["total_tokens"]?.jsonPrimitive?.intOrNull ?: totalTokens
                    reasoningTokens = usage["completion_tokens_details"]
                        ?.jsonObject?.get("reasoning_tokens")
                        ?.jsonPrimitive?.intOrNull ?: reasoningTokens
                }
            }
            val delta = chunk["choices"]
                ?.takeIf { it !is JsonNull }
                ?.jsonArray
                ?.firstOrNull()
                ?.jsonObject
                ?.get("delta")
                ?.takeIf { it !is JsonNull }
                ?.jsonObject
                ?: continue
            delta["reasoning_content"]?.jsonPrimitive?.contentOrNull?.let { reasoning.append(it) }
            delta["content"]?.jsonPrimitive?.contentOrNull?.let { answer.append(it) }
            if (reasoning.length - reportedAt >= 240) {
                reportedAt = reasoning.length
                onProgress(reasoning.length)
            }
        }

        val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
        onProgress(reasoning.length)
        val sections = HandoffPrompt.parseSections(answer.toString())
        if (sections.answer.isBlank()) {
            return Result.failure(
                HandoffException("Deep reasoning returned an empty answer after ${elapsedMs}ms."),
            )
        }
        if (totalTokens <= 0) totalTokens = inputTokens + outputTokens
        val usage = TokenUsage(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = totalTokens,
        )
        AppLog.info(
            LogCategory.HANDOFF,
            "Reasoning answer ready",
            "model=$model ${elapsedMs}ms tokens=$totalTokens reasoning=$reasoningTokens",
        )
        return Result.success(
            ReasoningResult(
                model = model,
                answer = sections.answer,
                summary = sections.summary.ifBlank { HandoffPrompt.fallbackSummary(sections.answer) },
                reasoning = reasoning.toString().trim(),
                usage = usage,
                reasoningTokens = reasoningTokens,
                elapsedMs = elapsedMs,
            ),
        )
    }

    private fun errorMessage(body: String): String {
        if (body.isBlank()) return "no details"
        val parsed = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
        val message = parsed?.get("error")?.let { element ->
            runCatching { element.jsonObject["message"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        return (message ?: body).take(200)
    }

    private fun httpMessage(code: Int, detail: String): String = when (code) {
        401, 403 -> "Deep reasoning key was rejected."
        404 -> "The reasoning model is not available on this workspace."
        429 -> "Deep reasoning is rate limited right now."
        in 500..599 -> "Deep reasoning service is unavailable."
        else -> "Deep reasoning failed (HTTP $code): $detail"
    }

    private fun timeoutOrNetworkMessage(error: IOException, timeoutMs: Int): String {
        val text = error.message.orEmpty()
        return if (text.contains("timeout", ignoreCase = true)) {
            "Deep reasoning timed out after ${timeoutMs / 1000}s."
        } else {
            "Deep reasoning could not be reached."
        }
    }
}
