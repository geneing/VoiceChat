package com.voicechat.agent.remote

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The shared transport's contract: HTTP status and transport failures become the
 * right typed errors, frames stream through, bodies are bounded, cancellation
 * propagates, and no header or body leaks through `toString`.
 */
class RemoteTransportTest {
    private val request = RemoteHttpRequest(method = "POST", url = "https://api.example.com/v1/responses", body = "{}")

    private suspend fun errorFrom(status: Int): ErrorCode? {
        val engine = FakeHttpStreamingEngine { scriptedResponse(status = status, body = "{\"error\":\"nope\"}") }
        val failure =
            runCatching { RemoteTransport(engine).streamSse(request).toList() }.exceptionOrNull()
        return (failure as? VoiceAgentException)?.error?.code
    }

    @Test
    fun eachHttpFailureStatusMapsToItsTypedReason() =
        runTest {
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, errorFrom(401))
            assertEquals(ErrorCode.LLM_AUTHENTICATION_FAILED, errorFrom(403))
            assertEquals(ErrorCode.LLM_RATE_LIMITED, errorFrom(429))
            assertEquals(ErrorCode.LLM_TIMEOUT, errorFrom(408))
            assertEquals(ErrorCode.LLM_TIMEOUT, errorFrom(504))
            assertEquals(ErrorCode.LLM_UNAVAILABLE, errorFrom(500))
            assertEquals(ErrorCode.LLM_UNAVAILABLE, errorFrom(503))
            assertEquals(ErrorCode.LLM_INVALID_REQUEST, errorFrom(400))
            assertEquals(ErrorCode.LLM_INVALID_REQUEST, errorFrom(404))
        }

    @Test
    fun aSuccessfulResponseStreamsDecodedFrames() =
        runTest {
            val body = "event: a\ndata: {\"n\":1}\n\nevent: b\ndata: {\"n\":2}\n\n"
            val engine = FakeHttpStreamingEngine { scriptedResponse(body = body, chunkSize = 5) }

            val frames = RemoteTransport(engine).streamSse(request).toList()

            assertEquals(listOf("a", "b"), frames.map { it.event })
        }

    @Test
    fun aMidStreamEngineFailurePropagatesTyped() =
        runTest {
            val engine =
                FakeHttpStreamingEngine {
                    failingResponse(
                        prefix = "event: a\ndata: {}\n\n",
                        failure =
                            VoiceAgentException(
                                com.voicechat.agent.domain
                                    .VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED),
                            ),
                    )
                }

            val failure = runCatching { RemoteTransport(engine).streamSse(request).toList() }.exceptionOrNull()

            assertEquals(ErrorCode.LLM_NETWORK_FAILED, (failure as VoiceAgentException).error.code)
        }

    @Test
    fun fetchReturnsTheStatusEvenOnAFailureResponseAndBoundsTheBody() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse(status = 401, body = "x".repeat(100)) }

            val response = RemoteTransport(engine).fetch(request, maxBytes = 10)

            assertEquals(401, response.status)
            assertEquals(10, response.body.length)
            assertTrue(response.truncated)
        }

    @Test
    fun cancellingCollectionCancelsTheEngineStream() =
        runTest {
            val cancelled = AtomicBoolean(false)
            val engine = FakeHttpStreamingEngine { stallingResponse("event: a\ndata: {}\n\n") { cancelled.set(true) } }
            val transport = RemoteTransport(engine)

            val collected = mutableListOf<SseFrame>()
            val job = launch { transport.streamSse(request).collect { collected += it } }
            advanceUntilIdle()
            assertEquals(1, collected.size)
            job.cancel()
            job.join()

            assertTrue("the engine must observe the cancellation", cancelled.get())
        }

    @Test
    fun theOkHttpFailureMapperClassifiesTimeoutNetworkAndOther() {
        assertEquals(ErrorCode.LLM_TIMEOUT, OkHttpFailureMapper.map(SocketTimeoutException()).error.code)
        assertEquals(ErrorCode.LLM_TIMEOUT, OkHttpFailureMapper.map(InterruptedIOException()).error.code)
        assertEquals(ErrorCode.LLM_NETWORK_FAILED, OkHttpFailureMapper.map(IOException("boom")).error.code)
        assertEquals(ErrorCode.LLM_REQUEST_FAILED, OkHttpFailureMapper.map(IllegalStateException("boom")).error.code)
    }

    @Test
    fun aRequestNeverRendersItsHeaderValuesOrBody() {
        val secret = "sk-live-DO-NOT-LEAK-0123456789"
        val request =
            RemoteHttpRequest(
                method = "POST",
                url = "https://api.openai.com/v1/responses",
                headers = listOf(RemoteHttpHeader("Authorization", "Bearer $secret"), RemoteHttpHeader("Accept", "application/json")),
                body = "{\"input\":\"my private prompt\"}",
            )

        val rendered = request.toString()

        assertFalse(rendered.contains(secret))
        assertFalse(rendered.contains("my private prompt"))
        assertTrue(rendered.contains("[redacted]"))
        // A harmless header survives, so redaction is not just dropping the map.
        assertTrue(rendered.contains("application/json"))
    }

    @Test
    fun closeDelegatesToTheEngine() =
        runTest {
            val engine = FakeHttpStreamingEngine { scriptedResponse() }

            RemoteTransport(engine).close()

            assertTrue(engine.closed.get())
        }

    @Test
    fun atMostTheConfiguredNumberOfStreamsReachTheEngineAtOnce() =
        runTest {
            val inFlight = AtomicInteger(0)
            val maxObserved = AtomicInteger(0)
            val started = AtomicInteger(0)
            val engine =
                FakeHttpStreamingEngine {
                    flow {
                        val concurrent = inFlight.incrementAndGet()
                        maxObserved.updateAndGet { maxOf(it, concurrent) }
                        started.incrementAndGet()
                        try {
                            emit(HttpStreamEvent.Head(status = 200))
                            awaitCancellation()
                        } finally {
                            inFlight.decrementAndGet()
                        }
                    }
                }
            val transport = RemoteTransport(engine, maxConcurrentRequests = 2)

            val jobs =
                List(4) {
                    launch { transport.streamSse(request).collect { } }
                }
            runCurrent()

            assertEquals("only two calls may reach the engine", 2, started.get())
            assertEquals("the engine must never see more than the cap", 2, maxObserved.get())

            jobs.forEach { it.cancel() }
        }
}
