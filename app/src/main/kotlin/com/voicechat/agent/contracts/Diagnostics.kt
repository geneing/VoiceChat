package com.voicechat.agent.contracts

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
}

/**
 * One privacy-safe pipeline event.
 *
 * [monotonicTimeNanos] must come from a monotonic clock (never wall time) so
 * durations are unaffected by clock changes. [attributes] must not contain
 * credentials, raw audio, full prompts, or full transcripts.
 */
data class DiagnosticEvent(
    val stage: DiagnosticStage,
    val outcome: DiagnosticOutcome,
    val monotonicTimeNanos: Long,
    val turnId: TurnId? = null,
    val durationNanos: Long? = null,
    val attributes: Map<DiagnosticAttribute, String> = emptyMap(),
)

/**
 * Sink for [DiagnosticEvent]s.
 *
 * **Ownership and lifecycle.** The sink is app-scoped and injected; callers do
 * not start or stop it. [record] must be cheap and non-blocking so it can be
 * called from the audio and UI paths, and must never log or persist sensitive
 * content by default (see `docs/privacy-and-security.md`).
 */
interface DiagnosticsSink {
    /** Records one event. */
    fun record(event: DiagnosticEvent)
}

/** Sink that discards events; the safe default when no trace is configured. */
object NoOpDiagnosticsSink : DiagnosticsSink {
    override fun record(event: DiagnosticEvent) = Unit
}
