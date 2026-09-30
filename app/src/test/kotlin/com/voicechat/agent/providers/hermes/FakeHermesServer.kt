package com.voicechat.agent.providers.hermes

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.voicechat.agent.remote.RecordedFixtures
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** How the [FakeHermesServer] answers `POST /v1/chat/completions`. */
enum class FakeHermesBehavior {
    /** Serve the documented Chat Completions SSE stream (normal_stream.sse). */
    NORMAL,

    /** Reject the request with an OpenAI-style 401 error body. */
    AUTH_FAILURE,

    /** Fail with a 500 error body. */
    SERVER_ERROR,

    /** Answer 302 with a `Location` pointing at another path on this server. */
    REDIRECT,

    /** Send one delta, then hold the connection open until cancelled/closed. */
    STALL,
}

/**
 * A tiny, in-process Hermes Agent API Server mock built on the JDK's
 * `com.sun.net.httpserver` (no new test dependency).
 *
 * It serves the documented OpenAI-compatible surface needed to prove the real
 * transport path end to end over a loopback socket: bearer auth (`API_SERVER_KEY`),
 * `GET /v1/models`, and `POST /v1/chat/completions` streaming the documented
 * `chat.completion.chunk` frames plus a `hermes.tool.progress` named event and a
 * `data: [DONE]` sentinel. It also exposes the failure modes the acceptance
 * requires: an auth failure, a server error, and a redirect that must **not** be
 * followed (risk R-0074).
 *
 * Every request is recorded, so a test can assert the endpoint, the bearer header,
 * and that no redirect target was ever reached. There is no real executor, disk,
 * or external credential; the server binds an ephemeral loopback port.
 */
class FakeHermesServer(
    private val apiKey: String = "test-key",
    var behavior: FakeHermesBehavior = FakeHermesBehavior.NORMAL,
) : AutoCloseable {
    /** One request the server received. */
    data class RecordedRequest(
        val method: String,
        val path: String,
        val authorization: String?,
        val body: String,
    )

    /** Every `POST /v1/chat/completions` request, in order. */
    val requests: MutableList<RecordedRequest> = CopyOnWriteArrayList()

    /** How many times the redirect target path was requested (must stay 0). */
    val redirectHits: AtomicInteger = AtomicInteger(0)

    private val release = CountDownLatch(1)
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    init {
        server.executor = Executors.newCachedThreadPool { runnable -> Thread(runnable).apply { isDaemon = true } }
        server.createContext("/v1/chat/completions") { exchange -> safely { handleChat(exchange) } }
        server.createContext("/v1/models") { exchange -> safely { handleModels(exchange) } }
        server.createContext("/stolen") { exchange ->
            safely {
                redirectHits.incrementAndGet()
                respond(exchange, 200, "text/plain", "stolen")
            }
        }
        server.start()
    }

    /** The base URL a client points at, e.g. `http://127.0.0.1:54321/v1`. */
    val baseUrl: String get() = "http://127.0.0.1:${server.address.port}/v1"

    /** The bound loopback port. */
    val port: Int get() = server.address.port

    private fun handleChat(exchange: HttpExchange) {
        val body = exchange.requestBody.use { it.readBytes().toString(StandardCharsets.UTF_8) }
        val auth = exchange.requestHeaders.getFirst("Authorization")
        requests += RecordedRequest(exchange.requestMethod, exchange.requestURI.path, auth, body)
        if (auth != "Bearer $apiKey") {
            respond(exchange, 401, "application/json", "{\"error\":{\"type\":\"authentication_error\"}}")
            return
        }
        when (behavior) {
            FakeHermesBehavior.AUTH_FAILURE -> {
                respond(exchange, 401, "application/json", "{\"error\":{\"type\":\"authentication_error\"}}")
            }

            FakeHermesBehavior.SERVER_ERROR -> {
                respond(exchange, 500, "application/json", "{\"error\":{\"type\":\"server_error\"}}")
            }

            FakeHermesBehavior.REDIRECT -> {
                exchange.responseHeaders.add("Location", "http://127.0.0.1:$port/stolen")
                exchange.sendResponseHeaders(302, -1)
                exchange.close()
            }

            FakeHermesBehavior.STALL -> {
                beginSse(exchange)
                exchange.responseBody.write(deltaChunk("partial").toByteArray(StandardCharsets.UTF_8))
                exchange.responseBody.flush()
                release.await(10, TimeUnit.SECONDS)
                exchange.close()
            }

            FakeHermesBehavior.NORMAL -> {
                beginSse(exchange)
                exchange.responseBody.write(RecordedFixtures.text("llm/hermes/normal_stream.sse").toByteArray(StandardCharsets.UTF_8))
                exchange.close()
            }
        }
    }

    private fun handleModels(exchange: HttpExchange) {
        val auth = exchange.requestHeaders.getFirst("Authorization")
        if (auth != "Bearer $apiKey") {
            respond(exchange, 401, "application/json", "{\"error\":{\"type\":\"authentication_error\"}}")
            return
        }
        respond(exchange, 200, "application/json", RecordedFixtures.text("llm/hermes/models_list.json"))
    }

    private fun beginSse(exchange: HttpExchange) {
        exchange.responseHeaders.add("Content-Type", "text/event-stream")
        exchange.responseHeaders.add("Cache-Control", "no-cache")
        // 0 = chunked (unknown length), so the stream can stay open.
        exchange.sendResponseHeaders(200, 0)
    }

    private fun respond(
        exchange: HttpExchange,
        status: Int,
        contentType: String,
        body: String,
    ) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun deltaChunk(text: String): String =
        "data: {\"id\":\"chatcmpl-mock\",\"object\":\"chat.completion.chunk\",\"model\":\"hermes-agent\"," +
            "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n"

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            // A client cancellation closes the socket; the mock ignores it.
        }
    }

    override fun close() {
        release.countDown()
        server.stop(0)
    }
}
