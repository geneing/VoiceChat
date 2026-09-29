package com.voicechat.agent.log

/**
 * Destination for formatted developer log lines.
 *
 * The sink receives an already-formatted [message]; enablement and message
 * construction are decided by [AppLog] before a sink is ever called, so a
 * disabled logger allocates nothing and touches no sink. Implementations must be
 * cheap, must not throw, and must never be handed secrets, raw audio, full
 * transcripts, prompts, or unredacted provider responses — redact those first
 * (see [AppLog.secret] and `docs/logging.md`).
 */
fun interface LogSink {
    /** Writes one formatted line; [throwable] may be null. */
    fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    )
}

/** Sink that discards everything; the safe default when logging is disabled. */
object NoOpLogSink : LogSink {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) = Unit
}
