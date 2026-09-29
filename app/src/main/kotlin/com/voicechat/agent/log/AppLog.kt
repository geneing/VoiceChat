package com.voicechat.agent.log

import com.voicechat.agent.diagnostics.Redaction

/**
 * Tiny, release-safe developer logging facade.
 *
 * **Purpose.** The M04 `DiagnosticsSink` is the structured, correlated trace
 * seam; `AppLog` is the complementary free-text developer log for debugging a
 * turn end to end (`docs/logging.md`). It is not a replacement for the trace.
 *
 * **Lazy, allocation-free when disabled.** Every entry point is `inline` and
 * takes the message as a lambda. When logging is disabled the function returns
 * before the lambda runs, so a disabled logger costs a boolean check and
 * allocates nothing — no string is built, no varargs array, no `toString`. The
 * default state is **off**; the app boundary calls [AndroidLogging.install],
 * which enables it only when `BuildConfig.DEBUG` is true, so release builds stay
 * off.
 *
 * **Privacy.** Callers must never log secrets, credentials, raw audio, full
 * transcripts, prompts, or unredacted provider responses. Route any sensitive
 * string through [secret], which applies the M04 [Redaction] helper, and log
 * counts/codes/identities rather than content.
 *
 * **Thread safety.** The configuration fields are `@Volatile`; the facade is
 * safe to call from any thread, and a call never blocks on a sink.
 */
object AppLog {
    /** Default tag for app log lines. */
    const val TAG: String = "VoiceChat"

    @Volatile
    @PublishedApi
    internal var enabled: Boolean = false

    @Volatile
    @PublishedApi
    internal var minLevel: LogLevel = LogLevel.DEBUG

    @Volatile
    @PublishedApi
    internal var sink: LogSink = NoOpLogSink

    /** True when [minLevel] and above are currently emitted. */
    val isEnabled: Boolean get() = enabled

    /** The current minimum severity. */
    val activeMinLevel: LogLevel get() = minLevel

    /** The current destination. */
    val activeSink: LogSink get() = sink

    /**
     * Installs a [sink], enablement, and minimum [minLevel] in one call. Use this
     * at the app boundary instead of mutating the individual fields.
     */
    fun configure(
        sink: LogSink,
        enabled: Boolean,
        minLevel: LogLevel = LogLevel.DEBUG,
    ) {
        this.sink = sink
        this.minLevel = minLevel
        this.enabled = enabled
    }

    /** Turns logging on or off without changing the sink or level. */
    fun configure(enabled: Boolean) {
        this.enabled = enabled
    }

    /** Returns to the disabled, no-op default (useful in tests). */
    fun reset() {
        sink = NoOpLogSink
        minLevel = LogLevel.DEBUG
        enabled = false
    }

    /** Logs at [LogLevel.VERBOSE]. */
    inline fun v(
        throwable: Throwable? = null,
        message: () -> String,
    ) = logIfEnabled(LogLevel.VERBOSE, throwable, message)

    /** Logs at [LogLevel.DEBUG]. */
    inline fun d(
        throwable: Throwable? = null,
        message: () -> String,
    ) = logIfEnabled(LogLevel.DEBUG, throwable, message)

    /** Logs at [LogLevel.INFO]. */
    inline fun i(
        throwable: Throwable? = null,
        message: () -> String,
    ) = logIfEnabled(LogLevel.INFO, throwable, message)

    /** Logs at [LogLevel.WARN]. */
    inline fun w(
        throwable: Throwable? = null,
        message: () -> String,
    ) = logIfEnabled(LogLevel.WARN, throwable, message)

    /** Logs at [LogLevel.ERROR]. */
    inline fun e(
        throwable: Throwable? = null,
        message: () -> String,
    ) = logIfEnabled(LogLevel.ERROR, throwable, message)

    /**
     * Logs `label=[redacted]` for any non-empty [value] and `label=` for an
     * empty/null value, so a credential or other sensitive string is never
     * echoed. Use this instead of interpolating a sensitive value directly.
     */
    inline fun secret(
        level: LogLevel,
        label: String,
        value: String?,
        throwable: Throwable? = null,
    ) {
        if (!isLoggable(level)) return
        sink.log(level, TAG, "$label=${Redaction.redact(value)}", throwable)
    }

    @PublishedApi
    internal inline fun logIfEnabled(
        level: LogLevel,
        throwable: Throwable?,
        message: () -> String,
    ) {
        if (!isLoggable(level)) return
        sink.log(level, TAG, message(), throwable)
    }

    @PublishedApi
    internal fun isLoggable(level: LogLevel): Boolean = enabled && level.isEnabledAt(minLevel)
}
