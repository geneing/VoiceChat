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
| `tts.AndroidTtsInstrumentedTest` | The platform TTS engine initializes and enumerates installed voices; a "ready" voice must be one `getVoices()` reported and not network-required (network voices are never selectable); immediate `stop()` during playback ends the utterance with a terminal event without hanging (skipped with `Assume` when no embedded voice exists); the `OnDeviceTts` contract adapter rejects empty input and closes. Availability is recorded, never faked. |
| `ui.ConversationAppInstrumentedTest` | The app launches on device and renders the conversation list (Room + Compose smoke). |
| `credentials.AndroidKeystoreCredentialStoreInstrumentedTest` | The real AndroidKeyStore-backed store stores/replaces/removes a credential against the device KeyStore; a second store instance reads the persisted value (restart proxy); the app-private preferences file holds only ciphertext, never the plaintext secret. |
| `orchestration.TurnOrchestrationInstrumentedTest` | Runs the real M21 `TurnOrchestrator`/`TurnStateMachine` on device with an inline fake provider and an in-memory repository: one text turn completes and persists a truthful assistant turn. No network, credential, microphone, or real TTS; a structural smoke check, not a provider/voice measurement. |
| `settings.PreferencesSettingsStoreInstrumentedTest` | The real DataStore (Preferences)-backed settings store persists a validated selection to the app-private file and a second store instance reads it (restart proxy); the file holds no credential value. |

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
(R-0061, R-0063). This section is a checklist, not a result: **do not mark any
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
- **M11 on-device TTS** — implemented; see [M11 — On-device
  text-to-speech](#m11--on-device-text-to-speech) above for the manual checks.
- **M13 credentials** — implemented; see the [M13
  section](#m13--credential-storage-and-provider-capability-registry) above
  (AndroidKeyStore store/replace/remove, restart, no secret in logs, crashes,
  backups, or QR payloads).
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

## M12 — LLM streaming contract and deterministic fake (JVM only)

**No device test is required for M12.** The milestone defines the provider-neutral
request/stream/error contract, the reference consumer, and a deterministic fake.
All of it is pure Kotlin covered by `:app:testDebugUnitTest`; nothing in M12
touches the microphone, AICore/ML Kit, TFLite/LiteRT, audio routing, or a real
provider, and the fake requires no network or credentials. There is no
`androidTest` source for M12, so there is nothing to add to the automated
on-device table above.

What the JVM suite proves instead (see
[docs/llm-contract.md](./docs/llm-contract.md)):

- event ordering (`Delta*` then exactly one terminal event);
- backpressure (a slow consumer suspends the producer, no lost events);
- cancellation (mid-stream, and a race at the emission boundary);
- timeout (a stall plus a typed `LLM_TIMEOUT`, never a silent end);
- malformed and empty streams (a terminal-less end is a failure, not a completion);
- partial-response state (`Cancelled`/`Failed` carry the text received so far);
- determinism (identical event sequences and virtual timestamps across runs);
- no vendor/platform type in the contract, and no prompt/response/credential
  content in the trace or the developer log.

Recorded with `:app:testDebugUnitTest`; no device, network, model, or credential
is involved. Any future real provider belongs to M14–M20, whose device runs are
recorded in their own sections.

## M13 — Credential storage and provider capability registry

This section covers the AndroidKeyStore-backed credential store and the provider
capability registry; see [docs/credentials.md](./docs/credentials.md). Most of
M13 is pure Kotlin, proven by JVM tests. Only the real AndroidKeyStore store is
device-bound.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device): store/replace/remove, restart,
# unsupported-method hiding, endpoint validation, auth errors, redaction, scans.
.\gradlew.bat :app:testDebugUnitTest

# Compile the instrumented test WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest

# Run the M13 instrumented test ON the device (NOT run for this milestone)
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.voicechat.agent.credentials.AndroidKeystoreCredentialStoreInstrumentedTest"
```

What the JVM suite proves instead (details in
[docs/credentials.md](./docs/credentials.md#tests)):

- store / replace / remove and the redacted `CredentialStatus`;
- a process-restart read: a new `EncryptedCredentialStore` over the same file-
  backed blob store loads the value, and the plaintext never appears at rest;
- a typed `CREDENTIAL_STORAGE_FAILED` on an encrypt failure, and an honest
  `NotStored`/`null` when the KeyStore key is lost;
- only documented auth methods are exposed (the enum has no QR value), and
  unverified provider behavior is marked rather than claimed;
- per-model capability reconciliation refuses an unsupported reasoning level;
- endpoint validation: TLS required for non-local hosts, loopback may use `http`,
  credentials-in-URL and QR-sourced endpoints are refused, and a fixed provider's
  host cannot be substituted;
- a QR payload carrying a credential is rejected;
- a credential never reaches a log line, `toString`, store error, or
  crash-metadata header map;
- `RepositorySecretScanTest` finds no credential shape in shipped source,
  resources, build files, docs, or CI config, and no packaged keystore/Firebase
  config.

### On-device run (deferred; compile-only for this milestone)

`AndroidKeystoreCredentialStoreInstrumentedTest` exercises the real
`AndroidKeystore` AES/GCM cipher and the real app-private preferences. It was
**compiled but not run** on a device for M13 (device testing is deferred).

What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit.
- The instrumented result for store / status / load / replace / remove.
- That a **new** store instance (restart proxy) still reads the credential, that
  the AndroidKeyStore contains alias `voicechat.credentials.v1`, and that the
  `voicechat-credentials` preferences file holds only ciphertext.
- A manual **process-restart** check: store a credential, then
  `adb shell am force-stop com.voicechat.agent`, relaunch, and confirm the status
  is still `Stored`/the value loads. (A true restart cannot be forced inside a
  single instrumentation run, so it is a manual step.)
- The re-entry behavior after an invalidation: lock-screen change or KeyStore
  reset, then confirm the app reports `NotStored` and does not crash or return a
  wrong value (R-0070).
- A backup/restore or device-to-device transfer check showing no credential blob
  leaves the device (R-0071).

Unrun device items are tracked as R-0070, R-0071, and R-0075; **do not mark any
row passed unless it was run on the device.**

## M21 — Turn orchestration and cancellation

M21 is a **JVM-first** milestone: the turn state machine and orchestrator are
pure-Kotlin and `android.*`-free, so the acceptance behavior is proven by
`:app:testDebugUnitTest` with deterministic fakes and no device, network,
credential, microphone, or real TTS. The package does not depend on the Compose
`ui` package (`OrchestrationPurityTest`).

What the JVM suite proves (see [docs/orchestration.md](./docs/orchestration.md)):

- voice and manual turns share one path; a voice turn records its `VOICE` source;
- interim transcript revisions apply in order and stale ones are dropped;
- no-speech, empty, and low-confidence transcripts end as explicit outcomes;
- provider failure, remote cancellation, user cancellation, barge-in
  interruption, and TTS failure each map to a typed terminal state;
- out-of-order late events and events from a superseded turn are dropped by turn
  ID and never mutate a newer turn (cancellation races);
- generated/queued/delivered accounting persists only the delivered prefix and
  leaves no phantom assistant turn when nothing was output;
- history and trace stay content-free, and a provider-reported model mismatch is
  recorded rather than silently accepted.

An instrumented test compiles the same path for the device runtime:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device)
.\gradlew.bat :app:testDebugUnitTest

# Compile the instrumented test WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

`orchestration.TurnOrchestrationInstrumentedTest` **was not run** for this
milestone; per the device-testing policy it is compiled only, and it is a
structural smoke check with a fake provider, not a voice-quality measurement.

The live capture -> VAD -> STT -> orchestration loop, TTS chunk overlap, and
barge-in onset timing remain **manual/device** work for M24/M25 (R-0080–R-0083);
do not claim them from the JVM suite.

## M22 — Settings and capability-aware selection

M22 is a **JVM-first** milestone: the settings model, option building, validation,
authorization lifecycle, and store logic are pure Kotlin
(`SettingsSourcePurityTest`), so the acceptance behavior is proven by
`:app:testDebugUnitTest` with deterministic fakes and no device, network, real
credential, microphone, or real TTS. The Compose settings surface is tested under
Robolectric. Only the real AndroidKeyStore + DataStore persistence is
device-bound. See [docs/settings.md](./docs/settings.md).

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device)
.\gradlew.bat :app:testDebugUnitTest

# Lint / format / assemble
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck

# Compile the instrumented test WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves instead:

- **Unsupported options hidden.** The auth-method list is exactly the provider's
  documented methods (the `AuthMethod` enum has no QR value); reasoning levels
  come from the provider∩model reconciliation, so an unsupported level is absent;
  network-required TTS voices are excluded.
- **Unavailable entries disabled with a reason.** An unprovisioned STT mode, a
  downloadable/unavailable model, and a Smart Turn model that is not installed
  render disabled with a safe explanation.
- **Unavailable models.** A model not offered/not ready is shown disabled (and a
  stored selection for it is cleared).
- **Credential replacement/removal.** Save/replace/remove go through the M13
  `CredentialStore`; the redacted `CredentialStatus` updates and the secret never
  appears in the UI state.
- **Invalid selections.** A stored provider/model/auth/reasoning/STT/TTS/Smart
  Turn value the registry or runtime no longer supports is dropped, reported, and
  re-saved.
- **Persistence.** DataStore round-trips every validated selection and survives a
  restart proxy; clearing a selection removes it.
- **Destination and remote-transfer disclosure.** The validated destination and
  the "text/context leave the device; the full conversation is not sent" notice
  are exposed before sending; a configurable (Hermes) destination is validated
  (TLS for non-local hosts) before it is stored.
- **Provider authorization / QR.** The OpenRouter PKCE session is short-lived and
  single-use, and an arbitrary destination or a credential-bearing URL is
  refused; QR is unsupported by every provider and a credential/arbitrary QR is
  rejected.

### On-device run (deferred; compile-only for this milestone)

`settings.PreferencesSettingsStoreInstrumentedTest` exercises the real DataStore
preferences file. It was **compiled but not run** on a device for M22 (device
testing is deferred).

What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit.
- That a validated selection persists and a second store instance reads it, and
  that the `voicechat-settings` preferences file holds no credential value.
- Manually, the runtime capability snapshot on the **settings screen**: the STT
  mode states from `checkStatus()`, the installed embedded TTS voices, and that
  no unsupported option is shown. This is the R-0100 measurement; never claim it
  from the JVM suite.

Unrun device items are tracked as R-0100 and R-0104; **do not mark any row
passed unless it was run on the device.**

The live `/models` catalog (so the model list is populated) and the OpenRouter
browser/token exchange are M14–M19 work (R-0101, R-0102); the persisted selection
is consumed by the turn path in M23 (R-0103).

## M14 — OpenAI adapter and shared remote transport

M14 is a **JVM-first** milestone. The shared remote transport and the OpenAI
adapter are pure Kotlin behind the M12 contract; every protocol case is a
recorded SSE fixture or a scripted engine with **no socket, clock, DNS, or real
credential**. There is no M14-specific instrumented test, so no row is added to
the automated on-device table above; the existing instrumented tests were
compiled only. See [docs/llm-transport.md](./docs/llm-transport.md) and
[docs/openai-adapter.md](./docs/openai-adapter.md).

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no network): fixture replay for the
# normal stream, empty response, malformed frames, rate limit, auth failure,
# server error, network loss, and cancellation mid-stream.
.\gradlew.bat :app:testDebugUnitTest

# Compile the instrumented tests WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves instead (details in the two linked documents):

- the request goes to the documented `POST /v1/responses` with `Authorization:
  Bearer <credential>` and **`"store": false`** (R-0018), and `"stream": true`;
- incremental `response.output_text.delta` frames become deltas, a
  terminal `response.completed` carries `usage`/`model`/`reasoning`, and a
  stream that ends without a terminal event is `LLM_MALFORMED_RESPONSE` (R-0067);
- the `reasoning_*` channel is excluded from assistant text and only the
  reported `reasoning.effort` is surfaced (R-0066);
- 401/403 → `LLM_AUTHENTICATION_FAILED`, 429 → `LLM_RATE_LIMITED`,
  408/504 → `LLM_TIMEOUT`, other 4xx → `LLM_INVALID_REQUEST`, 5xx →
  `LLM_UNAVAILABLE`, a dropped connection → `LLM_NETWORK_FAILED`, both as
  streamed `error` events and as HTTP statuses;
- a mid-stream network loss and a mid-stream cancellation keep the partial text
  as partial and never report completion;
- the credential check (`GET /v1/models`) maps 401/403 to an authentication
  rejection and keeps a transient failure typed (R-0073);
- `HTTP`/`JSON` library types stay inside the transport and `domain/`+`contracts/`
  do not import the transport (`RemoteSourcePurityTest`); the app contract stays
  vendor-free (`LlmContractPurityTest`);
- no credential, prompt, or assistant text appears in the developer log.

### Opt-in real-provider smoke test (marked NOT run)

`providers.openai.OpenAiSmokeTest` is the one M14 test that can touch the real
service. It is **skipped** (JUnit `Assume`), and so never runs in routine CI,
unless **both** `VOICECHAT_OPENAI_SMOKE=1` and `OPENAI_API_KEY` are set:

```powershell
$env:VOICECHAT_OPENAI_SMOKE = "1"
$env:OPENAI_API_KEY = "<your key>"                 # externally supplied only
$env:VOICECHAT_OPENAI_SMOKE_MODEL = "gpt-5.6-luna" # optional
.\gradlew.bat :app:testDebugUnitTest --tests "*OpenAiSmokeTest"
```

It **was not run** for this milestone. This is tracked as R-0091 (adapter) and
R-0096 (credential check). What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit, and the
  selected model and reasoning level.
- Network/region conditions and the observed time to first text and total
  completion time.
- The completion outcome and any typed failure reason — **never** the prompt or
  the response text, and **never** the API key. The smoke test itself asserts on
  completion/counts only and prints no content.

Device-level latency, cancellation-acknowledgement timing, and live provider
behavior remain unmeasured and are tracked in R-0069, R-0091, and R-0096. **Do
not mark any of these passed unless the command was actually run.**

## M15 — OpenRouter adapter

M15 is a **JVM-first** milestone, like M14. The OpenRouter adapter is pure Kotlin
behind the M12 contract and reuses the M14 shared transport; every protocol case
is a recorded SSE fixture or a scripted engine with **no socket, clock, DNS, or
real credential**. There is no M15-specific instrumented test, so no row is added
to the automated on-device table above; the existing instrumented tests were
compiled only. See [docs/openrouter-adapter.md](./docs/openrouter-adapter.md).

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no network): fixture replay for the
# normal stream, empty response, malformed frames, rate limit, auth failure,
# payment required, server error, mid-stream provider error, network loss,
# cancellation mid-stream, terminal-less stream, reasoning exclusion, model
# identity, and the /models catalog.
.\gradlew.bat :app:testDebugUnitTest

# Compile the instrumented tests WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves instead (details in the linked document):

- the request goes to the documented `POST /api/v1/chat/completions` with
  `Authorization: Bearer <credential>`, `"stream": true`, and
  **`"provider": {"allow_fallbacks": false}`**, and it never sends `models[]`,
  `route`, `:nitro`, or `:floor` (R-0017);
- `choices[].delta.content` frames become deltas; the content-free accounting
  chunk carries `usage`/`model`/`cost`; the `[DONE]` sentinel is the only
  terminal event, so a usage chunk without `[DONE]` is `LLM_MALFORMED_RESPONSE`
  (R-0067);
- the reported serving model is surfaced on `Completed.model`; a response that
  reports a model different from the selection is detectable, not silently
  rewritten (R-0017/R-0023);
- the `reasoning`/`reasoning_details` channel is excluded from assistant text
  and `reasoning_tokens` is surfaced as usage (R-0066); `ReasoningLevel.NONE`
  omits the reasoning object, and a level outside the declared capability is
  refused before any request;
- 401/403 → `LLM_AUTHENTICATION_FAILED`, 429 → `LLM_RATE_LIMITED`,
  402 → `LLM_INVALID_REQUEST`, 408/504 → `LLM_TIMEOUT`, 5xx →
  `LLM_UNAVAILABLE`, a dropped connection → `LLM_NETWORK_FAILED`, both as
  streamed `error.metadata.error_type` events and as HTTP statuses;
- a mid-stream provider error and a mid-stream cancellation keep the partial text
  as partial and never report completion;
- the credential check (`GET /api/v1/models`) maps 401/403 to an authentication
  rejection and keeps a transient failure typed (R-0073);
- `/models` parsing exposes per-model `supported_efforts` through
  `ModelCapabilityCatalog` (R-0102);
- no credential, prompt, or assistant text appears in the developer log.

### Opt-in real-provider smoke test (marked NOT run)

`providers.openrouter.OpenRouterSmokeTest` is the one M15 test that can touch the
real service. It is **skipped** (JUnit `Assume`), and so never runs in routine
CI, unless **both** `VOICECHAT_OPENROUTER_SMOKE=1` and `OPENROUTER_API_KEY` are
set:

```powershell
$env:VOICECHAT_OPENROUTER_SMOKE = "1"
$env:OPENROUTER_API_KEY = "<your key>"                       # externally supplied only
$env:VOICECHAT_OPENROUTER_SMOKE_MODEL = "anthropic/claude-sonnet-4.5"  # optional
.\gradlew.bat :app:testDebugUnitTest --tests "*OpenRouterSmokeTest"
```

It **was not run** for this milestone. This is tracked as R-0111 (adapter) and
R-0112 (credential check). What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit, and the
  selected model and reasoning level.
- Network/region conditions and the observed time to first text and total
  completion time.
- The completion outcome, the reported model, and any typed failure reason —
  **never** the prompt or the response text, and **never** the API key. The smoke
  test itself asserts on completion, model identity, and counts only and prints no
  content.

Device-level latency, cancellation-acknowledgement timing, and live provider
behavior remain unmeasured and are tracked in R-0069, R-0091, and R-0111. **Do
not mark any of these passed unless the command was actually run.**

## M17 — OpenCode Go adapter

M17 is a **JVM-first** milestone. The adapter is pure Kotlin behind the M12
contract and reuses the M14 remote transport; every protocol case is a recorded
SSE fixture or a scripted engine with **no socket, clock, DNS, or real
credential**. There is no M17-specific instrumented test, so no row is added to
the automated on-device table above; the existing instrumented tests were
compiled only. See [docs/opencode-go-adapter.md](./docs/opencode-go-adapter.md).

OpenCode Go is deliberately **not** treated as OpenAI-compatible: its own model
table places each model on one of three protocols (Chat Completions for
GLM/Kimi/DeepSeek, Responses for GPT/Grok, Anthropic Messages for Qwen/MiniMax),
and the adapter dispatches on the model and refuses an unplaced id.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no network): fixture replay for the three
# protocol families, the unplaced-model and unsupported-reasoning refusals, and
# the ordinary auth/rate-limit/server/network/terminal-less errors.
.\gradlew.bat :app:testDebugUnitTest --tests "*OpenCodeGo*"

# Compile the instrumented tests WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves instead (details in the linked document):

- the request goes to the **documented endpoint for the selected model's
  family** (`/chat/completions`, `/responses`, or `/messages`) with
  `Authorization: Bearer <credential>`, the documented `User-Agent`, and a stable
  `x-opencode-session`; the Responses family always sends `"store": false`;
- Chat Completions `data: [DONE]`, Responses `response.completed`, and Messages
  `message_stop` each terminate the stream, and Messages input/output tokens are
  merged into one usage report;
- the reasoning channel (`response.reasoning_*.delta`, `thinking_delta`) is
  excluded from assistant text;
- an unplaced model (`omen-alpha`) is refused with `LLM_INVALID_REQUEST` **before
  any request is sent** — no protocol is assumed for it;
- a requested reasoning level is refused before send because Go documents no
  reasoning control (capability declares none);
- 401/403 → `LLM_AUTHENTICATION_FAILED`, 429 → `LLM_RATE_LIMITED`, other 4xx →
  `LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`, a dropped connection →
  `LLM_NETWORK_FAILED`, both as streamed error frames and as HTTP statuses;
- a terminal-less stream is `LLM_MALFORMED_RESPONSE`, never a completion; a
  mid-stream cancellation emits no terminal event;
- no credential validator is offered (Go's `GET /models` answers without a key),
  and the registry Go row claims no reasoning and marks usage/streaming
  unverified;
- no credential, prompt, or assistant text appears in the developer log.

### Opt-in real-provider smoke test (marked NOT run)

`providers.opencodego.OpenCodeGoSmokeTest` is the one M17 test that can touch the
real service. It is **skipped** (JUnit `Assume`), and so never runs in routine
CI, unless **both** `VOICECHAT_OPENCODE_GO_SMOKE=1` and `OPENCODE_API_KEY` are
set:

```powershell
$env:VOICECHAT_OPENCODE_GO_SMOKE = "1"
$env:OPENCODE_API_KEY = "<your key>"                          # externally supplied only
$env:VOICECHAT_OPENCODE_GO_SMOKE_MODEL = "glm-5.3-flash"      # optional; try gpt-5.6-luna, qwen3.8-flash
.\gradlew.bat :app:testDebugUnitTest --tests "*OpenCodeGoSmokeTest"
```

It **was not run** for this milestone. This is tracked as R-0138. What to record
for every run:

- Device model/build, Android version, build variant (`debug`), commit, and the
  selected model and family.
- Network/region conditions and the observed time to first text and total
  completion time.
- The completion outcome and any typed failure reason — **never** the prompt or
  the response text, and **never** the API key. The smoke test itself asserts on
  completion/counts only and prints no content.
- Whether each family's live SSE framing matches the mapping (R-0131).

Device-level latency, cancellation-acknowledgement timing, and live provider
behavior remain unmeasured and are tracked in R-0131 and R-0138. **Do not mark
any of these passed unless the command was actually run.**
