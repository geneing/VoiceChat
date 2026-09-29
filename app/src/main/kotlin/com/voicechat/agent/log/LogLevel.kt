package com.voicechat.agent.log

/**
 * Severity of a developer log line, from most to least verbose.
 *
 * The order is significant: [isEnabledAt] compares severity, so raising the
 * configured minimum level suppresses everything below it. This is deliberately
 * a small enum rather than a vendor type so the core logger is platform-free and
 * JVM-testable (`docs/logging.md`).
 */
enum class LogLevel {
    VERBOSE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    ;

    /** True when a message at this level is at least as severe as [threshold]. */
    fun isEnabledAt(threshold: LogLevel): Boolean = ordinal >= threshold.ordinal
}
