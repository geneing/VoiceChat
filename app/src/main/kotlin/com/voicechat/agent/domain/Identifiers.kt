package com.voicechat.agent.domain

// Stable, platform-free identifiers for the conversation domain.
//
// These are deliberately string-backed value classes rather than a vendor or
// platform type (for example an Android `UUID`), so the domain layer can be
// tested and persisted without pulling in `android.*`. Persistence maps them to
// their String value; orchestration generates the values.

/** Identifies one stored conversation. */
@JvmInline
value class ConversationId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ConversationId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies one user or assistant turn within a conversation. */
@JvmInline
value class TurnId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "TurnId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies an LLM provider independently from the model it serves. */
@JvmInline
value class ProviderId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ProviderId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies a model offered by a provider or an on-device runtime. */
@JvmInline
value class ModelId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "ModelId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies an on-device engine (for example a specific STT or TTS engine). */
@JvmInline
value class EngineId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "EngineId must not be blank" }
    }

    override fun toString(): String = value
}

/** Identifies one unit of speech submitted to text-to-speech playback. */
@JvmInline
value class UtteranceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "UtteranceId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Identifies one trace that correlates every diagnostic event for a
 * conversation turn.
 *
 * A trace is deliberately separate from [TurnId]: a turn may be retried or a
 * trace may span adjacent pipeline work, but all events that describe one
 * latency story share a [TraceId] (see `docs/turn-tracing.md`).
 */
@JvmInline
value class TraceId(
    val value: String,
) {
    init {
        require(value.isNotBlank()) { "TraceId must not be blank" }
    }

    override fun toString(): String = value
}
