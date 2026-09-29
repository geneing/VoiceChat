package com.voicechat.agent.log

import com.voicechat.agent.diagnostics.Redaction
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the two guarantees the release-safe logging facade must keep:
 *
 * 1. a disabled logger produces **no** events and does **not** evaluate the
 *    message lambda, so it allocates nothing and costs a boolean check; and
 * 2. a sensitive value routed through [AppLog.secret] is redacted on the log
 *    path and never reaches the sink.
 *
 * `AppLog` is app-global state, so every test resets it afterwards.
 */
class AppLogTest {
    private val secret = "sk-live-DO-NOT-LEAK-0123456789"

    @After
    fun tearDown() {
        AppLog.reset()
    }

    @Test
    fun disabledLoggerProducesNoEventsAndNeverBuildsAMessage() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = false)
        var invocations = 0

        AppLog.v {
            invocations++
            "verbose"
        }
        AppLog.d {
            invocations++
            "debug"
        }
        AppLog.i {
            invocations++
            "info"
        }
        AppLog.w {
            invocations++
            "warn"
        }
        AppLog.e {
            invocations++
            "error"
        }
        AppLog.secret(LogLevel.INFO, "apiKey", secret)

        assertEquals("the message lambda must not run when logging is off", 0, invocations)
        assertTrue("a disabled logger must not touch the sink", sink.records.isEmpty())
    }

    @Test
    fun enabledLoggerRecordsTheLazilyBuiltMessageAtEveryLevel() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)

        AppLog.v { "verbose" }
        AppLog.d { "debug" }
        AppLog.i { "info" }
        AppLog.w { "warn" }
        AppLog.e { "error" }

        assertEquals(
            listOf("verbose", "debug", "info", "warn", "error"),
            sink.records.map { it.message },
        )
        assertEquals(
            listOf(LogLevel.VERBOSE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR),
            sink.records.map { it.level },
        )
        assertTrue(sink.records.all { it.tag == AppLog.TAG })
    }

    @Test
    fun aLevelBelowTheThresholdIsSuppressedWithoutEvaluatingTheLambda() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.WARN)
        var invocations = 0

        AppLog.d {
            invocations++
            "debug"
        }
        AppLog.i {
            invocations++
            "info"
        }
        AppLog.w {
            invocations++
            "warn"
        }

        assertEquals("suppressed levels must not build their message", 1, invocations)
        assertEquals(listOf("warn"), sink.records.map { it.message })
    }

    @Test
    fun aThrowableIsForwardedToTheSink() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = true)
        val cause = IllegalStateException("boom")

        AppLog.e(cause) { "failed" }

        assertEquals(cause, sink.records.single().throwable)
    }

    @Test
    fun secretIsRedactedOnTheLogPathAndNeverEchoed() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = true)

        AppLog.secret(LogLevel.INFO, "apiKey", secret)

        val message = sink.records.single().message
        assertEquals("apiKey=${Redaction.PLACEHOLDER}", message)
        assertFalse(message.contains(secret))
        assertFalse(sink.messages.any { it.contains(secret) })
    }

    @Test
    fun anEmptySecretRendersNoPlaceholder() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = true)

        AppLog.secret(LogLevel.DEBUG, "token", "")
        AppLog.secret(LogLevel.DEBUG, "token", null)

        assertEquals(listOf("token=", "token="), sink.messages)
    }

    @Test
    fun aDisabledSecretIsNotEvaluatedIntoTheSink() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = false)

        AppLog.secret(LogLevel.ERROR, "password", secret)

        assertTrue(sink.records.isEmpty())
    }

    @Test
    fun resetReturnsToTheDisabledNoOpDefault() {
        val sink = RecordingLogSink()
        AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)

        AppLog.reset()

        assertSame(NoOpLogSink, AppLog.activeSink)
        assertFalse(AppLog.isEnabled)
        assertEquals(LogLevel.DEBUG, AppLog.activeMinLevel)
    }

    @Test
    fun levelThresholdsCompareBySeverity() {
        assertTrue(LogLevel.ERROR.isEnabledAt(LogLevel.DEBUG))
        assertTrue(LogLevel.WARN.isEnabledAt(LogLevel.WARN))
        assertFalse(LogLevel.DEBUG.isEnabledAt(LogLevel.WARN))
        assertFalse(LogLevel.VERBOSE.isEnabledAt(LogLevel.DEBUG))
    }
}
