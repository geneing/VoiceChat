package com.voicechat.agent.remote

import com.voicechat.agent.diagnostics.Redaction
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayOutputStream

/** One HTTP header. Header values are treated as sensitive: never logged. */
data class RemoteHttpHeader(
    val name: String,
    val value: String,
)

/**
 * One HTTP request the transport sends.
 *
 * The [body] is the encoded provider payload and the [headers] may carry a
 * bearer credential. [toString] redacts every header value through the M04
 * [Redaction] helper and reports the body as a length only, so an accidental
 * `"sending $request"` log or crash message cannot leak a key or prompt
 * (`RemoteTransportRedactionTest`).
 */
data class RemoteHttpRequest(
    val method: String,
    val url: String,
    val headers: List<RemoteHttpHeader> = emptyList(),
    val body: String? = null,
) {
    override fun toString(): String {
        val redactedHeaders = Redaction.redactHeaders(headers.associate { it.name to it.value })
        val bodyState = body?.let { "present(${it.length} chars)" } ?: "none"
        return "RemoteHttpRequest(method=$method, url=$url, headers=$redactedHeaders, body=$bodyState)"
    }
}

/** One event in an HTTP exchange: the response head, then zero or more body chunks. */
sealed interface HttpStreamEvent {
    /** The response status and headers, delivered before any body bytes. */
    data class Head(
        val status: Int,
        val headers: Map<String, List<String>> = emptyMap(),
    ) : HttpStreamEvent

    /** A slice of the response body, in arrival order. */
    data class Chunk(
        val bytes: ByteArray,
    ) : HttpStreamEvent
}

/**
 * The HTTP engine seam.
 *
 * Keeping the client (OkHttp) behind this interface is what makes the transport
 * testable with recorded bytes and no network: a fixture engine emits
 * [HttpStreamEvent]s, and the same [RemoteTransport] logic runs. An
 * implementation must map its transport failures to typed
 * `VoiceAgentException`s (network, timeout) and must not log a credential,
 * prompt, or response body.
 */
interface HttpStreamingEngine {
    /** Executes [request]; the returned flow emits the head then body chunks. */
    fun execute(request: RemoteHttpRequest): Flow<HttpStreamEvent>

    /** Releases the engine's resources. Idempotent. */
    suspend fun close()
}

/** A buffered (non-streaming) response, bounded by the transport. */
data class RemoteHttpResponse(
    val status: Int,
    val headers: Map<String, List<String>>,
    val body: String,
    val truncated: Boolean,
)

/**
 * Maps an HTTP status to a typed language-model error.
 *
 * HTTP statuses are the one failure signal every provider shares, so the mapping
 * lives in the shared transport rather than in each adapter. A provider-specific
 * error body is deliberately **not** inspected and never becomes [VoiceAgentError.detail].
 */
object RemoteStatusMapper {
    /** The typed error for a non-success [status]. */
    fun errorFor(status: Int): VoiceAgentError =
        when {
            status == 401 || status == 403 -> {
                VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED, "the provider rejected the credential (HTTP $status)")
            }

            status == 408 || status == 504 -> {
                VoiceAgentError(ErrorCode.LLM_TIMEOUT, "the provider timed out (HTTP $status)")
            }

            status == 429 -> {
                VoiceAgentError(ErrorCode.LLM_RATE_LIMITED, "the provider rate-limited the request (HTTP 429)")
            }

            status in 400..499 -> {
                VoiceAgentError(ErrorCode.LLM_INVALID_REQUEST, "the provider rejected the request (HTTP $status)")
            }

            status in 500..599 -> {
                VoiceAgentError(ErrorCode.LLM_UNAVAILABLE, "the provider was unavailable (HTTP $status)")
            }

            else -> {
                VoiceAgentError(ErrorCode.LLM_REQUEST_FAILED, "unexpected provider status (HTTP $status)")
            }
        }
}

/**
 * The shared remote transport every provider adapter (M14-M19) builds on.
 *
 * It turns an [HttpStreamingEngine] into a stream of [SseFrame]s and maps HTTP
 * failures to typed errors. It is deliberately small and provider-free:
 *
 * - **Cancellation.** Cancelling collection of [streamSse] cancels the engine's
 *   in-flight call (the OkHttp engine closes the socket via `invokeOnClose`);
 *   the cancellation is never converted into a typed failure.
 * - **Timeouts.** The engine owns socket timeouts and maps them to
 *   `LLM_TIMEOUT`; the transport adds no hidden retry.
 * - **Bounded.** [fetch] caps the buffered body and reports truncation; the SSE
 *   decoder caps each frame.
 * - **Typed errors.** A non-2xx status becomes a typed `VoiceAgentError` via
 *   [RemoteStatusMapper]; the response body is drained but never surfaced.
 * - **Privacy.** No method logs a request, header, credential, prompt, or body.
 */
class RemoteTransport(
    private val engine: HttpStreamingEngine,
    /**
     * Maximum provider calls in flight at once (M26, R-0094). Each blocking
     * engine call otherwise consumes an IO thread; a bounded semaphore makes
     * rapid cancellation, recreation, or provider errors queue instead of
     * growing unbounded. Extra callers suspend until a permit frees — the
     * backpressure is applied to the caller's coroutine, not to a thread.
     */
    maxConcurrentRequests: Int = DEFAULT_MAX_CONCURRENT_REQUESTS,
) {
    init {
        require(maxConcurrentRequests > 0) { "maxConcurrentRequests must be positive, was $maxConcurrentRequests" }
    }

    private val permits = Semaphore(maxConcurrentRequests)

    /**
     * Executes [request] and decodes a Server-Sent Events stream.
     *
     * The flow emits one [SseFrame] per provider event and completes at the end
     * of the body. A non-2xx status, an engine transport failure, or an
     * over-large frame throws a typed [VoiceAgentException]; cancellation
     * propagates as `CancellationException`.
     */
    fun streamSse(request: RemoteHttpRequest): Flow<SseFrame> =
        flow {
            permits.withPermit {
                rawSse(request).collect { emit(it) }
            }
        }

    /**
     * Executes [request] and decodes a Server-Sent Events stream without the
     * concurrency bound; [streamSse] calls this while holding one permit.
     */
    private fun rawSse(request: RemoteHttpRequest): Flow<SseFrame> =
        flow {
            var status: Int? = null
            val bodyChunks =
                flow {
                    engine.execute(request).collect { event ->
                        when (event) {
                            is HttpStreamEvent.Head -> {
                                status = event.status
                            }

                            is HttpStreamEvent.Chunk -> {
                                if (status in SUCCESS_RANGE && event.bytes.isNotEmpty()) {
                                    emit(event.bytes)
                                }
                            }
                        }
                    }
                    val resolved =
                        status ?: throw VoiceAgentException(
                            VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the transport returned no HTTP status"),
                        )
                    if (resolved !in SUCCESS_RANGE) throw VoiceAgentException(RemoteStatusMapper.errorFor(resolved))
                }
            SseDecoder.decode(bodyChunks).collect { emit(it) }
        }

    /**
     * Executes [request] and buffers up to [maxBytes] of the response body.
     *
     * Unlike [streamSse], this returns the status even on a failure response, so
     * an adapter can distinguish a 401 from a 500. The body is capped: when the
     * response is larger, [RemoteHttpResponse.truncated] is true and the extra
     * bytes are discarded rather than read into memory.
     */
    suspend fun fetch(
        request: RemoteHttpRequest,
        maxBytes: Int = DEFAULT_MAX_RESPONSE_BYTES,
    ): RemoteHttpResponse = permits.withPermit { fetchPermitted(request, maxBytes) }

    private suspend fun fetchPermitted(
        request: RemoteHttpRequest,
        maxBytes: Int,
    ): RemoteHttpResponse {
        var status: Int? = null
        var headers: Map<String, List<String>> = emptyMap()
        val out = ByteArrayOutputStream()
        var truncated = false
        engine.execute(request).collect { event ->
            when (event) {
                is HttpStreamEvent.Head -> {
                    status = event.status
                    headers = event.headers
                }

                is HttpStreamEvent.Chunk -> {
                    val remaining = maxBytes - out.size()
                    if (event.bytes.size <= remaining) {
                        out.write(event.bytes)
                    } else {
                        if (remaining > 0) out.write(event.bytes, 0, remaining)
                        truncated = true
                    }
                }
            }
        }
        val resolved =
            status ?: throw VoiceAgentException(
                VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, "the transport returned no HTTP status"),
            )
        return RemoteHttpResponse(
            status = resolved,
            headers = headers,
            body = out.toString(Charsets.UTF_8.name()),
            truncated = truncated,
        )
    }

    /** Releases the underlying engine. Idempotent. */
    suspend fun close() = engine.close()

    companion object {
        /** Default cap for one buffered [fetch] body. */
        const val DEFAULT_MAX_RESPONSE_BYTES: Int = 1024 * 1024

        /**
         * Default in-flight provider-call limit. Four is enough for a single
         * user's conversation plus settings probes; a larger burst queues rather
         * than exhausting the IO pool. The value is a deliberate policy, not a
         * measured optimum (R-0094).
         */
        const val DEFAULT_MAX_CONCURRENT_REQUESTS: Int = 4

        private val SUCCESS_RANGE = 200..299
    }
}
