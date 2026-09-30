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
| `stt.MlKitSttInstrumentedTest` | Both catalog modes return a typed `SttAvailability` without throwing; the adapter reports the single engine ID; and, when the engine is **not** ready, the adapter refuses to run and emits a typed `STT_MODEL_NOT_READY`/`STT_UNAVAILABLE` failure. When the engine is ready this case is skipped (`Assume`), never faked. |
| `tts.AndroidTtsInstrumentedTest` | The platform TTS engine initializes and enumerates installed voices; a "ready" voice must be one `getVoices()` reported and not network-required (network voices are never selectable); immediate `stop()` during playback ends the utterance with a terminal event without hanging (skipped with `Assume` when no embedded voice exists); the `OnDeviceTts` contract adapter rejects empty input and closes. Availability is recorded, never faked. |
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

## M11 — On-device text-to-speech

Engine: the Android platform `android.speech.tts.TextToSpeech` API, restricted to
**embedded (non-network) voices** only (`isNetworkConnectionRequired() == false`,
`docs/decisions.md` §2.2). There is no cloud TTS and no fallback to a network
voice. Instrumented coverage is `tts.AndroidTtsInstrumentedTest`; the manual
checks below are **not** automated and were **not** run for this milestone.

What to record for every run:

- Device model/build, Android build fingerprint, build variant (`debug`), commit.
- The full embedded voice list from `getVoices()` with, for each voice, its
  locale and `isNetworkConnectionRequired()` flag; the voice the engine selected,
  and the locale requested.
- The output route kind at playback start (built-in speaker, wired headset,
  Bluetooth) and any route change during playback.
- First-audible time (from `speak()` to the `TtsEvent.Started`/first-audible
  event) and total playback time, per voice and per route.
- Immediate-stop latency (barge-in `stop()` call to the `Interrupted` terminal)
  with the delivered prefix reported.
- Empty input and forced-failure behavior; the explicit "no on-device voice"
  state on a device/locale with only network voices.

| Check | How | Record |
| --- | --- | --- |
| Voice discovery | Run `AndroidTtsInstrumentedTest` and read the logged availability/voice counts. | Installed voice count and the embedded subset; the selected voice id; `NoOnDeviceVoice` when no embedded voice exists. Never claim a voice exists without `getVoices()`. |
| On-device-only selection | Inspect the selected voice's `isNetworkConnectionRequired()`. | It is `false`; a network voice is never selected even when it is the only/locale-best match. |
| First-audible latency | Speak a fixed phrase per voice/route; time `speak()` to the first-audible event; measure audible output, not the API return. | p50/tail first-audible per voice and route; do not set a budget from a single run. |
| Speaker / headset routing | Play a phrase through the built-in speaker, a wired headset, and Bluetooth; watch output routing. | Audio is audible on the expected output; the recorded `AUDIO_ROUTE` kind changes; no crash on route change. |
| Audio focus | Start TTS, then trigger another app's audio (or a call); observe focus handling. | Focus acquired/abandoned as expected; note whether playback ducks/pauses and whether capture (M07) is affected. Final policy is M24. |
| Attention/empty path | Call the adapter with empty input; on a device/locale with only network voices, attempt to speak. | Empty input completes with no playback and no engine call; no-on-device-voice shows an explicit typed state and stays text-only. |
| Immediate stop | Start a long phrase, then call `stop()` mid-playback (the instrumented test does this unattended). | Playback stops promptly; the `speak` flow ends with an `Interrupted` terminal (or `Completed` if it had already finished); never hangs. |
| Failed input | Where reproducible, force an engine error (for example unavailable engine). | A typed `TTS_SYNTHESIS_FAILED`/`TTS_PLAYBACK_FAILED` event; no silent success. |
| On-device confirmation | After voices are installed, repeat playback in airplane mode. | Playback still works, confirming on-device synthesis with no network voice. |

Genuinely manual and **not** automated: audible first-audible timing, speaker /
wired / Bluetooth routing, focus interaction, real voice quality, and the
airplane-mode proof. Do not claim these from the JVM tests.

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

- **M09 VAD / endpointing** — onset latency, false endpoints/holds, exactly one
  endpoint per turn, maximum-silence cap, CPU/memory on device.
- **M10 Smart Turn v3.2** — model load, inference time, veto/hold behavior,
  silence cap, missing/corrupt model.
- **M11 on-device TTS** — implemented; see [M11 — On-device
  text-to-speech](#m11--on-device-text-to-speech) above for the manual checks.
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
