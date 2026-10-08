package com.voicechat.agent.stt.parakeet

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * The app-facing on-device [SpeechToText] for the Parakeet-EOU streaming engine
 * (stream 3 of `docs/parakeet-eou-streaming.md`).
 *
 * **What it owns.** It is the only class that connects the M02 audio/STT
 * contracts to stream 1's `ParakeetStreamingDecoder`: it consumes the 16 kHz mono
 * [AudioFrame] capture flow, converts packed 16-bit samples to float32, buffers
 * whole decode chunks, drives the decoder off the main thread, and maps decode
 * steps to [SttEvent]s. It also publishes the model's `<EOU>` signal through
 * [EndOfUtteranceSignal] so the M09/M10 turn policy can corroborate a candidate
 * pause without a second decode ([EouTurnCompletionDetector]).
 *
 * **Partial vs final.** Every decode step that produces text emits
 * `isFinal = false` (a revisable hypothesis) with a monotonic
 * [TranscriptRevision]. A decode step that ends the utterance — the model's
 * confirmed `<EOU>`, a VAD-driven [ParakeetDecodeSession.forceEndOfUtterance],
 * or end-of-input [ParakeetDecodeSession.finalize] — emits exactly one
 * `isFinal = true` transcript, and nothing more is emitted for that session.
 *
 * **Non-blocking, honest backpressure.** Decode is CPU-heavy (~100-200 ms per
 * 320 ms chunk on device), so the capture coroutine only converts and forwards
 * chunks through a **bounded** channel; it never blocks a full chunk on decode.
 * When a consumer falls behind, the oldest buffered chunk is dropped, the drop is
 * counted, and the count is surfaced as `AUDIO_DROPPED_FRAMES` in diagnostics and
 * in the append path — exactly the R-0052 policy of counting rather than
 * silently discarding. A dropped chunk is a real gap; this adapter prefers an
 * honest count over pretending the audio was transcribed.
 *
 * **Cancellation.** The returned flow is cold and completes after the single
 * final result or failure. Cancelling collection cancels both the capture and the
 * decode coroutine; the decode coroutine is cooperative (it checks for
 * cancellation between chunks), and no event is emitted after cancellation.
 *
 * **Privacy.** It never logs or records transcript text, raw samples, or chunk
 * bytes; diagnostics carry only counts, timings, stable reason codes, and the
 * engine id.
 *
 * @param engineId the engine identity reported to settings and diagnostics.
 * @param decoderFactory builds one decode session per [transcribe] call.
 * @param config chunking/debounce parameters (see [ParakeetEouSpeechToTextConfig]).
 * @param diagnostics privacy-safe M04 sink.
 * @param clock monotonic clock for the per-chunk timing.
 * @param decodeDispatcher dispatcher the decode loop runs on; defaults to
 *   [Dispatchers.Default] because the work is CPU-bound, not I/O.
 */
class ParakeetEouSpeechToText(
    override val engineId: EngineId = ENGINE_ID,
    private val decoderFactory: ParakeetDecoderSessionFactory,
    private val config: ParakeetEouSpeechToTextConfig = ParakeetEouSpeechToTextConfig.default(),
    /**
     * Input level normalizer applied to each capture chunk before decode. The
     * pinned model feeds raw log-mel with no normalization, so quiet microphone
     * audio must be brought toward the level it was evaluated at; see
     * [ParakeetInputGain]. Tests that assert the raw PCM conversion pass
     * [ParakeetInputGain.PASSTHROUGH].
     */
    private val inputGain: ParakeetInputGain = ParakeetInputGain(),
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val decodeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : SpeechToText {
    private val closed = AtomicBoolean(false)

    private val eouSignal = ParakeetEouSignal()

    /** The model's confirmed `<EOU>` signal, for the turn-completion detector. */
    val endOfUtterance: EndOfUtteranceSignal get() = eouSignal

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            check(!closed.get()) { "ParakeetEouSpeechToText has been closed" }

            val startedAtNanos = clock.nanoTime()
            var frameCount = 0
            var chunkCount = 0
            var finalCharacterCount = 0
            var droppedChunks = 0L
            var totalDecodeNanos = 0L
            var maxDecodeNanos = 0L
            var eouConfirmedCount = 0
            var revision = 0
            var failed = false
            var outcome = DiagnosticOutcome.CANCELLED
            var errorCode: String? = null
            AppLog.d {
                "stt: parakeet session start engine=${engineId.value} " +
                    "chunkSamples=${config.chunkSamples} queueCapacity=${config.chunkQueueCapacity} " +
                    "eouDebounceMs=${config.eouDebounceMillis}"
            }
            record(
                outcome = DiagnosticOutcome.STARTED,
                startedAtNanos = startedAtNanos,
                frameCount = frameCount,
                chunkCount = chunkCount,
                characterCount = finalCharacterCount,
                droppedChunks = 0L,
                errorCode = errorCode,
            )

            // New capture: clear the EOU signal so a stale confirmation from the
            // previous turn can never corroborate this one.
            eouSignal.resetCapture()

            val session = decoderFactory.create()
            try {
                coroutineScope {
                    // Bounded chunk queue: the capture side converts and enqueues;
                    // the decode side is the slow consumer. Capacity is in chunks.
                    val chunks = Channel<FloatArray>(capacity = config.chunkQueueCapacity)
                    // Events are produced on the decode dispatcher and emitted from
                    // this flow's own coroutine, so a FlowCollector is never shared
                    // across coroutines.
                    val events = Channel<SttEvent>(Channel.UNLIMITED)

                    val capture =
                        launch {
                            // Read at most chunkQueueCapacity + 1 without a consumer so
                            // an overflowing producer drops the oldest buffered chunk and
                            // counts it, instead of suspending the capture flow.
                            val buffer = ArrayDeque<FloatArray>()
                            var pending = FloatArray(config.chunkSamples)
                            var pendingCount = 0

                            audio.collect { frame ->
                                frameCount++
                                val samples = frame.samples
                                var index = 0
                                while (index < samples.size) {
                                    val room = config.chunkSamples - pendingCount
                                    val take = minOf(room, samples.size - index)
                                    for (offset in 0 until take) {
                                        pending[pendingCount + offset] = samples[index + offset] / PCM_FULL_SCALE
                                    }
                                    pendingCount += take
                                    index += take
                                    if (pendingCount == config.chunkSamples) {
                                        val gain = inputGain.gainFor(pending, pendingCount)
                                        inputGain.apply(pending, pendingCount, gain)
                                        AppLog.v {
                                            "stt: parakeet input chunk normalized gain=$gain " +
                                                "(target=${ParakeetInputGain.DEFAULT_TARGET_PEAK})"
                                        }
                                        buffer.addLast(pending)
                                        pending = FloatArray(config.chunkSamples)
                                        pendingCount = 0
                                        // Drain to the decoder without ever blocking capture.
                                        while (buffer.isNotEmpty()) {
                                            if (chunks.trySend(buffer.first()).isSuccess) {
                                                buffer.removeFirst()
                                            } else {
                                                break
                                            }
                                        }
                                        // If the channel is full, keep buffering up to one
                                        // extra chunk, then drop the oldest to make room.
                                        while (buffer.size > config.chunkQueueCapacity) {
                                            buffer.removeFirst()
                                            droppedChunks++
                                            AppLog.v {
                                                "stt: parakeet chunk dropped total=$droppedChunks (decode consumer behind)"
                                            }
                                        }
                                    }
                                }
                            }
                            // Flush any trailing partial chunk on input end so no
                            // captured sample is silently discarded.
                            if (pendingCount > 0) {
                                val tail = pending.copyOf(pendingCount)
                                inputGain.apply(tail, pendingCount)
                                buffer.addLast(tail)
                            }
                            while (buffer.isNotEmpty()) {
                                if (chunks.trySend(buffer.first()).isSuccess) {
                                    buffer.removeFirst()
                                } else {
                                    buffer.removeFirst()
                                    droppedChunks++
                                }
                            }
                            chunks.close()
                            AppLog.d {
                                "stt: parakeet capture ended frames=$frameCount droppedChunks=$droppedChunks"
                            }
                        }

                    val decode =
                        launch(decodeDispatcher) {
                            // Tracks the terminal *inside this coroutine*, not via the
                            // collector, so a model-flagged final cannot be followed by a
                            // second finalize final (the two coroutines race otherwise).
                            var terminalSent = false
                            try {
                                for (chunk in chunks) {
                                    val decodeStartedAtNanos = clock.nanoTime()
                                    AppLog.v {
                                        "stt: parakeet chunk#$chunkCount samples=${chunk.size} dequeued; decoding"
                                    }
                                    val result = session.accept(chunk)
                                    val decodeNanos = clock.nanoTime() - decodeStartedAtNanos
                                    totalDecodeNanos += decodeNanos
                                    if (decodeNanos > maxDecodeNanos) maxDecodeNanos = decodeNanos
                                    chunkCount++
                                    AppLog.v {
                                        "stt: parakeet chunk#$chunkCount decoded decodeMs=${decodeNanos / 1_000_000L} " +
                                            "chars=${result.text.length} final=${result.isFinal} " +
                                            "eou=${result.eouDetected} segment=${result.segmentIndex} " +
                                            "confidence=${result.confidence}"
                                    }

                                    if (result.eouDetected) {
                                        val confirmed =
                                            eouSignal.confirmEou(
                                                offsetMillis = chunkCount.toLong() * chunkDurationMillis(config),
                                                confidence = result.confidence,
                                                debounceMillis = config.eouDebounceMillis,
                                            )
                                        if (confirmed) {
                                            eouConfirmedCount++
                                            AppLog.v {
                                                "stt: parakeet eou confirmed count=$eouConfirmedCount " +
                                                    "offsetMs=${chunkCount.toLong() * chunkDurationMillis(config)} " +
                                                    "confidence=${result.confidence}"
                                            }
                                        } else {
                                            AppLog.v {
                                                "stt: parakeet eou refresh (within debounce) " +
                                                    "offsetMs=${chunkCount.toLong() * chunkDurationMillis(config)}"
                                            }
                                        }
                                    }

                                    eventFor(result, revision++)?.let { event ->
                                        if (event is SttEvent.Result) {
                                            if (event.transcript.isFinal) terminalSent = true
                                            AppLog.v {
                                                "stt: parakeet emit revision=${revision - 1} " +
                                                    "final=${event.transcript.isFinal} " +
                                                    "chars=${event.transcript.text.length} " +
                                                    "confidence=${event.transcript.confidence}"
                                            }
                                        } else if (event is SttEvent.Failed) {
                                            AppLog.v {
                                                "stt: parakeet emit failed code=${event.error.code}"
                                            }
                                        }
                                        events.send(event)
                                    }
                                }

                                // End of input: flush the decoder's buffered right
                                // context. `finalize()` always yields the terminal
                                // transcript, so a trailing turn commits here even if
                                // the model never flagged EOU. If a decode step already
                                // emitted a final (a debounced EOU), the terminal was
                                // delivered and there is nothing to add.
                                if (!failed && !terminalSent) {
                                    val tail = session.finalize()
                                    AppLog.d {
                                        "stt: parakeet finalize chars=${tail.text.length} final=${tail.isFinal} " +
                                            "eou=${tail.eouDetected} confidence=${tail.confidence}"
                                    }
                                    if (tail.eouDetected) {
                                        if (eouSignal.confirmEou(
                                                offsetMillis = chunkCount.toLong() * chunkDurationMillis(config),
                                                confidence = tail.confidence,
                                                debounceMillis = config.eouDebounceMillis,
                                            )
                                        ) {
                                            eouConfirmedCount++
                                        }
                                    }
                                    eventFor(tail, revision++, forceFinal = true)?.let { event ->
                                        if (event is SttEvent.Result) {
                                            AppLog.v {
                                                "stt: parakeet emit (finalize) final=${event.transcript.isFinal} " +
                                                    "chars=${event.transcript.text.length} " +
                                                    "confidence=${event.transcript.confidence}"
                                            }
                                        }
                                        events.send(event)
                                    }
                                }
                            } catch (cancellation: CancellationException) {
                                throw cancellation
                            } catch (failure: Throwable) {
                                // Decoder/runtime failure mapped to a typed STT error;
                                // the underlying message is never surfaced (it may
                                // contain content).
                                failed = true
                                events.send(
                                    SttEvent.Failed(
                                        mapFailure(failure, ErrorCode.STT_RECOGNITION_FAILED, "decode failed"),
                                    ),
                                )
                            } finally {
                                events.close()
                            }
                        }

                    for (event in events) {
                        when (event) {
                            is SttEvent.Result -> {
                                if (event.transcript.isFinal) {
                                    outcome = DiagnosticOutcome.COMPLETED
                                    finalCharacterCount = event.transcript.text.length
                                }
                            }

                            is SttEvent.Failed -> {
                                outcome = DiagnosticOutcome.FAILED
                                errorCode = event.error.code.name
                                failed = true
                            }
                        }
                        emit(event)
                    }

                    capture.join()
                    decode.join()
                }
            } catch (cancellation: CancellationException) {
                AppLog.d { "stt: parakeet session cancelled frames=$frameCount chunks=$chunkCount" }
                outcome = DiagnosticOutcome.CANCELLED
                throw cancellation
            } catch (failure: Throwable) {
                // A failure outside the decode loop (for example the capture flow
                // itself). Mapped to a typed error; the message is never surfaced.
                AppLog.e { "stt: parakeet capture failed" }
                outcome = DiagnosticOutcome.FAILED
                errorCode = ErrorCode.STT_RECOGNITION_FAILED.name
                emit(SttEvent.Failed(mapFailure(failure, ErrorCode.STT_RECOGNITION_FAILED, "capture failed")))
            } finally {
                record(
                    outcome = outcome,
                    startedAtNanos = startedAtNanos,
                    frameCount = frameCount,
                    chunkCount = chunkCount,
                    characterCount = finalCharacterCount,
                    droppedChunks = droppedChunks,
                    errorCode = errorCode,
                    totalDecodeNanos = totalDecodeNanos,
                    maxDecodeNanos = maxDecodeNanos,
                    eouConfirmedCount = eouConfirmedCount,
                )
                AppLog.d {
                    "stt: parakeet session end outcome=$outcome frames=$frameCount chunks=$chunkCount dropped=$droppedChunks chars=$finalCharacterCount"
                }
                withContext(NonCancellable) { runCatching { session.close() } }
                eouSignal.markCaptureEnded()
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        eouSignal.markClosed()
    }

    /**
     * Maps a decode result to a contract event, or `null` when there is nothing
     * to emit (a silent chunk).
     *
     * [forceFinal] is set for the end-of-input flush: the decoder's `finalize()`
     * always yields the terminal transcript, so it becomes `isFinal = true` even
     * if the model never flagged EOU. This is what guarantees a trailing turn
     * commits here as well as through the policy's silence cap.
     */
    private fun eventFor(
        result: DecodeResult,
        revision: Int,
        forceFinal: Boolean = false,
    ): SttEvent? {
        val text = result.text
        val isFinal = result.isFinal || forceFinal
        if (!isFinal && text.isBlank()) return null
        val confidence = result.confidence.takeIf { it.isFinite() }?.coerceIn(0f, 1f)
        return SttEvent.Result(
            Transcript(
                text = text,
                revision = TranscriptRevision(revision),
                isFinal = isFinal,
                confidence = confidence,
            ),
        )
    }

    private fun record(
        outcome: DiagnosticOutcome,
        startedAtNanos: Long,
        frameCount: Int,
        chunkCount: Int,
        characterCount: Int,
        droppedChunks: Long,
        errorCode: String?,
        totalDecodeNanos: Long = 0L,
        maxDecodeNanos: Long = 0L,
        eouConfirmedCount: Int = 0,
    ) {
        val now = clock.nanoTime()
        diagnostics.record(
            DiagnosticEvent(
                stage = DiagnosticStage.SPEECH_TO_TEXT,
                outcome = outcome,
                monotonicTimeNanos = now,
                durationNanos = if (outcome == DiagnosticOutcome.STARTED) null else now - startedAtNanos,
                attributes =
                    buildMap {
                        put(DiagnosticAttribute.ENGINE_ID, engineId.value)
                        put(DiagnosticAttribute.FRAME_COUNT, frameCount.toString())
                        if (outcome != DiagnosticOutcome.STARTED) {
                            put(DiagnosticAttribute.CHARACTER_COUNT, characterCount.toString())
                        }
                        if (droppedChunks > 0L) {
                            put(DiagnosticAttribute.AUDIO_DROPPED_FRAMES, droppedChunks.toString())
                        }
                        if (chunkCount > 0) {
                            put(ATTR_DECODE_CHUNKS, chunkCount.toString())
                            // Measured per-chunk decode time, never a claim.
                            put(ATTR_DECODE_CHUNK_MILLIS, (totalDecodeNanos / chunkCount / 1_000_000L).toString())
                            put(ATTR_DECODE_MAX_CHUNK_MILLIS, (maxDecodeNanos / 1_000_000L).toString())
                        }
                        if (outcome != DiagnosticOutcome.STARTED) {
                            put(ATTR_EOU_CONFIRMED, eouConfirmedCount.toString())
                        }
                        errorCode?.let { put(DiagnosticAttribute.ERROR_CODE, it) }
                    },
            ),
        )
    }

    private fun mapFailure(
        failure: Throwable,
        code: ErrorCode,
        detail: String,
    ): VoiceAgentError {
        val carried = (failure as? VoiceAgentErrorCarrier)?.error
        return carried?.takeIf { it.code == code } ?: VoiceAgentError(code, detail)
    }

    companion object {
        /** Stable engine id for the Parakeet-EOU streaming recognizer. */
        val ENGINE_ID: EngineId = EngineId("parakeet-eou-streaming")

        /** 16-bit PCM full scale; sample/32768 maps to `[-1, 1)`. */
        private const val PCM_FULL_SCALE: Float = 32_768f

        /** Custom (non-enum) diagnostic attribute keys, kept as counts/timings only. */
        private val ATTR_DECODE_CHUNKS = DiagnosticAttribute.DECODE_CHUNK_COUNT

        private val ATTR_DECODE_CHUNK_MILLIS = DiagnosticAttribute.DECODE_CHUNK_MILLIS

        private val ATTR_DECODE_MAX_CHUNK_MILLIS = DiagnosticAttribute.DECODE_MAX_CHUNK_MILLIS

        private val ATTR_EOU_CONFIRMED = DiagnosticAttribute.EOU_CONFIRMED_COUNT

        private fun chunkDurationMillis(config: ParakeetEouSpeechToTextConfig): Long =
            config.chunkSamples.toLong() * 1000L / config.sampleRateHz
    }
}

/**
 * Tuning for [ParakeetEouSpeechToText] that is not part of the frozen decoder
 * config.
 *
 * [chunkSamples] must match the decoder's streaming chunk
 * (`ParakeetEouConfig.chunkSamples`, pinned at 10,240 = 64 mel frames × 160 hop
 * at 16 kHz). It is repeated here as a plain int so the adapter does not depend
 * on stream 1's config class existing at compile time; the app boundary passes
 * the pinned value from `ParakeetEouConfig`.
 *
 * @param sampleRateHz capture rate; 16 kHz for the pinned model.
 * @param chunkSamples samples per decode chunk; the decoder receives exactly one
 *   chunk per [ParakeetDecodeSession.accept] call.
 * @param chunkQueueCapacity how many decoded-but-unconsumed chunks may buffer
 *   before the oldest is dropped and counted (R-0052).
 * @param eouDebounceMillis collapse repeated `<EOU>` flags closer than this into
 *   the single confirmed observation. The head fires mid-word on disfluencies, so
 *   a sustained EOU run is one confirmation, not many.
 */
data class ParakeetEouSpeechToTextConfig(
    val sampleRateHz: Int = 16_000,
    val chunkSamples: Int = 10_240,
    val chunkQueueCapacity: Int = 8,
    val eouDebounceMillis: Long = 1_280L,
) {
    init {
        require(sampleRateHz > 0) { "sampleRateHz must be positive" }
        require(chunkSamples > 0) { "chunkSamples must be positive" }
        require(chunkQueueCapacity >= 1) { "chunkQueueCapacity must be at least 1" }
        require(eouDebounceMillis >= 0L) { "eouDebounceMillis must not be negative" }
    }

    companion object {
        fun default(): ParakeetEouSpeechToTextConfig = ParakeetEouSpeechToTextConfig()
    }
}

/** Throwable carrier so a typed error survives crossing a coroutine boundary. */
internal class VoiceAgentErrorCarrier(
    val error: VoiceAgentError,
) : Exception(error.code.name)

/**
 * The adapter-owned implementation of [EndOfUtteranceSignal].
 *
 * A confirmed EOU is debounced: repeated flags within
 * [ParakeetEouSpeechToTextConfig.eouDebounceMillis] of the last confirmation keep
 * the *latest* offset but do not create a new confirmation. The capture offset is
 * approximate (derived from the chunk count) and is used only for a bounded
 * window comparison by [EouTurnCompletionDetector].
 */
internal class ParakeetEouSignal : EndOfUtteranceSignal {
    private val state = MutableStateFlow(EouObservation(offsetMillis = NO_EOU, confidence = null, sequence = 0L))
    private val sequence = AtomicLong(0L)

    override val eouEvents: StateFlow<EouObservation> = state.asStateFlow()

    override fun lastEouOffsetMillis(): Long? {
        val value = state.value
        return if (value.offsetMillis == NO_EOU) null else value.offsetMillis
    }

    override fun consumeEou(): Boolean {
        val value = state.value
        if (value.offsetMillis == NO_EOU) return false
        state.value = EouObservation(offsetMillis = NO_EOU, confidence = null, sequence = sequence.incrementAndGet())
        return true
    }

    /** Records a model EOU, applying the sustained-run debounce. */
    fun confirmEou(
        offsetMillis: Long,
        confidence: Float?,
        debounceMillis: Long,
    ): Boolean {
        val previous = state.value
        if (previous.offsetMillis != NO_EOU && offsetMillis - previous.offsetMillis < debounceMillis) {
            // Same sustained EOU run: refresh the offset/confidence without a new
            // confirmation.
            state.value = previous.copy(offsetMillis = offsetMillis, confidence = confidence ?: previous.confidence)
            return false
        }
        state.value =
            EouObservation(
                offsetMillis = offsetMillis,
                confidence = confidence,
                sequence = sequence.incrementAndGet(),
            )
        return true
    }

    /** Clears the signal at the start of a new capture session. */
    fun resetCapture() {
        state.value = EouObservation(offsetMillis = NO_EOU, confidence = null, sequence = sequence.incrementAndGet())
    }

    /** Marks capture ended; the last confirmed offset remains readable. */
    fun markCaptureEnded() = Unit

    /** Marks the adapter closed; no further confirmation can arrive. */
    fun markClosed() {
        state.value = EouObservation(offsetMillis = NO_EOU, confidence = null, sequence = sequence.incrementAndGet())
    }

    private companion object {
        /** Sentinel: no confirmed EOU yet in this capture. */
        const val NO_EOU = Long.MIN_VALUE
    }
}
