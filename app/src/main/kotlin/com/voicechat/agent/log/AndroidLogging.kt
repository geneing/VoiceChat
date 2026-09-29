package com.voicechat.agent.log

import com.voicechat.agent.BuildConfig

/**
 * App-boundary installation of developer logging.
 *
 * Enablement is derived from [BuildConfig.DEBUG]: a debug build logs to
 * `android.util.Log`, and a release build leaves [AppLog] disabled, so no sink
 * is called and no message lambda is evaluated. This is the "off in release"
 * guarantee `docs/logging.md` documents and a JVM test proves for the core.
 *
 * Call [install] once, before other components run: [VoiceChatApplication] does
 * this in `onCreate`. Production enablement follows the build type; a
 * device/debug run can raise the level or swap the sink afterwards through
 * [AppLog].
 */
object AndroidLogging {
    /**
     * Installs the `android.util.Log` sink and enables logging only when
     * [debug] is true. The default reads the generated [BuildConfig].
     */
    fun install(
        debug: Boolean = BuildConfig.DEBUG,
        minLevel: LogLevel = LogLevel.DEBUG,
    ) {
        AppLog.configure(sink = AndroidLogSink, enabled = debug, minLevel = minLevel)
    }
}
