package com.voicechat.agent.remote

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

/**
 * Maps a transport exception to the contract's typed failures.
 *
 * A socket timeout is a `LLM_TIMEOUT`; any other I/O failure is a
 * `LLM_NETWORK_FAILED`; anything else is `LLM_REQUEST_FAILED`. The mapping is a
 * pure function so it is unit-tested without a socket, and it never puts an
 * exception message (which could echo a URL or body) into the error detail.
 */
internal object OkHttpFailureMapper {
    fun map(throwable: Throwable): VoiceAgentException =
        when (throwable) {
            is SocketTimeoutException, is InterruptedIOException -> {
                VoiceAgentException(VoiceAgentError(ErrorCode.LLM_TIMEOUT, "the connection timed out"))
            }

            is IOException -> {
                VoiceAgentException(VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED, "the network request failed"))
            }

            else -> {
                VoiceAgentException(VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "the transport request failed"))
            }
        }
}

/**
 * The OkHttp-backed [HttpStreamingEngine].
 *
 * This is the single file in the app that imports OkHttp, so the HTTP client
 * type stays inside the transport (`RemoteSourcePurityTest`). Behavior:
 *
 * - **Streaming.** `Call.execute()` runs on `Dispatchers.IO` and body bytes are
 *   emitted as they arrive, so time-to-first-token is preserved; the channel
 *   backpressures a slow consumer.
 * - **Cancellation.** `invokeOnClose` cancels the in-flight OkHttp call, which
 *   closes the socket and unblocks the reader. A cancellation is rethrown as
 *   `CancellationException` and never mapped to a typed failure.
 * - **Timeouts.** `connectTimeout` bounds connection setup, `readTimeout`
 *   bounds the gap between body bytes, and `callTimeout` is disabled so a long
 *   stream is not cut off. `retryOnConnectionFailure` is off: retries are the
 *   caller's decision, based on the typed `RATE_LIMITED`/`UNAVAILABLE` reason.
 * - **No redirects.** `followRedirects`/`followSslRedirects` are off, so a 3xx
 *   is returned as-is and a request can never be silently redirected to an
 *   unintended host (risk R-0074). A caller sees the 3xx status and maps it to a
 *   typed failure instead of following it.
 * - **Privacy.** Nothing is logged here; the caller's adapter records only
 *   identities and counts.
 */
class OkHttpStreamingEngine(
    private val client: OkHttpClient = defaultClient(),
) : HttpStreamingEngine {
    override fun execute(request: RemoteHttpRequest): Flow<HttpStreamEvent> =
        channelFlow {
            val call = client.newCall(request.toOkHttp())
            var cancelledByClose = false
            invokeOnClose {
                cancelledByClose = true
                call.cancel()
            }
            try {
                call.execute().use { response ->
                    send(HttpStreamEvent.Head(status = response.code, headers = response.headers.toMultimap()))
                    val source = response.body.source()
                    val buffer = ByteArray(READ_BUFFER_BYTES)
                    while (isActive) {
                        val read = source.read(buffer, 0, buffer.size)
                        if (read == -1) break
                        if (read == 0) continue
                        send(HttpStreamEvent.Chunk(buffer.copyOf(read)))
                    }
                }
            } catch (cancellation: CancellationException) {
                call.cancel()
                throw cancellation
            } catch (throwable: Throwable) {
                call.cancel()
                // A cancelled collection aborts the blocking read with an IOException;
                // report that as cancellation, not as a network failure.
                if (cancelledByClose || !isActive) {
                    throw CancellationException("the transport call was cancelled", throwable)
                }
                throw OkHttpFailureMapper.map(throwable)
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun RemoteHttpRequest.toOkHttp(): Request {
        val builder = Request.Builder().url(url)
        val requestBody = body?.toRequestBody(JSON_MEDIA_TYPE)
        builder.method(method, requestBody)
        headers.forEach { builder.addHeader(it.name, it.value) }
        return builder.build()
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val READ_BUFFER_BYTES = 8 * 1024
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
        const val WRITE_TIMEOUT_SECONDS = 15L

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                // A stream may legitimately run for minutes; the read timeout covers idle gaps.
                .callTimeout(0, TimeUnit.MILLISECONDS)
                // No implicit retry: a streamed response must not be silently replayed.
                .retryOnConnectionFailure(false)
                // No redirects (R-0074): a 3xx is surfaced as its own status, so a
                // request cannot be silently redirected to an unintended host.
                .followRedirects(false)
                .followSslRedirects(false)
                .build()
    }
}
