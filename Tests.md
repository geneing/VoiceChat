# On-Device Testing on Pixel 10

This is the single place that documents how to test this app **on the physical
device**. Everything here requires a real Pixel 10; the JVM unit tests
(`:app:testDebugUnitTest`), Robolectric tests, and the compile-only instrumented
build are not on-device runs and are covered by [docs/validation.md](./docs/validation.md).

Later milestone agents **append** their own section under
[Later milestones](#later-milestones-extend-here) rather than editing another
milestone's rows. Keep the exact command, the recorded conditions, and the real
outcome; never mark a check passed unless it was run.

## Prerequisites

- **Pixel 10** with a **locked bootloader**. ML Kit GenAI Speech Recognition
  (M08) does not support unlocked bootloaders.
- Stock Android with **AICore present and updated**, signed in and online at
  least once so AICore can fetch its configuration. For STT **Advanced** mode the
  device must be Pixel 10/11; **Basic** mode works from API 31.
- **USB debugging** enabled; the device authorized for `adb`.
- Android SDK on the host with `ANDROID_HOME` set for every Gradle call:

  ```powershell
  $env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
  ```

- A working microphone and speaker. Most automated checks work with ambient
  silence; the transcription checks need audible speech (a human, or the debug
  probe's TTS loopback described below).
- For the STT probe, the `debug` source set's `SttProbeActivity` (a debug-only
  manual probe). See [Debug probe](#debug-probe-m08-stt) if it is present in the
  current checkout.

## Commands

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Confirm the device is attached
adb devices

# Install the debug build on the device
.\gradlew.bat :app:installDebug

# Compile the instrumented tests WITHOUT a device (host check)
.\gradlew.bat :app:assembleDebugAndroidTest

# Run the instrumented tests ON the device
.\gradlew.bat :app:connectedDebugAndroidTest
```

`connectedDebugAndroidTest` launches `AndroidJUnitRunner`. A single class can be
run with `-Pandroid.testInstrumentationRunnerArguments.class=...`, for example:

```powershell
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.voicechat.agent.audio.MicrophoneCaptureInstrumentedTest"
```

Watch developer logs (debug builds only; see [docs/logging.md](./docs/logging.md)):

```powershell
adb logcat -s VoiceChat:V
```

## Automated on-device tests

These live under `app/src/androidTest/` and are run by
`connectedDebugAndroidTest`. They use the real permission and the real engines;
they do not fake availability, and none requires a human speaker.

| Class | What it asserts |
| --- | --- |
| `audio.MicrophoneCaptureInstrumentedTest` | Without `RECORD_AUDIO`, capture refuses with `AUDIO_PERMISSION_DENIED`; a closed input refuses with `AUDIO_DEVICE_UNAVAILABLE`; with the permission granted a session emits 16 kHz mono frames and a second session can start after the first stops (no busy recorder). |
| `audio.VadCaptureInstrumentedTest` | Live M07 capture feeds the real M09 `EnergyVoiceActivityDetector` and `BoundedTurnEndpointPolicy`: a bounded window of real audio produces typed events without failing, and the policy emits at least one endpoint with a non-negative offset. It is a runs-and-does-not-crash smoke check, not a quality measurement. |
| `stt.MlKitSttInstrumentedTest` | Both catalog modes return a typed `SttAvailability` without throwing; the adapter reports the single engine ID; and, when the engine is **not** ready, the adapter refuses to run and emits a typed `STT_MODEL_NOT_READY`/`STT_UNAVAILABLE` failure. When the engine is ready this case is skipped (`Assume`), never faked. |
| `ui.ConversationAppInstrumentedTest` | The app launches on device and renders the conversation list (Room + Compose smoke). |

## M07 — Microphone capture

Run `MicrophoneCaptureInstrumentedTest` first, then the manual checks.

What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit.
- Capture source (`VOICE_RECOGNITION`), negotiated format (16 kHz mono 16-bit),
  and the active route kind at start.
- For each manual check below: exact steps, observed result, and whether it
  passed.

| Check | How | Record |
| --- | --- | --- |
| Permission denial | Revoke `RECORD_AUDIO` in Settings → Apps → VoiceChat → Permissions, then start capture. | App shows an explicit permission state and does not crash; no recorder is created. |
| Permission revocation mid-capture | Start capture, then revoke the permission while it runs. | Capture ends with `AUDIO_PERMISSION_DENIED`; remaining audio is not silently kept. |
| Route change | Plug/unplug a wired headset and connect/disconnect Bluetooth while capturing. | The route kind changes; capture continues or reports a typed failure; no crash. |
| Lifecycle cleanup | Background the app (`ON_STOP`) while capturing. | Capture stops; focus is abandoned; foregrounding does not leave a second recorder running. |
| No leaked `AudioRecord` | Repeat start/stop five or more times, then inspect `adb shell dumpsys media.audio_flinger` (or `dumpsys audio`). | No accumulating input records from `com.voicechat.agent`. |
| Capture level / noise floor | Record a few seconds of speech and of silence per route. | Peak/RMS ranges per route for later calibration; do **not** copy gain or RMS values from another project. |

## M08 — On-device speech-to-text

Engine: ML Kit GenAI Speech Recognition `1.0.0-alpha1`, Advanced preferred over
Basic. There is no cloud fallback and no second engine.

> **Real-time pacing rule.** The engine requires audio delivered to its file
> descriptor at a real-time rate (about 16,000 samples / 32 KB per second).
> Full-speed file-backed descriptors are **not** supported, so the M03 replay
> harness (which reads as fast as possible) must never be connected to the real
> engine. On-device STT checks must use live capture or an explicitly
> real-time-paced feeder.

What to record for every run:

- Device model/build, Android version, AICore version, bootloader state.
- Engine artifact version, mode (Basic/Advanced), and `modelId`.
- `checkStatus()` / `MlKitSttStatus.check()` result before and after
  provisioning (`Ready` / `DownloadRequired` / `Downloading` / `Unavailable` and
  the reason).
- Audio path (16 kHz mono 16-bit PCM → `AudioSource.fromPfd`) and that no
  preprocessing (gain/AEC/denoise/resample) was applied.
- Spoken phrase vs. live partials vs. the final transcript, and the network
  state (online vs. airplane mode).

| Check | How | Record |
| --- | --- | --- |
| Runtime status | Call `MlKitSttStatus.check()` for both catalog modes (the instrumented test logs the result). | Typed state per mode; never report a mode ready unless it is `AVAILABLE`. |
| Provisioning | If a mode is `DOWNLOADABLE`/`DOWNLOADING`, wait (or trigger the supported `download()`) and re-check. | Progress and the resulting state; the model is system-managed. |
| On-device transcription | Speak fixed phrases covering a number, a name, a negation, a disfluency, and a correction through live capture; read partials and the final transcript. | Raw partials and final text; confirm the raw text is preserved for user review. |
| Airplane-mode proof | After provisioning, enable airplane mode and repeat a transcription. | Transcription still succeeds, proving it is on-device (no cloud STT). |
| Finalization | Stop speaking / stop capture and observe the final transcript. | Exactly one final transcript; a stop-induced `ErrorResponse(errorCode = 0)` must not turn a good transcript into a failure. |
| Unavailable path | Deny/revoke the permission, and where available use a device with an unlocked bootloader or without AICore. | Explicit unavailable state; the manual text composer stays usable. |

Genuinely manual and **not** automated: live transcription quality (needs a
speaker), and the airplane-mode proof. Do not claim these from the JVM tests.

## M09 — VAD, fast onset, and bounded endpointing

Path: measured-audio VAD (normalized frame RMS + zero-crossing rate) behind the
M02 `VoiceActivityDetector`, plus the bounded VAD-only
`BoundedTurnEndpointPolicy` with a configurable maximum-silence cap. No VAD
model is used (see [docs/vad-endpointing.md](./docs/vad-endpointing.md)).

Run the JVM replay suite first; it proves exactly one endpoint per logical turn,
pause/resume keeping one turn, the silence cap, and empty/no-speech. Then run the
compile-only instrumented build, and on the device the M09 instrumented test:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device)
.\gradlew.bat :app:testDebugUnitTest

# Compile the instrumented test WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest

# Run the M09 instrumented test ON the device
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.voicechat.agent.audio.VadCaptureInstrumentedTest"
```

`VadCaptureInstrumentedTest` only proves live capture feeds the detector and the
policy terminates. Onset latency, false endpoints/holds, the right silence cap,
and per-route behavior are **manual measurements** below; never claim them from
the JVM or smoke tests.

What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit.
- Active input route kind (built-in mic, wired headset, Bluetooth) and the
  capture source/format (16 kHz mono 16-bit).
- The `VadConfig` in effect (route-aware onset/hangover RMS, zero-crossing rate,
  onset/pause frames, maximum-silence cap) if it was changed from the default.
- For each check: exact steps, the spoken/played phrase, observed events
  (`SPEECH_STARTED` / `CANDIDATE_PAUSE` / `SPEECH_RESUMED` / `HELD` /
  `ENDPOINTED`), and whether it passed.

| Check | How | Record |
| --- | --- | --- |
| Onset latency | Speak a single word and read the `SPEECH_STARTED` offset (from capture start or from the first audible word). | Onset delay per route; confirm it is low enough for barge-in and does not need a fixed grace period. |
| False endpoint (false commit) | Speak a phrase with a natural internal pause and keep going. | No `ENDPOINTED` before the phrase ends; the pause appears as `CANDIDATE_PAUSE` + `HELD`, then `SPEECH_RESUMED`. |
| False hold | Speak a complete short phrase and stop. | Exactly one `ENDPOINTED` within the configured maximum-silence cap after speech ends. |
| Prolonged silence | Stay silent for well beyond the cap after a phrase. | Exactly one `ENDPOINTED(SILENCE_CAP)`; the turn does not hang open. |
| Exactly one endpoint per turn | Repeat the above and count `ENDPOINTED` events per logical turn. | Exactly one final endpoint per turn; resumed speech after a committed endpoint is a new turn. |
| Empty/no-speech | Start capture, make no sound, stop. | One explicit `ENDPOINTED(EMPTY_NO_SPEECH)`; no committed utterance. |
| Route/conditions | Repeat onset and endpoint checks on built-in mic, wired headset, and Bluetooth, and with speaker playback/echo, road/wind noise, and music playing. | Per-route onset latency and false endpoint/hold counts; do not copy threshold values from another project. |
| Resources | Observe CPU/memory while endpointing runs with STT. | Sustained CPU/memory for the detector; flag if it competes with STT/TTS. |

CPU/memory and route-specific latency numbers are unmeasured until this runs
(R-0057, R-0059). This section is a checklist, not a result: **do not mark any
row passed unless it was run on the device.**

## Manual checklist that cannot be automated

- Audio levels, noise floor, clipping, and route behavior on real hardware
  (built-in mic, wired headset, Bluetooth).
- Permission grant/denial/revocation performed in system settings while the app
  is running.
- Physical route changes (plug/unplug, Bluetooth) during capture.
- Leak inspection via `dumpsys` across repeated start/stop and backgrounding.
- Real speech recognition quality and accent/disfluency behavior.
- Airplane-mode on-device proof.
- (Later) first-audible TTS timing, barge-in stop latency, and echo/noise
  false-interruption behavior.

## Debug probe (M08 STT)

When present in the checkout, the debug-only `SttProbeActivity`
(`app/src/debug/...`) is the smallest repeatable way to probe the engine
unattended: it calls `MlKitSttStatus.check()`/`download()`, drives the real
M07 capture → M08 adapter path, and runs the raw recognizer to show
per-segment final text and flow-completion behavior. Because no human is
available, it can synthesize phrases with the platform `TextToSpeech` API and
capture the speaker output through the microphone (**synthetic** speech, not a
human run; keep synthetic and human results separate, per
[docs/validation.md](./docs/validation.md)).

Example invocation (debug build):

```powershell
adb shell am start -n com.voicechat.agent/.debug.SttProbeActivity --es action status
adb shell am start -n com.voicechat.agent/.debug.SttProbeActivity --es action adapter
adb shell am start -n com.voicechat.agent/.debug.SttProbeActivity --es action raw
```

The probe is a debug source-set artifact; it is not part of a release build and
must not log transcripts in production code (see [docs/logging.md](./docs/logging.md)).

## Later milestones (extend here)

Add a new subsection per milestone below, keeping the same "what to record"
shape. Suggested future entries:

- **M09 VAD / endpointing** — documented in the [M09 section](#m09--vad-fast-onset-and-bounded-endpointing)
  above (onset latency, false endpoints/holds, exactly one endpoint per turn,
  maximum-silence cap, route/conditions, CPU/memory on device).
- **M10 Smart Turn v3.2** — model load, inference time, veto/hold behavior,
  silence cap, missing/corrupt model.
- **M11 on-device TTS** — installed voices, on-device-only voice selection,
  first-audible latency, speaker/headset routing, immediate stop.
- **M13 credentials** — Keystore-backed store/replace/remove across process
  restart; no secret in logs, crashes, backups, or QR payloads.
- **M14–M19 providers** — one section each: destination disclosure, model
  selection, streaming, cancellation, network-loss and auth errors, and proof
  that no silent provider/model fallback occurs.
- **M20 local LLM runtimes** — availability, unprovisioned/corrupt/insufficient
  resource paths, memory/thermal contention with STT/TTS.
- **M21/M24 voice loop and barge-in** — end-to-end STT → LLM → TTS, live
  transcript, barge-in stop/cancel timing, delivered-vs-generated history.
- **M25 quality/latency evaluation** — labeled corpus per condition, per-stage
  p50/tail latency with full configuration metadata.
- **M26 release hardening** — the full permission/network/model/process-death
  matrix on the release build.
