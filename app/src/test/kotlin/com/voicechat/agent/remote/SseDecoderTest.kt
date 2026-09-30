package com.voicechat.agent.remote

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SSE decoder is the replayable core of the shared fixture harness: recorded
 * frames go through it with no socket. These tests pin the SSE field rules and
 * the frame-size bound.
 */
class SseDecoderTest {
    @Test
    fun decodesFramesAcrossOneByteChunks() =
        runTest {
            val body =
                "event: response.output_text.delta\n" +
                    "data: {\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}\n\n" +
                    ": keep-alive comment\n" +
                    "event: second\n" +
                    "data: line one\n" +
                    "data: line two\n\n"
            val chunks = body.map { it.toString().toByteArray(Charsets.UTF_8) }.asFlow()

            val frames = SseDecoder.decode(chunks).toList()

            assertEquals(2, frames.size)
            assertEquals("response.output_text.delta", frames[0].event)
            assertEquals("{\"type\":\"response.output_text.delta\",\"delta\":\"Hi\"}", frames[0].data)
            assertEquals("second", frames[1].event)
            assertEquals("line one\nline two", frames[1].data)
        }

    @Test
    fun aCommentPayloadIsNeverMistakenForData() =
        runTest {
            val body = ": this is only a keep-alive\n: still a comment\n\n"
            val frames = SseDecoder.decode(flowOf(body.toByteArray(Charsets.UTF_8))).toList()

            assertTrue(frames.isEmpty())
        }

    @Test
    fun crlfLineEndingsAreAccepted() =
        runTest {
            val body = "event: a\r\ndata: {\"x\":1}\r\n\r\n"
            val frames = SseDecoder.decode(flowOf(body.toByteArray(Charsets.UTF_8))).toList()

            assertEquals(1, frames.size)
            assertEquals("a", frames[0].event)
            assertEquals("{\"x\":1}", frames[0].data)
        }

    @Test
    fun aFinalUnterminatedFrameIsFlushedAtEndOfStream() =
        runTest {
            val body = "event: a\ndata: no trailing blank line"
            val frames = SseDecoder.decode(flowOf(body.toByteArray(Charsets.UTF_8))).toList()

            assertEquals(1, frames.size)
            assertEquals("no trailing blank line", frames[0].data)
        }

    @Test
    fun idAndRetryFieldsAreCaptured() =
        runTest {
            val body = "id: 42\nretry: 1500\nevent: a\ndata: {}\n\n"
            val frame = SseDecoder.decode(flowOf(body.toByteArray(Charsets.UTF_8))).toList().single()

            assertEquals("42", frame.id)
            assertEquals(1500L, frame.retryMillis)
        }

    @Test
    fun anOversizedFrameEndsWithATypedMalformedFailure() =
        runTest {
            val body = "data: " + "x".repeat(64) + "\n\n"
            val failure =
                runCatching {
                    SseDecoder.decode(flowOf(body.toByteArray(Charsets.UTF_8)), maxFrameBytes = 32).toList()
                }.exceptionOrNull()

            assertTrue("expected a typed failure, was $failure", failure is VoiceAgentException)
            assertEquals(ErrorCode.LLM_MALFORMED_RESPONSE, (failure as VoiceAgentException).error.code)
        }

    @Test
    fun anEmptyBodyProducesNoFrames() =
        runTest {
            assertTrue(SseDecoder.decode(flowOf(ByteArray(0))).toList().isEmpty())
        }

    @Test
    fun aFrameWithNoEventFieldKeepsANullEventName() =
        runTest {
            val frame = SseDecoder.decode(flowOf("data: {}\n\n".toByteArray(Charsets.UTF_8))).toList().single()

            assertNull(frame.event)
            assertEquals("{}", frame.data)
        }
}
