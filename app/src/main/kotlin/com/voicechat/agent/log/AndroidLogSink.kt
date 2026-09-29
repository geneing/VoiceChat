package com.voicechat.agent.log

import android.util.Log as AndroidLogUtil

/**
 * [LogSink] backed by `android.util.Log`.
 *
 * This is the only logging file that touches the platform; the rest of the
 * `log` package is pure Kotlin and runs as ordinary JVM unit tests. It holds no
 * state and never throws.
 */
object AndroidLogSink : LogSink {
    override fun log(
        level: LogLevel,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        when (level) {
            LogLevel.VERBOSE -> AndroidLogUtil.v(tag, message, throwable)
            LogLevel.DEBUG -> AndroidLogUtil.d(tag, message, throwable)
            LogLevel.INFO -> AndroidLogUtil.i(tag, message, throwable)
            LogLevel.WARN -> AndroidLogUtil.w(tag, message, throwable)
            LogLevel.ERROR -> AndroidLogUtil.e(tag, message, throwable)
        }
    }
}
