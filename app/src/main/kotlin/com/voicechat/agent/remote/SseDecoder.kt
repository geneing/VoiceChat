package com.voicechat.agent.remote

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * One decoded Server-Sent Events frame.
 *
 * [event] is the `event:` field (OpenAI's Responses API always sends it, but the
 * payload's own `type` field is the authoritative discriminator), [data] is the
 * `data:` field with multi-line values joined by `\n`, and [id]/[retryMillis]
 * carry the optional `id:`/`retry:` fields. No JSON is parsed here: a frame
 * stays a string until a provider adapter interprets its own payload.
 */
data class SseFrame(
    val event: String?,
    val data: String,
    val id: String? = null,
    val retryMillis: Long? = null,
) {
    /** The `data:` payload without its `event:`/`id:` metadata. */
    val isDataEmpty: Boolean get() = data.isEmpty()
}

/**
 * Decodes a byte stream of SSE frames into [SseFrame]s.
 *
 * The decoder is pure and transport-free, which is what makes the shared fixture
 * harness (M14-M19) possible: recorded frames are replayed through it with no
 * socket, clock, or credential. It follows the SSE field rules that matter for
 * provider streams:
 *
 * - `\n` and `\r\n`-terminated lines are accepted;
 * - a line starting with `:` is a comment and is ignored (OpenAI uses these as
 *   keep-alives), so a comment payload is never mistaken for data;
 * - a single leading space after the colon is stripped, per the SSE spec;
 * - multiple `data:` lines in one frame are joined with `\n`;
 * - a frame is dispatched at a blank line, and a final unterminated frame is
 *   flushed when the stream ends.
 *
 * **Bounds and backpressure.** Emission is suspending, so a slow consumer
 * backpressures the producer instead of buffering. A line or accumulated frame
 * larger than [maxFrameBytes] ends the flow with a typed
 * `LLM_MALFORMED_RESPONSE` instead of growing memory without limit.
 */
object SseDecoder {
    /** Default cap for one SSE line or one accumulated frame payload. */
    const val DEFAULT_MAX_FRAME_BYTES: Int = 4 * 1024 * 1024

    /** Decodes a stream of raw body [chunks] (any byte split) into frames. */
    fun decode(
        chunks: Flow<ByteArray>,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    ): Flow<SseFrame> = decodeLines(lines(chunks, maxFrameBytes), maxFrameBytes)

    /** Decodes a stream of text [lines] (without terminators) into frames. */
    fun decodeLines(
        lines: Flow<String>,
        maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
    ): Flow<SseFrame> =
        flow {
            val data = StringBuilder()
            var event: String? = null
            var id: String? = null
            var retry: Long? = null
            var sawField = false

            lines.collect { raw ->
                val line = raw.removeSuffix("\r")
                if (line.isEmpty()) {
                    if (sawField) {
                        emit(SseFrame(event = event, data = data.toString(), id = id, retryMillis = retry))
                    }
                    data.clear()
                    event = null
                    id = null
                    retry = null
                    sawField = false
                    return@collect
                }
                // A comment line (keep-alive) is never data.
                if (line.startsWith(":")) return@collect

                val colon = line.indexOf(':')
                val field = if (colon < 0) line else line.substring(0, colon)
                val rawValue = if (colon < 0) "" else line.substring(colon + 1)
                val value = if (rawValue.startsWith(" ")) rawValue.substring(1) else rawValue

                when (field) {
                    "data" -> {
                        if (data.isNotEmpty()) data.append('\n')
                        data.append(value)
                        if (data.length > maxFrameBytes) {
                            throw malformed("an SSE frame exceeded the $maxFrameBytes-byte limit")
                        }
                    }

                    "event" -> {
                        event = value
                    }

                    "id" -> {
                        id = value
                    }

                    "retry" -> {
                        retry = value.toLongOrNull()
                    }

                    else -> {
                        Unit
                    }
                }
                sawField = true
            }

            // A final frame without a trailing blank line is still a frame.
            if (sawField) emit(SseFrame(event = event, data = data.toString(), id = id, retryMillis = retry))
        }

    private fun lines(
        chunks: Flow<ByteArray>,
        maxFrameBytes: Int,
    ): Flow<String> =
        flow {
            var buffer = ByteArray(0)
            chunks.collect { chunk ->
                if (chunk.isEmpty()) return@collect
                buffer = if (buffer.isEmpty()) chunk else buffer + chunk
                var start = 0
                for (i in buffer.indices) {
                    if (buffer[i] == NEWLINE) {
                        emit(String(buffer, start, i - start, Charsets.UTF_8))
                        start = i + 1
                    }
                }
                if (start > 0) buffer = buffer.copyOfRange(start, buffer.size)
                if (buffer.size > maxFrameBytes) {
                    throw malformed("an SSE line exceeded the $maxFrameBytes-byte limit")
                }
            }
            if (buffer.isNotEmpty()) emit(String(buffer, 0, buffer.size, Charsets.UTF_8))
        }

    private fun malformed(detail: String) = VoiceAgentException(VoiceAgentError(ErrorCode.LLM_MALFORMED_RESPONSE, detail))

    private const val NEWLINE: Byte = '\n'.code.toByte()
}
