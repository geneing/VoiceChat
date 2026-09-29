# Audio Capture

This document describes the M07 microphone capture path: how permission is
requested, how `AudioRecord` is negotiated and run, how the capture lifecycle is
cleaned up, and what privacy-safe diagnostics it emits. It complements
[architecture](./architecture.md#component-boundaries),
[voice quality and latency](./voice-quality-and-latency.md),
[device notes](./android-device-notes.md), and
[privacy and security](./privacy-and-security.md).

M07 implements capture only. Voice-activity detection (M09), on-device STT
(M08), TTS (M11), and the integrated voice loop (M24) build on this seam.

## Scope

- Permission request at the point of use, capture start/stop, format
  negotiation, route reporting, audio-focus and lifecycle cleanup, and typed
  error states.
- Capture on a dedicated non-UI execution path, emitting bounded 16 kHz mono
  16-bit PCM `AudioFrame`s through the M02 `AudioInput` contract.
- Privacy-safe capture diagnostics (format, source, route kind, normalized
  levels, clipping and drop counts) through the M04 diagnostics seam, with no
  raw audio retained.
- `android.permission.RECORD_AUDIO`, declared because capture now exists and
  requested at the moment capture is wanted, never at app start.

M07 does **not** add a voice UI button, VAD, STT, AGC/noise suppression, or a
foreground service.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/
  contracts/Diagnostics.kt          + AUDIO_* diagnostic attributes (additive)
  audio/
    AudioRoute.kt                   privacy-safe route kind + description
    AudioRouteMonitor.kt            AudioManager device-callback route monitor
    AudioFocusController.kt         transient audio focus for a capture session
    MicrophonePermission.kt         RECORD_AUDIO check behind an interface
    MicrophonePermissionRequest.kt  Compose point-of-use request controller
    PcmRecorder.kt                  capture config + PcmRecorderEngine/Factory
    AndroidPcmRecorder.kt           AudioRecord factory + format negotiation
    CaptureLevelAccumulator.kt      peak / RMS / clipping statistics
    AudioCaptureDiagnostics.kt      AUDIO_INPUT events on the M04 sink
    MicrophoneAudioInput.kt         the AudioInput implementation
    MicrophoneAudioCapture.kt       app-boundary factory wiring the above
    CaptureLifecycle.kt             ON_STOP teardown helper
app/src/test/kotlin/com/voicechat/agent/
  fake/AudioCaptureFakes.kt         fake engine, permission, route, focus
  audio/                            JVM + Robolectric capture tests
```

`MicrophoneAudioInput` is the physical counterpart of
`replay/ReplayAudioInput`; both are `AudioInput`s that emit
`AudioFormat.MONO_16_KHZ` frames, so STT, VAD, and turn detection cannot tell a
fixture from a live microphone.

## Capture path

1. Request the permission at the point of use with
   `rememberMicrophonePermissionController()`; the caller starts capture only
   after `MicrophonePermissionStatus.GRANTED`.
2. `MicrophoneAudioInput.frames()` is a cold flow: each collection is one
   capture session. `MicrophoneAudioCapture.create(context, …)` builds the real
   path (platform permission check, `AndroidPcmRecorderFactory`,
   `AndroidAudioRouteMonitor`, `AndroidAudioFocusController`).
3. `AndroidPcmRecorderFactory.create()` re-checks `RECORD_AUDIO`, negotiates
   `AudioRecord.getMinBufferSize(16000, CHANNEL_IN_MONO, ENCODING_PCM_16BIT)`,
   and throws a typed `AUDIO_DEVICE_UNAVAILABLE` when 16 kHz mono PCM is not
   available instead of recording at another rate. Capture uses
   `MediaRecorder.AudioSource.VOICE_RECOGNITION` with no app-supplied gain, AGC,
   or noise suppression.
4. The capture loop reads fixed-size frames (20 ms / 320 samples by default),
   copies each block into an `AudioFrame`, and offers it to a bounded buffer.
5. Completion, collector cancellation, and read failure all release the
   recorder and audio focus in a `finally` block. `close()` is idempotent,
   marks the input unusable, and stops an in-flight session to unblock a pending
   read.

## Threading and backpressure

- The whole session runs on an injected `CoroutineDispatcher` (default
  `Dispatchers.IO`), never the main thread. A test asserts every recorder call
  runs on the capture dispatcher.
- The frame buffer is bounded. If a consumer cannot keep up, frames are dropped
  and counted in diagnostics rather than blocking the reader indefinitely or
  letting the hardware buffer overflow invisibly.
- Capture is structured-concurrency based: cancelling the collector cancels the
  session, and `close()`/lifecycle teardown leave no recorder behind.

## Diagnostics

Capture events use the existing M04 `DiagnosticsSink` and `DiagnosticEvent`
types with `DiagnosticStage.AUDIO_INPUT`; there is no parallel tracing
mechanism. M07 adds these `DiagnosticAttribute` values (an additive contract
change):

| Attribute | Meaning |
| --- | --- |
| `AUDIO_SOURCE` | Platform source label (for example `VOICE_RECOGNITION`). |
| `AUDIO_FORMAT` | Negotiated format, for example `16000Hz/mono/16bit`. |
| `AUDIO_ROUTE` | Input route *kind* only (`BUILTIN_MIC`, `BLUETOOTH_SCO`, …). |
| `AUDIO_PEAK_LEVEL` | Peak sample magnitude, normalized to `[0, 1]`. |
| `AUDIO_RMS_LEVEL` | RMS magnitude, normalized to `[0, 1]`. |
| `AUDIO_CLIPPED_SAMPLES` | Samples at or near full scale. |
| `AUDIO_DROPPED_FRAMES` | Frames dropped because the consumer lagged. |

A session emits `STARTED` with the format/source/route, periodic `PROGRESS`
level/clip/drop samples, `COMPLETED`/`CANCELLED` totals, and `FAILED` with only
the stable error code. Sample values and raw audio are never retained or
emitted. Route changes emit `PROGRESS` with the new kind and are de-duplicated.
Because a capture session can span turns, capture events carry no trace/turn ID
unless one is supplied at construction; a future orchestrator can pass the
active turn's IDs.

## Tests

JVM and Robolectric tests cover:

- start/stop, frame contents, typed start/read/create failures, permission
  denial, and mid-capture revocation (`MicrophoneAudioInputTest`, 13 tests);
- route classification and selection, level/clipping statistics, format
  negotiation, source/focus mapping, and expiry of diagnostics
  (`AudioRouteTest`, `CaptureLevelsTest`, `RecorderNegotiationTest`,
  `AudioCaptureDiagnosticsTest`);
- recorder cleanup after normal completion and cancellation, and off-main-thread
  I/O (`MicrophoneAudioInputTest`);
- Android permission grant/revocation and the factory's permission guard
  (`AndroidMicrophonePermissionTest`, `AndroidPcmRecorderFactoryTest`),
  lifecycle `ON_STOP` teardown (`CaptureLifecycleTest`), and the route monitor
  degrading gracefully (`AndroidAudioRouteMonitorTest`);
- frame compatibility: replay and capture frames fed through the same consumer,
  including the real `ReplaySpeechToText` adapter
  (`CaptureReplayFrameCompatibilityTest`).

## Manual Pixel 10 checks (no automation)

Device/hardware behavior cannot be validated on the JVM. These remain manual
runs and must record device, OS, source, route, and condition:

- Real capture level, noise floor, and clipping by route (built-in mic, wired
  headset, Bluetooth); decide whether any gain/AEC/NS is warranted from measured
  data. Do not copy GVP's gain or RMS thresholds.
- Permission denial and revocation in system settings while the app is open.
- Route changes during capture (plug/unplug a headset) and capture continuity.
- Audio focus interaction with playback once TTS exists (M11/M24).
- No leaked `AudioRecord` across repeated start/stop and backgrounding, observed
  via `dumpsys media.audio_flinger` / `dumpsys audio`.

## Unresolved decisions

- Focus policy (transient vs. none, and whether focus loss should stop capture)
  is provisional until TTS and barge-in exist (M11/M24).
- Capture once covers multiple turns; per-turn trace correlation is deferred to
  the orchestrator (M21).
- Whether any platform AEC/NS source variant improves STT on Pixel 10 is
  deferred to M08/M25 measurement.
