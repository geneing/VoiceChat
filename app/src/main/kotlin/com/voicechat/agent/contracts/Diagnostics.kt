package com.voicechat.agent.contracts

import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId

/** Pipeline stage a diagnostic event belongs to. */
enum class DiagnosticStage {
    AUDIO_INPUT,
    SPEECH_TO_TEXT,
    TURN_DETECTION,
    LLM_REQUEST,
    TTS_SYNTHESIS,
    TTS_PLAYBACK,
    PERSISTENCE,

    /** Turn-level lifecycle (start, completion, barge-in), not one pipeline stage. */
    TURN,

    /** Trace infrastructure itself (for example an overflow marker). */
    TRACE,
}

/** Lifecycle point of a diagnostic event. */
enum class DiagnosticOutcome {
    STARTED,
    PROGRESS,
    COMPLETED,
    CANCELLED,
    FAILED,
}

/**
 * Allowed metadata keys for a diagnostic event.
 *
 * Using an enum rather than free-form keys keeps providers, credentials, and
 * transcript content out of the default trace: there is no key under which a
 * secret or raw content can be recorded by accident. Values are still expected
 * to be non-sensitive identifiers and counts.
 */
enum class DiagnosticAttribute {
    PROVIDER_ID,
    MODEL_ID,
    RUNTIME,
    ENGINE_ID,
    REASONING_LEVEL,
    ERROR_CODE,
    FRAME_COUNT,
    BYTE_COUNT,
    CHARACTER_COUNT,

    /** State of the request itself (for example selected, streaming, cancelled). */
    REQUEST_STATE,

    /** Which point of a stream the event marks (first text, a later delta). */
    STREAM_STATE,

    /** Zero-based index of a streamed delta within one request. */
    DELTA_INDEX,

    /** Assistant characters actually delivered to playback. */
    DELIVERED_CHARACTER_COUNT,

    /** Assistant characters generated for the utterance (delivered or not). */
    TOTAL_CHARACTER_COUNT,

    /** Set when an event is part of a barge-in transition. */
    BARGE_IN,
}

/**
 * One privacy-safe pipeline event.
 *
 * [monotonicTimeNanos] must come from a monotonic clock (never wall time) so
 * durations are unaffected by clock changes. [attributes] must not contain
 * credentials, raw audio, full prompts, or full transcripts.
 *
 * [traceId] is the correlation key for the turn; [turnId] links the trace to
 * the persisted conversation turn. Events with the same [traceId] describe one
 * request's latency story (see `docs/turn-tracing.md`).
 */
data class DiagnosticEvent(
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val monotonicTimeNanos: Long,
    val turnId: TurnId? = null,
    val durationNanos: Long? = null,
    val attributes: Map<DiagnosticAttribute, String> = emptyMap(),
    val traceId: TraceId? = null,
)

/**
 * Sink for [DiagnosticEvent]s.
 *
 * **Ownership and lifecycle.** The sink is app-scoped and injected; callers do
 * not start or stop it. [record] must be cheap and non-blocking so it can be
 * called from the audio and UI paths, and must never log or persist sensitive
 * content by default (see `docs/privacy-and-security.md`). When a bounded
 * implementation cannot accept an event it must count the drop and expose that
 * count rather than silently discarding it.
 */
interface DiagnosticsSink {
    /** Records one event. */
    fun record(event: DiagnosticEvent)
}

/** Sink that discards events; the safe default when no trace is configured. */
object NoOpDiagnosticsSink : DiagnosticsSink {
    override fun record(event: DiagnosticEvent) = Unit
}
