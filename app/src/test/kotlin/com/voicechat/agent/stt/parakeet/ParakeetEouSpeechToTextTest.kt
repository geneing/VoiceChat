package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stream-3 contract tests for [ParakeetEouSpeechToText], driven by a fake
 * [ParakeetDecodeSession] so no ONNX or model file is needed.
 *
 * They prove the properties the frozen contract requires of the adapter: partial
 * vs final mapping, monotonic revisions, that captured `AudioFrame` bytes reach
 * the decoder unchanged as float32, that a decoder failure becomes a typed
 * [SttEvent.Failed], and that cancellation stops emission and closes the session.
 */
class ParakeetEouSpeechToTextTest {
    private val config = ParakeetEouSpeechToTextConfig(sampleRateHz = 16_000, chunkSamples = 4)

    private fun frame(
        vararg samples: Short,
        capturedAtNanos: Long = 0L,
    ): AudioFrame = AudioFrame(AudioFormat.MONO_16_KHZ, samples, capturedAtNanos)

    private fun listener(session: ParakeetDecodeSession): Pair<ParakeetDecoderSessionFactory, MutableList<ParakeetDecodeSession>> {
        val created = mutableListOf<ParakeetDecodeSession>()
        val factory =
            ParakeetDecoderSessionFactory {
                created += session
                session
            }
        return factory to created
    }

    @Test
    fun partialResultsAreInterimAndTheFinalComesOnce() =
        runTest {
            val session =
                ScriptedDecodeSession(
                    acceptResults =
                        listOf(
                            DecodeResult("hello", false, false, 0.9f, 0),
                            DecodeResult("hello world", false, false, 0.8f, 0),
                        ),
                    finalizeResult = DecodeResult("hello world", true, false, 0.8f, 1),
                )
            val (factory, _) = listener(session)
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config)

            val events =
                adapter
                    .transcribe(flowOf(frame(1, 2, 3, 4), frame(5, 6, 7, 8)))
                    .toList()
                    .filterIsInstance<SttEvent.Result>()
                    .map { it.transcript }

            assertEquals(3, events.size)
            assertEquals(listOf(false, false, true), events.map { it.isFinal })
            assertEquals("hello", events[0].text)
            assertEquals("hello world", events[1].text)
            assertEquals("hello world", events[2].text)
        }

    @Test
    fun revisionsAreStrictlyMonotonicAcrossTheSession() =
        runTest {
            val session =
                ScriptedDecodeSession(
                    acceptResults =
                        listOf(
                            DecodeResult("a", false, false, 1f, 0),
                            DecodeResult("ab", false, false, 1f, 0),
                        ),
                    finalizeResult = DecodeResult("ab", true, false, 1f, 1),
                )
            val (factory, _) = listener(session)
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config)

            val revisions =
                adapter
                    .transcribe(flowOf(frame(1, 2, 3, 4), frame(5, 6, 7, 8)))
                    .toList()
                    .filterIsInstance<SttEvent.Result>()
                    .map { it.transcript.revision.value }

            assertEquals(listOf(0, 1, 2), revisions)
            assertEquals(revisions.sorted(), revisions)
            assertEquals(revisions.distinct(), revisions)
        }

    @Test
    fun capturedSamplesReachTheDecoderUnchangedAsFloat32() =
        runTest {
            val received = mutableListOf<FloatArray>()
            val session =
                object : ParakeetDecodeSession {
                    override fun reset() = Unit

                    override fun accept(chunkSamples: FloatArray): DecodeResult {
                        received += chunkSamples.copyOf()
                        return DecodeResult("", false, false, 1f, 0)
                    }

                    override fun finalize(): DecodeResult = DecodeResult("", true, false, 1f, 0)

                    override fun forceEndOfUtterance(): DecodeResult = DecodeResult("", true, false, 1f, 0)

                    override fun close() = Unit
                }
            val (factory, _) = listener(session)
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config)

            adapter.transcribe(flowOf(frame(1000, -2000, 32767, -32768))).toList()

            assertEquals(1, received.size)
            val chunk = received.single()
            assertEquals(4, chunk.size)
            assertEquals(1000f / 32768f, chunk[0], 0f)
            assertEquals(-2000f / 32768f, chunk[1], 0f)
            assertEquals(32767f / 32768f, chunk[2], 0f)
            assertEquals(-1f, chunk[3], 0f)
        }

    @Test
    fun aFrameSpanningMultipleChunksIsSplitInCaptureOrder() =
        runTest {
            val received = mutableListOf<FloatArray>()
            val session =
                object : ParakeetDecodeSession {
                    override fun reset() = Unit

                    override fun accept(chunkSamples: FloatArray): DecodeResult {
                        received += chunkSamples.copyOf()
                        return DecodeResult("", false, false, 1f, 0)
                    }

                    override fun finalize(): DecodeResult = DecodeResult("", true, false, 1f, 0)

                    override fun forceEndOfUtterance(): DecodeResult = DecodeResult("", true, false, 1f, 0)

                    override fun close() = Unit
                }
            val (factory, _) = listener(session)
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config)

            adapter.transcribe(flowOf(frame(1, 2, 3, 4, 5, 6, 7, 8))).toList()

            // Two whole chunks of four samples each, in order.
            assertEquals(2, received.size)
            assertEquals(listOf(1f, 2f, 3f, 4f), received[0].toList().map { it * 32768f })
            assertEquals(listOf(5f, 6f, 7f, 8f), received[1].toList().map { it * 32768f })
        }

    @Test
    fun modelEouProducesAFinalTranscriptAndPublishesTheSignal() =
        runTest {
            val session =
                ScriptedDecodeSession(
                    acceptResults = listOf(DecodeResult("done", true, true, 0.7f, 1)),
                    finalizeResult = DecodeResult("", true, false, 1f, 1),
                )
            val (factory, _) = listener(session)
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config)

            val results =
                adapter
                    .transcribe(flowOf(frame(1, 2, 3, 4)))
                    .toList()
                    .filterIsInstance<SttEvent.Result>()

            assertTrue(results.last().transcript.isFinal)
            assertEquals("done", results.last().transcript.text)
            assertEquals(0L, adapter.endOfUtterance.lastEouOffsetMillis())
        }

    @Test
    fun aDecoderFailureBecomesATypedSttFailed() =
        runTest {
            val session =
                object : ParakeetDecodeSession {
                    override fun reset() = Unit

                    override fun accept(chunkSamples: FloatArray): DecodeResult = throw IllegalStateException("boom")

                    override fun finalize(): DecodeResult = DecodeResult("", true, false, 1f, 0)

                    override fun forceEndOfUtterance(): DecodeResult = DecodeResult("", true, false, 1f, 0)

                    override fun close() = Unit
                }
            val (factory, _) = listener(session)
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config)

            val events = adapter.transcribe(flowOf(frame(1, 2, 3, 4))).toList()

            val failure = events.filterIsInstance<SttEvent.Failed>().single()
            assertEquals(ErrorCode.STT_RECOGNITION_FAILED, failure.error.code)
            assertTrue("the platform message must not leak into the typed detail", failure.error.detail?.contains("boom") != true)
        }

    @Test
    fun theCharacterCountIsRecordedButNeverTheTranscript() =
        runTest {
            val sink = RecordingDiagnosticsSink()
            val session =
                ScriptedDecodeSession(
                    acceptResults = listOf(DecodeResult("secret words", false, false, 1f, 0)),
                    finalizeResult = DecodeResult("secret words", true, false, 1f, 1),
                )
            val (factory, _) = listener(session)
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config, diagnostics = sink)

            adapter.transcribe(flowOf(frame(1, 2, 3, 4))).toList()

            val sttEvents = sink.events.filter { it.stage == DiagnosticStage.SPEECH_TO_TEXT }
            val completed = sttEvents.single { it.outcome == com.voicechat.agent.contracts.DiagnosticOutcome.COMPLETED }
            assertEquals("12", completed.attributes[DiagnosticAttribute.CHARACTER_COUNT])
            assertTrue(
                "no diagnostic value may contain transcript text",
                sink.events.none { event -> event.attributes.values.any { it.contains("secret") } },
            )
        }

    @Test
    fun cancellationStopsEmissionAndClosesTheSession() =
        runTest {
            val closed = CompletableDeferred<Unit>()
            val acceptStarted = CompletableDeferred<Unit>()
            val session =
                object : ParakeetDecodeSession {
                    override fun reset() = Unit

                    override fun accept(chunkSamples: FloatArray): DecodeResult {
                        acceptStarted.complete(Unit)
                        return DecodeResult("partial", false, false, 1f, 0)
                    }

                    override fun finalize(): DecodeResult {
                        // Must never be reached after cancellation.
                        throw AssertionError("finalize must not run after cancellation")
                    }

                    override fun forceEndOfUtterance(): DecodeResult = DecodeResult("", true, false, 1f, 0)

                    override fun close() {
                        closed.complete(Unit)
                    }
                }
            val factory = ParakeetDecoderSessionFactory { session }
            val adapter = ParakeetEouSpeechToText(decoderFactory = factory, config = config)

            val seen = mutableListOf<SttEvent>()
            val job =
                CoroutineScope(Dispatchers.Default).launch {
                    adapter
                        .transcribe(
                            kotlinx.coroutines.flow.flow {
                                // Infinite capture: `finalize()` is only reached if the
                                // session is NOT cancelled, so this isolates the
                                // cancellation behavior.
                                while (true) emit(frame(1, 2, 3, 4))
                            },
                        ).collect { seen += it }
                }
            // Wait until the partial has actually been delivered before cancelling.
            assertTrue("the partial result never arrived", withTimeoutOrNull(5_000) { waitUntil { seen.isNotEmpty() } } != null)
            job.cancelAndJoin()

            assertTrue("the session must be closed on cancellation", withTimeoutOrNull(5_000) { closed.await() } != null)
            // Only interim results may have been delivered; no final may be appended
            // after cancellation. (An infinite fast producer can legitimately deliver
            // more than one partial before the cancel lands.)
            assertTrue("only interim results may be delivered", seen.all { it is SttEvent.Result && !it.transcript.isFinal })
            assertTrue("at least the first partial must have been delivered", seen.isNotEmpty())
        }

    private suspend fun waitUntil(predicate: () -> Boolean) {
        while (!predicate()) {
            kotlinx.coroutines.yield()
        }
    }

    /** A decode session that returns scripted results and counts calls. */
    private class ScriptedDecodeSession(
        private val acceptResults: List<DecodeResult>,
        private val finalizeResult: DecodeResult,
    ) : ParakeetDecodeSession {
        private var acceptIndex = 0

        override fun reset() = Unit

        override fun accept(chunkSamples: FloatArray): DecodeResult =
            acceptResults.getOrElse(acceptIndex++) { acceptResults.lastOrNull() ?: DecodeResult("", false, false, 1f, 0) }

        override fun finalize(): DecodeResult = finalizeResult

        override fun forceEndOfUtterance(): DecodeResult = finalizeResult

        override fun close() = Unit
    }
}
