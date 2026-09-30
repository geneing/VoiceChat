package com.voicechat.agent.remote

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shared fixture harness for the remote transport.
 *
 * M15-M19 reuse this instead of re-inventing a fake HTTP layer: a provider test
 * scripts the `Head` and body bytes (or a recorded SSE fixture), and the real
 * [RemoteTransport] and [SseDecoder] run. There is **no socket, clock, DNS, or
 * credential** anywhere here, so every provider fixture is deterministic.
 */
class FakeHttpStreamingEngine(
    private val handler: (RemoteHttpRequest) -> Flow<HttpStreamEvent>,
) : HttpStreamingEngine {
    /** Every request the transport sent, in order. */
    val requests: MutableList<RemoteHttpRequest> = mutableListOf()

    /** True once [close] was called. */
    val closed: AtomicBoolean = AtomicBoolean(false)

    /** True when a scripted stream observed cancellation. */
    val streamCancelled: AtomicBoolean = AtomicBoolean(false)

    override fun execute(request: RemoteHttpRequest): Flow<HttpStreamEvent> {
        requests += request
        return handler(request)
    }

    override suspend fun close() {
        closed.set(true)
    }
}

/**
 * A response of [status] whose body is [body], optionally split into
 * [chunkSize]-byte pieces so a test can prove the decoder reassembles frames
 * across arbitrary chunk boundaries.
 */
fun scriptedResponse(
    status: Int = 200,
    body: String = "",
    chunkSize: Int = body.length.coerceAtLeast(1),
    headers: Map<String, List<String>> = mapOf("content-type" to listOf("text/event-stream")),
): Flow<HttpStreamEvent> =
    flow {
        emit(HttpStreamEvent.Head(status = status, headers = headers))
        var index = 0
        while (index < body.length) {
            val end = minOf(index + chunkSize, body.length)
            emit(HttpStreamEvent.Chunk(body.substring(index, end).toByteArray(Charsets.UTF_8)))
            index = end
        }
    }

/**
 * A stream that emits [prefix] then throws [failure], modeling a connection that
 * drops mid-response. The head is delivered first so the transport has a status.
 */
fun failingResponse(
    prefix: String,
    failure: Throwable,
    status: Int = 200,
): Flow<HttpStreamEvent> =
    flow {
        emit(HttpStreamEvent.Head(status = status))
        if (prefix.isNotEmpty()) emit(HttpStreamEvent.Chunk(prefix.toByteArray(Charsets.UTF_8)))
        throw failure
    }

/**
 * A stream that emits [prefix] and then stays open until its consumer cancels
 * it; [onCancel] records that the cancellation reached the engine.
 */
fun stallingResponse(
    prefix: String,
    onCancel: () -> Unit = {},
): Flow<HttpStreamEvent> =
    flow {
        emit(HttpStreamEvent.Head(status = 200))
        if (prefix.isNotEmpty()) emit(HttpStreamEvent.Chunk(prefix.toByteArray(Charsets.UTF_8)))
        try {
            awaitCancellation()
        } finally {
            onCancel()
        }
    }

/** Loads recorded frame fixtures from `src/test/resources`. */
object RecordedFixtures {
    /** Reads the UTF-8 text at the classpath [path], failing loudly when absent. */
    fun text(path: String): String {
        val stream =
            RecordedFixtures::class.java.classLoader.getResourceAsStream(path)
                ?: error("missing recorded fixture: $path")
        return stream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** Reads a recorded SSE fixture under `llm/openai/`. */
    fun openAi(name: String): String = text("llm/openai/$name")
}
