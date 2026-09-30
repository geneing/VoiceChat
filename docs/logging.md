# Developer Logging

This document describes the release-safe developer logging facility in
`com.voicechat.agent.log`. It is the free-text companion to the M04 structured
turn trace ([turn-tracing.md](./turn-tracing.md)): the trace is the correlated,
typed seam that orchestration and the viewer consume, and `AppLog` is the
human-readable line you read in logcat while debugging a turn end to end. They
are deliberately separate; the logger is **not** a replacement for diagnostics.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/log/
  LogLevel.kt          VERBOSE/DEBUG/INFO/WARN/ERROR + severity comparison
  LogSink.kt           LogSink fun interface + NoOpLogSink
  RecordingLogSink.kt  in-memory sink for tests and debug tooling
  AppLog.kt            the facade: lazy inline entry points, redaction hook
  AndroidLogSink.kt    android.util.Log-backed sink (platform only here)
  AndroidLogging.kt    app-boundary install; enablement from BuildConfig.DEBUG
```

The four platform-free files are covered by `LogSourcePurityTest`; only
`AndroidLogSink` and `AndroidLogging` import `android.*`, so the facade's behavior
is exercised by ordinary JVM tests (`AppLogTest`). `VoiceChatApplication.onCreate`
calls `AndroidLogging.install()` before any component runs.

## Levels and enablement

| Level | Use |
| --- | --- |
| `VERBOSE` | Very chatty tracing; off by default even in debug. |
| `DEBUG` | Developer detail: session start/stop, state transitions, counts. |
| `INFO` | Notable lifecycle facts (capture started, model status, repository open). |
| `WARN` | Recoverable problems (permission revoked, buffer overflow, persistence retry). |
| `ERROR` | Failures with a stable code (and optionally a cause). |

- **Off by default, off in release.** `AppLog` starts disabled with a
  `NoOpLogSink`. `AndroidLogging.install(debug = BuildConfig.DEBUG)` enables the
  `android.util.Log` sink only for debug builds, so a release build never calls a
  sink and never builds a message. `buildFeatures.buildConfig = true` in
  `app/build.gradle.kts` generates `BuildConfig.DEBUG`.
- **Minimum level.** The default minimum is `DEBUG`; `VERBOSE` is available but
  suppressed unless raised. Raising the level is a one-line change at the app
  boundary (`AndroidLogging.install(minLevel = LogLevel.VERBOSE)`) or, for a
  one-off debug session, via `AppLog.configure(...)`.
- **No blocking.** A sink is called synchronously only after the enabled check;
  sinks must be cheap and must not throw. Nothing on the audio or UI path blocks
  on a log call.

## Lazy, allocation-free when disabled

Every entry point is `inline` and takes the message as a lambda:

```kotlin
AppLog.d { "capture: stopped frames=$frames drops=$drops" }
```

When logging is disabled, `d` returns before evaluating the lambda, so the
interpolated string is never built and nothing is allocated. This is the cost
control that keeps the logger free in release (`AppLogTest` proves it: a disabled
logger records no events and never invokes the message lambda).

## Redaction and privacy

Never log secrets, credentials, raw audio, full transcripts, prompts, or
unredacted provider responses. Two rules enforce it:

- Log **identities, codes, and counts**, not content: engine/model IDs, error
  codes, frame/character counts, route kinds, state names. Strings like a
  transcript's length are fine; the transcript text is not.
- Route any sensitive string through `AppLog.secret(level, label, value)`, which
  applies the M04 `Redaction` helper and emits `label=[redacted]` for any
  non-empty value. A credential is never echoed.

`AppLogTest` proves redaction applies on the log path and that the secret is
absent from every captured line. The structured trace remains content-free by
construction; the logger relies on these rules and on review.

## What is instrumented today

| Area | Examples |
| --- | --- |
| Capture (M07) | session start (source/format/route), stop with frame/drop counts, permission denial/revocation, read errors, route changes, audio focus acquire/loss/abandon. |
| STT (M08) | availability result, start/refuse-to-start, input-end stop, session end with outcome/frame counts, model download progress/failure. |
| TTS (M11) | engine availability (voice counts, selected embedded voice id, resolved engine), synthesis start/failure by character count, playback first-audible/completed/interrupted with delivered-vs-total counts and route kind, immediate stop, no-on-device-voice refusal. Never assistant text. |
| Persistence (M05) | repository open, per-operation load/save/delete success/failure, process-death recovery. |
| Credentials (M13) | store/replace/remove by provider id and kind and the failure reason by exception *type* only; never the credential value. `Credential.toString()` is redacted and `CredentialRedactionTest` proves the path stays clean. |
| UI (M06) | conversation open/send/cancel/retry/delete, generation start/terminal state, storage failure. Never transcript text. |
| LLM contract (M12) | request start (provider/model identity, message and character counts, reasoning level, streaming capability), one line per fake/adapter emission (event kind, index, character count), and stream end (completed, delta count, character count, terminal kind, failure reason, whether usage was reported, provider-reported model). Never prompt, delta, or response text; `LlmStreamLoggingTest` proves the content-free rule. |
| Diagnostics (M04) | bounded sink start and a one-time overflow warning. |

Logging is intentionally not per audio frame and not per UI recomposition.

## Turning it on for a debug or device run

1. Build and install the **debug** variant (debug logging is enabled
   automatically):

   ```powershell
   $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
   .\gradlew.bat :app:installDebug
   ```

2. Watch the `VoiceChat` tag:

   ```powershell
   adb logcat -s VoiceChat:V
   ```

3. For maximum detail, raise the level (temporary, debug-only) by passing
   `minLevel = LogLevel.VERBOSE` to `AndroidLogging.install` in
   `VoiceChatApplication`, or in a debug-only code path call
   `AppLog.configure(sink = AndroidLogSink, enabled = true, minLevel = LogLevel.VERBOSE)`.

## Confirming it is off in release

- By construction the core defaults to disabled and the Android install derives
  enablement from `BuildConfig.DEBUG`; a release build leaves `AppLog` disabled.
- To verify on a release build, run a release install and observe that no
  `VoiceChat` tag lines appear:

  ```powershell
  adb logcat -s VoiceChat:V
  ```

  (A release build also runs with `BuildConfig.DEBUG == false`, so
  `AndroidLogging.install()` is a no-op.) `AppLogTest` proves the behavior at the
  unit level so release silence does not depend on a device run.

## Relationship to the M04 trace

The M04 `DiagnosticsSink` stays the structured, correlated seam: it is what a
trace viewer or exporter reads, and what orchestration uses for timing. `AppLog`
adds developer text so a human can follow a turn in logcat. New diagnostics
should be recorded through the typed `DiagnosticEvent` model; `AppLog` is for the
prose around it.
