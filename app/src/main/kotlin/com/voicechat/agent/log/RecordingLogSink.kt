package com.voicechat.agent.log

/**
 * [LogSink] that keeps every line in memory so tests (and a debug tool) can
 * assert exactly what was logged.
 *
 * It is intentionally part of the facility rather than a test-only fake: the same
 * recording shape is useful for a debug run that wants to inspect log output in
 * process. It retains formatted lines only; callers are still responsible for
 * redacting before they log.
 */
class RecordingLogSink : LogSink {
    /** One captured log line. */
    data class Record(
        val level: LogLevel,
        val tag: String,
        val message: String,
        val throwable: Throwable? = null,
    )

    private val captured = mutableListOf<Record>()

    /** Snapshot of captured lines in call order. */
    val records: List<Record> get() = captured.toList()

    /** Captured messages only, in call order. */
    val messages: List<String> get() = captured.map { it.message }

    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        captured += Record(level, tag, message, throwable)
    }

    /** Drops every captured line; useful between assertions. */
    fun clear() {
        captured.clear()
    }
}
