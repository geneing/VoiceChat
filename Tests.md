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
| `audio.EchoCancellationInstrumentedTest` | Attaches the real `PlatformEchoCanceller` to a real `AudioRecord` session and asserts it reports `ENABLED` (skipped with `Assume` when the platform has no canceller). Passed on Pixel 10 on 2026-10-17. |
| `audio.EchoCancellationEffectivenessInstrumentedTest` | Opt-in (`-e echoProbe=1`): speaks a fixed phrase through the platform TTS and measures the residual frame RMS at the microphone for four capture configurations (AEC off/on, `MODE_NORMAL`/`MODE_IN_COMMUNICATION`). Requires a quiet room; records, never asserts. Run on Pixel 10 on 2026-10-17 (AEC roughly halves the echo). |
| `stt.MlKitSttInstrumentedTest` | Both catalog modes return a typed `SttAvailability` without throwing; the adapter reports the single engine ID; and, when the engine is **not** ready, the adapter refuses to run and emits a typed `STT_MODEL_NOT_READY`/`STT_UNAVAILABLE` failure. When the engine is ready this case is skipped (`Assume`), never faked. |
| `tts.AndroidTtsInstrumentedTest` | The platform TTS engine initializes and enumerates installed voices and installed engines; a "ready" voice must be one `getVoices()` reported and not network-required (network voices are never selectable); immediate `stop()` during playback ends the utterance with a terminal event without hanging (skipped with `Assume` when no embedded voice exists); the `OnDeviceTts` contract adapter rejects empty input and closes. Availability is recorded, never faked. |
| `eval.tts.VoiceTtsHarnessInstrumentedTest` | Opt-in (`-e ttsHarnessRunId`): speaks the fixed `VoiceTtsHarnessCases` texts through the real platform engine and the configured embedded voice and records `tts_first_audible` / `tts_completion` to app-private cache. Skipped (`Assume`) when no embedded voice exists. |
| `ui.ConversationAppInstrumentedTest` | **Removed.** The app-launch/Compose smoke check is not automatable on Android 17 with `androidx.test.ext:junit` 1.3.0 (Espresso `InputManager.getInstance()` reflection fails). The Compose surface is covered by Robolectric JVM tests; launch is a manual step. |
| `credentials.AndroidKeystoreCredentialStoreInstrumentedTest` | The real AndroidKeyStore-backed store stores/replaces/removes a credential against the device KeyStore; a second store instance reads the persisted value (restart proxy); the app-private preferences file holds only ciphertext, never the plaintext secret. |
| `orchestration.TurnOrchestrationInstrumentedTest` | Runs the real M21 `TurnOrchestrator`/`TurnStateMachine` on device with an inline fake provider and an in-memory repository: one text turn completes and persists a truthful assistant turn. No network, credential, microphone, or real TTS; a structural smoke check, not a provider/voice measurement. |
| `voice.VoiceSessionInstrumentedTest` | Runs the STT-only `VoiceSessionCoordinator` on device with inline fakes (audio, endpoint, STT) and an in-memory repository: one finalized transcript persists as a user turn, with no assistant generation. No network, credential, microphone, or real STT; a structural smoke check, not a voice-quality measurement. |
| `settings.PreferencesSettingsStoreInstrumentedTest` | The real DataStore (Preferences)-backed settings store persists a validated selection to the app-private file and a second store instance reads it (restart proxy); the file holds no credential value. |

> **Not automated on device:** the Compose conversation surface is tested under
> Robolectric on the JVM (M06/M22/M23) and through the voice/text integration
> tests. An instrumented Compose launch test is intentionally **not** included:
> `androidx.test.ext:junit` 1.3.0 (latest) calls Espresso's
> `InputManagerEventInjectionStrategy`, which reflects
> `android.hardware.input.InputManager.getInstance()` and throws
> `NoSuchMethodException` on Android 17, so the rule cannot initialize. The
> app-launch check therefore stays a manual step ([M06](#m06--compose-conversation-ui-and-manual-text-path)).

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
voice. The settings screen offers a speech-engine picker (when the device reports
more than one engine) and a voice picker, defaulting to the most natural embedded
voice. Instrumented coverage is `tts.AndroidTtsInstrumentedTest`; the opt-in
TTS-stage harness is `eval.tts.VoiceTtsHarnessInstrumentedTest`. The manual checks
below are **not** automated and were **not** run for this milestone.

What to record for every run:

- Device model/build, Android build fingerprint, build variant (`debug`), commit.
- The installed TTS **engine** list from `getEngines()` with the system default,
  the engine selected in settings (or "platform default"), and the engine the
  session actually used.
- The full embedded voice list from `getVoices()` with, for each voice, its
  locale, `isNetworkConnectionRequired()` flag, and `getQuality()`/
  `getLatency()`; the voice the app selected (the most natural embedded voice, or
  the persisted choice), and the locale requested.
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
| Engine discovery and selection | Run `AndroidTtsInstrumentedTest` and open Settings; change the speech engine when more than one is installed. | The `getEngines()` list with the system default; that a single-engine device shows no engine picker; that the chosen engine is the one used (or a typed "not installed" if it disappeared). |
| Most natural voice | Leave the voice unset and read the selected voice. | The selected voice's `getQuality()` is the highest among the embedded voices for the locale; note any tie broken by latency. |
| TTS-stage timings (harness) | `.\\scripts\\run-tts-harness-device.ps1` (opt-in). | The `summary.md` table: median `tts_first_audible` and `tts_completion` per text, with the conditions the script copied back. Currently **not run**. |
| On-device-only selection | Inspect the selected voice's `isNetworkConnectionRequired()`. | It is `false`; a network voice is never selected even when it is the only/locale-best match. |
| First-audible latency | Speak a fixed phrase per voice/route; time `speak()` to the first-audible event; measure audible output, not the API return. | p50/tail first-audible per voice and route; do not set a budget from a single run. |
| Speaker / headset routing | Play a phrase through the built-in speaker, a wired headset, and Bluetooth; watch output routing. | Audio is audible on the expected output; the recorded `AUDIO_ROUTE` kind changes; no crash on route change. |
| Audio focus | Start TTS, then trigger another app's audio (or a call); observe focus handling. | Focus acquired/abandoned as expected; note whether playback ducks/pauses and whether capture (M07) is affected. Final policy is M24. |
| Attention/empty path | Call the adapter with empty input; on a device/locale with only network voices, attempt to speak. | Empty input completes with no playback and no engine call; no-on-device-voice shows an explicit typed state and stays text-only. |
| Immediate stop | Start a long phrase, then call `stop()` mid-playback (the instrumented test does this unattended). | Playback stops promptly; the `speak` flow ends with an `Interrupted` terminal (or `Completed` if it had already finished); never hangs. |
| Failed input | Where reproducible, force an engine error (for example unavailable engine). | A typed `TTS_SYNTHESIS_FAILED`/`TTS_PLAYBACK_FAILED` event; no silent success. |
| On-device confirmation | After voices are installed, repeat playback in airplane mode. | Playback still works, confirming on-device synthesis with no network voice. |

### TTS-stage harness run (Pixel 10, 2026-10-17)

`.\\scripts\\run-tts-harness-device.ps1 -Repeats 3` on the Pixel 10 (`frankel`,
Android 17 / API 37, build `CP3A.261005.005`, debug). Conditions: locale `en-US`,
engine `com.google.android.tts`, voice `en-us-x-tpf-local` (quality 400,
latency 200) chosen by the most-natural policy (`selectedVoice=most-natural`),
built-in speaker (assumed). All 9 attempts delivered.

| metric | n | p50 ms | p90 ms | max ms |
| --- | --- | --- | --- | --- |
| tts_first_audible | 9 | 23 | 305 | 305 |
| tts_completion | 9 | 4958 | 6055 | 6055 |

The 305 ms first-audible sample is the first `speak()` after engine
initialization; every later attempt was 15-26 ms. `tts_completion` is full
playback time, not a start delay. This is one device, one voice, one moment; the
route/echo and audible-naturalness rows above are still manual and unrun.

Genuinely manual and **not** automated: audible first-audible timing, speaker /
wired / Bluetooth routing, focus interaction, real voice quality, and the
airplane-mode proof. Do not claim these from the JVM tests.

## M28 — Distilled Kokoro on-device TTS

Engine: the distilled "Teacher in the Loop" Kokoro-82M student
(`MithrilMan/teacher-in-the-loop`), English voice `af_heart`, run through ONNX
Runtime on the CPU execution provider (`docs/decisions.md` §2.4). It replaces the
platform engine on the voice-loop path: `AndroidTtsEngine` stays in the tree but
is no longer wired. The graph is **not** in the APK — it is downloaded
app-privately (or pushed by script), verified by exact size and SHA-256, and a
missing/corrupt graph is a visible "install the voice model" state, never a
silent fallback to another voice.

JVM coverage (runs in `:app:testDebugUnitTest`, no device, no native library):
`tts.kokoro.KokoroModelStoreTest` (integrity, atomic install, failure and
cancellation cleanup), `tts.kokoro.KokoroTtsEngineTest` (first-audible ordering,
chunked playback, interruption accounting, typed failures),
`tts.kokoro.phonemes.*` (the phonemizer port against Python-generated fixtures),
`eval.tts.KokoroTtsStageHarnessTest` (the latency aggregation, with a
deterministic clock). Nothing here loads ONNX Runtime or the 9 MB graph.

Install the artifact before a device run:

```bash
# WSL, repo root
bash scripts/fetch-models.sh kokoro-af-heart
bash scripts/push-models.sh kokoro-af-heart
```

| Check | How | Record |
| --- | --- | --- |
| Graph integrity | `scripts/push-models.sh kokoro-af-heart`, then open Settings. | The section reports the voice installed; a truncated/wrong-hash file reports `DownloadRequired`/corrupt with a reason instead of speaking. |
| Time to first sound | `.\\scripts\\run-kokoro-tts-latency.ps1` (opt-in). | `synthesis-record.md`: text start → phonemes → first PCM → all PCM per text, plus the realtime factor; `audible-record.md`: `tts_first_audible` through real playback. The gap between the two is the audio device's start cost. |
| Interruption | Start a long reply, then speak over it. | Playback stops promptly; the interrupted utterance reports only the text that was actually audible (the ported chunker's source-text spans), never more. |
| Route | Play through the built-in speaker, a wired headset, and Bluetooth. | Audio is audible on the expected route; the recorded `AUDIO_ROUTE` kind changes; no crash on a route change. |
| Offline | After install, run in airplane mode. | Playback still works: the graph, the vocabulary, and the lexicon are all on-device. |
| Airplane/empty path | Speak empty text and punctuation-only text. | No playback, no `Started`, a normal completion — not a failure and not fabricated audio. |
| Phonemizer parity | `bash scripts/kokoro-phoneme-parity.sh` (WSL) or the JVM parity test. | The measured sentence-exact agreement with the Python reference (`docs/tts.md`), with the categories that differ. |
| Text normalization | Run the JVM `tts.normalization` tests; speak a reply containing a date, a currency amount, a unit, an abbreviation, and an all-caps initialism. | Each form is spoken as words, not letters or digits; nothing is double-expanded (idempotence); the delivered-text prefix in the transcript still matches what was heard (`docs/text-normalization.md`). |

### Kokoro latency run (Pixel 10)

**Not run yet.** The harness, the runner, and the host-side aggregation exist;
the device numbers must come from a real run and are recorded here when they do,
with the device, build, graph revision, and repeat count.

Two host-side components have been measured on the development machine (2026-10-17)
and are recorded in [evaluation.md](./evaluation.md#latency-pixel-10): the real
phonemizer costs 3-4 ms per assistant-shaped sentence on the JVM
(`KokoroPhonemizerTimingTest`), the real text-normalization pipeline costs 1-3 ms
per rule-heavy sentence in the same test, and one graph call per sentence costs
111-177 ms under Python ONNX Runtime 1.31.0 on the host CPU
(`scripts/kokoro-onnx-contract.py`). Neither is a device claim: the Pixel's CPU,
the pinned Android runtime 1.29.0, and the audio device all differ.

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
- **M10 Smart Turn v3.2** — implemented (opt-in, default off); see [M10 — Smart
  Turn v3.2](#m10--smart-turn-v32-optional-semantic-end-of-turn) below for the
  device checks (model load, inference time, veto/hold behavior, silence cap,
  missing/corrupt model).
- **M11 on-device TTS** — implemented; see [M11 — On-device
  text-to-speech](#m11--on-device-text-to-speech) above for the manual checks.
- **M13 credentials** — implemented; see the [M13
  section](#m13--credential-storage-and-provider-capability-registry) above
  (AndroidKeyStore store/replace/remove, restart, no secret in logs, crashes,
  backups, or QR payloads).
- **M14–M19 providers** — one section each: destination disclosure, model
  selection, streaming, cancellation, network-loss and auth errors, and proof
  that no silent provider/model fallback occurs.
- **M20 local LLM runtimes** — implemented (JVM only; device run deferred); see
  the [M20 section](#m20--local-model-runtimes-and-curated-catalog) below for
  availability, unprovisioned/corrupt/insufficient-resource paths, and the manual
  memory/thermal/STT-TTS-contention rows.
- **Current `dev/STT` voice path** — capture → VAD → STT → optional Smart Turn →
  persisted transcript, plus latency/accuracy measurement. LLM, assistant
  generation, and TTS are not connected to this microphone path.
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

### Debug credential import (developer/device aid)

`scripts/push-credentials.sh` + the debug-only `DebugVoiceChatApplication`
pre-load a provider key from the gitignored `secrets/` directory into the
Keystore-backed store, so a device run does not require retyping the key. See
[docs/credentials.md](./docs/credentials.md#debug-credential-import-developerdevice-only).

```powershell
# 1) put the key in the gitignored file, then from WSL:
bash scripts/push-credentials.sh          # adb push -> run-as cp into files/debug-credentials/
# 2) install/launch the debug build; the debug app imports it on launch.
```

Verified **2026-09-30** on the Pixel 10 (Android 17, `debug`):
`adb push` of a 68-byte `secrets/opencode-go.key` followed by a cold launch
logged `credentials: stored provider=opencode-go kind=API_KEY` and
`debug-credentials: imported OpenCode Go credential from opencode-go.key`; the
plaintext file was deleted (`files/debug-credentials` empty) and
`shared_prefs/voicechat-credentials.xml` held only a Base64 ciphertext blob with
no `OPENCODE_API_KEY` plaintext. The parser is covered on the JVM by
`debug.DebugCredentialImportTest`. This is a developer aid, not a product
feature; release builds do not compile it.

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

Live capture -> VAD -> STT -> transcript persistence and the STT latency/accuracy
measurements remain **manual/device** work for M25; do not claim them from the
JVM suite. LLM/TTS response and barge-in behavior are outside the current
`dev/STT` microphone path.

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

### LLM-only harness (JVM, opt-in, run on 2026-10-17)

`com.voicechat.agent.eval.llm.VoiceLlmHarnessTest` drives the **real adapter**
through the M12 `LanguageModel` contract for every model the voice loop may
select, with the thinking parameter `docs/voice-llm-selection.md` recommends, and
records the timings and the verbatim answers. It is skipped unless
`VOICECHAT_LLM_HARNESS=1` and `OPENCODE_API_KEY` are set.

```powershell
# Reads secrets/opencode-go.key, sets the environment, runs the harness, and
# prints the record path. Three attempts per model; the record shows the median.
.\scripts\run-llm-harness.ps1
.\scripts\run-llm-harness.ps1 -OutputPath build/llm-harness/yosemite-2026-10-17.md -Repeats 5
```

It **was run** on 2026-10-17 with three attempts per model; the medians and the
answers are recorded in
[voice-llm-results.md](./docs/voice-llm-results.md#harness-run-2026-10-17). It is
host-side only — no Pixel, microphone, or TTS — so it is model-choice evidence
from the host, not a device result.

### On-device LLM harness (Pixel 10, run on 2026-10-17)

`com.voicechat.agent.eval.llm.VoiceLlmHarnessInstrumentedTest` runs the same
harness and the same case list **on the phone**, so the timings include the
device's network path and runtime. It is opt-in (`-e llmHarnessRunId`), takes its
credential from the app's AndroidKeyStore-backed store, and writes a content-free
`summary.md` plus a `record.md` with the answers into app-private cache.

```powershell
# Stages the credential, builds/installs, runs the instrumented harness, and
# copies both reports into build/llm-harness-device/<runId>/.
.\scripts\run-llm-harness-device.ps1
.\scripts\run-llm-harness-device.ps1 -Repeats 5 -DeviceSerial 57220DLCR002R6
```

It **was run** on 2026-10-17 on Pixel 10 (`frankel`), Android 17 / API 37, build
`google/frankel/frankel:17/CP3A.261005.005/16271449:user/release-keys`, three
attempts per model, on Wi-Fi, USB-powered at 80%, battery 29.0 °C (sampled after
the run). All eight configurations completed; the medians are in
[voice-llm-results.md](./docs/voice-llm-results.md#device-run-pixel-10-2026-10-17).
Still unmeasured: TTS time-to-first-audio, echo behaviour per route, and the
end-to-end voice turn (R-0254, R-0255).

## M18 — OpenCode Zen adapter

M18 is a **JVM-first** milestone. The adapter is pure Kotlin behind the M12
contract and reuses the M14 remote transport; every protocol case is a recorded
SSE fixture or a scripted engine with **no socket, clock, DNS, or real
credential**. There is no M18-specific instrumented test, so no row is added to
the automated on-device table above; the existing instrumented tests were
compiled only. See [docs/opencode-zen-adapter.md](./docs/opencode-zen-adapter.md).

OpenCode Zen is deliberately **not** treated as OpenAI-compatible or as OpenCode
Go: its own endpoint table places each model on one of three chat protocols
(Chat Completions for GLM/Kimi/DeepSeek/Qwen3.8 Max, Responses for GPT/Grok/Muse,
Anthropic Messages for Claude/Qwen Plus), and the adapter dispatches on the model
and refuses an unplaced id. Zen's `/systemone` (Jev) decision models and the
Google `/models/<model>` family are never surfaced as chat models.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no network): fixture replay for the three
# chat families, the `/systemone` and Google-family refusals, the unplaced-model
# and unsupported-reasoning refusals, and the ordinary auth/rate-limit/server/
# network/terminal-less errors.
.\gradlew.bat :app:testDebugUnitTest --tests "*OpenCodeZen*"

# Compile the instrumented tests WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves instead (details in the linked document):

- the request goes to the **documented endpoint for the selected model's family**
  (`/chat/completions`, `/responses`, or `/messages`) with
  `Authorization: Bearer <credential>`; **no** Go-specific `x-opencode-session`
  header is sent (Zen documents none); the Responses family always sends
  `"store": false`;
- Chat Completions `data: [DONE]`, Responses `response.completed`, and Messages
  `message_stop` each terminate the stream, and Messages input/output tokens are
  merged into one usage report;
- the reasoning channel (`response.reasoning_*.delta`, `thinking_delta`) is
  excluded from assistant text;
- **`/systemone` (Jev) is never a chat model**: `jev-1.13`/`jev-1.13-free` are
  refused with `LLM_INVALID_REQUEST` **before any request is sent**, and the
  Gemini Google-family ids are refused the same way because that family is not
  implemented;
- an unplaced model (`deepseek-v4-flash-free`, `claude-sonnet-5-5`) is refused
  before any request is sent — no protocol is assumed for it;
- a requested reasoning level is refused before send because Zen documents no
  reasoning control (capability declares none);
- 401/403 → `LLM_AUTHENTICATION_FAILED`, 429 → `LLM_RATE_LIMITED`, other 4xx →
  `LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`, a dropped connection →
  `LLM_NETWORK_FAILED`, both as streamed error frames and as HTTP statuses;
- a terminal-less stream is `LLM_MALFORMED_RESPONSE`, never a completion; a
  mid-stream cancellation emits no terminal event;
- no credential validator is offered (Zen's `GET /models` answers without a key),
  and the registry Zen row claims no reasoning and marks usage/streaming
  unverified;
- no credential, prompt, or assistant text appears in the developer log.

### Opt-in real-provider smoke test (marked NOT run)

`providers.opencodezen.OpenCodeZenSmokeTest` is the one M18 test that can touch
the real service. It is **skipped** (JUnit `Assume`), and so never runs in
routine CI, unless **both** `VOICECHAT_OPENCODE_ZEN_SMOKE=1` and
`OPENCODE_ZEN_API_KEY` are set:

```powershell
$env:VOICECHAT_OPENCODE_ZEN_SMOKE = "1"
$env:OPENCODE_ZEN_API_KEY = "<your key>"                          # externally supplied only
$env:VOICECHAT_OPENCODE_ZEN_SMOKE_MODEL = "glm-5.3-flash"        # optional; try gpt-5.6-luna, claude-sonnet-4-5
.\gradlew.bat :app:testDebugUnitTest --tests "*OpenCodeZenSmokeTest"
```

It **was not run** for this milestone. This is tracked as R-0148. What to record
for every run:

- Device model/build, Android version, build variant (`debug`), commit, and the
  selected model and family.
- Network/region conditions and the observed time to first text and total
  completion time.
- The completion outcome and any typed failure reason — **never** the prompt or
  the response text, and **never** the API key. The smoke test itself asserts on
  completion/counts only and prints no content.
- Whether each family's live SSE framing matches the mapping (R-0141).

Device-level latency, cancellation-acknowledgement timing, and live provider
behavior remain unmeasured and are tracked in R-0141 and R-0148. **Do not mark
any of these passed unless the command was actually run.**

## M16 — DeepSeek adapter

M16 is a **JVM-first** milestone. The DeepSeek adapter is pure Kotlin behind the
M12 contract and reuses the M14 shared transport; every protocol case is a
recorded SSE fixture or a scripted engine with **no socket, clock, DNS, or real
credential**. There is no M16-specific instrumented test, so no row is added to
the automated on-device table above; the existing instrumented tests were
compiled only. See [docs/deepseek-adapter.md](./docs/deepseek-adapter.md).

The verified DeepSeek facts (endpoint, auth, models, streaming shape, thinking
controls, usage, error codes) and their sources are recorded with the
**2026-09-29** access date in that document.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no network): DeepSeek fixture replay for
# the normal stream, empty response, malformed frame, truncated response, rate
# limit, auth failure, insufficient balance, server error, network loss,
# cancellation, and the reasoning-channel exclusion.
.\gradlew.bat :app:testDebugUnitTest

# Compile the instrumented tests WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves instead (details in the linked document):

- the request goes to the documented `POST /chat/completions` (base
  `https://api.deepseek.com`) with `Authorization: Bearer <credential>` and
  `"stream": true`, and the selected model is sent verbatim; DeepSeek's Chat
  Completions API documents no `store`-style field, so none is fabricated;
- `choices[].delta.content` frames become deltas, `choices[].delta.reasoning_content`
  is a **separate channel** never concatenated into assistant text (R-0066), and
  the finish-reason chunk carries `usage`/`model`;
- `reasoning == null` omits thinking, `NONE` sends `thinking.type = disabled`, and
  a real level sends `thinking.type = enabled` plus `reasoning_effort`;
- `finish_reason = length` (truncated), `content_filter`,
  `insufficient_system_resource`, and `aborted` are typed failures, not
  completions; a stream with no finish reason and no `[DONE]` is
  `LLM_MALFORMED_RESPONSE` (R-0067);
- 401/403 → `LLM_AUTHENTICATION_FAILED`, 429 → `LLM_RATE_LIMITED`, 408/504 →
  `LLM_TIMEOUT`, other 4xx (including DeepSeek's documented 402) →
  `LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`, a dropped connection →
  `LLM_NETWORK_FAILED`, and a streamed `error` body is mapped from its stable
  `code`/`type` only (R-0124 tracks the 402 gap);
- a mid-stream network loss and a mid-stream cancellation keep the partial text
  as partial and never report completion;
- the credential check (`GET /models`) maps 401/403 to an authentication
  rejection and keeps a transient failure typed;
- a missing credential is `LLM_NOT_CONFIGURED` and no request is sent;
- the adapter declares the re-verified provider capability (streaming, usage,
  and the accepted reasoning union), and an unsupported level is refused before
  any request is built;
- HTTP/JSON library types stay inside the transport (`RemoteSourcePurityTest`);
  no credential, prompt, or assistant text appears in the developer log.

### Opt-in real-provider smoke test (marked NOT run)

`providers.deepseek.DeepSeekSmokeTest` is the one M16 test that can touch the
real service. It is **skipped** (JUnit `Assume`), and so never runs in routine
CI, unless **both** `VOICECHAT_DEEPSEEK_SMOKE=1` and `DEEPSEEK_API_KEY` are set:

```powershell
$env:VOICECHAT_DEEPSEEK_SMOKE = "1"
$env:DEEPSEEK_API_KEY = "<your key>"                  # externally supplied only
$env:VOICECHAT_DEEPSEEK_SMOKE_MODEL = "deepseek-flash" # optional
.\gradlew.bat :app:testDebugUnitTest --tests "*DeepSeekSmokeTest"
```

It **was not run** for this milestone. This is tracked as R-0121. What to record
for every run:

- Device model/build, Android version, build variant (`debug`), commit, and the
  selected model and reasoning level.
- Network/region conditions and the observed time to first text and total
  completion time.
- The completion outcome and any typed failure reason — **never** the prompt or
  the response text, and **never** the API key. The smoke test itself asserts on
  completion/counts only and prints no content.

Device-level latency, cancellation-acknowledgement timing, and live DeepSeek
behavior remain unmeasured and are tracked in R-0121. **Do not mark any of these
passed unless the command was actually run.**

## M19 — Hermes Agent API Server adapter

M19 is a **JVM-first** milestone. The Hermes adapter is pure Kotlin behind the M12
contract and reuses the M14 remote transport; every protocol case is a recorded
SSE fixture, a scripted engine, or the in-process loopback mock server, with **no
external network, no device, and no real credential**. There is no M19-specific
instrumented test, so no row is added to the automated on-device table above; the
existing instrumented tests were compiled only. See
[docs/hermes-adapter.md](./docs/hermes-adapter.md).

Hermes is a **user/admin-deployed** server with **no default endpoint**: the
adapter requires an explicit destination, requires TLS for non-local hosts (M13
rules), shows the destination before text is sent, and treats the server as an
agent runtime that executes tools on its own host. The verified facts (endpoints,
bearer auth, streaming shape, reasoning channel, model discovery) and their
**2026-09-29** access date are recorded in that document.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no external network): fixture replay for
# the normal stream, empty response, malformed frame, truncated/terminal-less
# stream, auth/rate-limit/server errors, network loss, cancellation, reasoning
# exclusion, destinations, and the protocol/discovery mapping.
.\gradlew.bat :app:testDebugUnitTest --tests "*Hermes*"

# The loopback mock-server tests drive the real OkHttp engine against an
# in-process Hermes mock (JDK HttpServer, no new dependency): normal stream,
# bearer auth failure, server error, redirect not followed, cancellation.
.\gradlew.bat :app:testDebugUnitTest --tests "*HermesMockServerTest"

# Compile the instrumented tests WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves instead (details in the linked document):

- the request goes to the **configured** `{base}/chat/completions` with
  `Authorization: Bearer <credential>`, `Accept: text/event-stream`, and
  `"stream": true`, and the selected model alias is sent verbatim;
- the destination is disclosed (`ServerDestination.disclosure()`) before any
  request, and the config model carries the server-tools notice;
- valid loopback `http` and remote `https` are accepted; a remote `http`, a
  non-http(s) scheme, an embedded `user:pass@host`, an empty address, and any
  QR-sourced value are refused with a typed code (TLS for non-local hosts);
- `choices[].delta.content` frames become deltas, the custom
  `hermes.tool.progress` **named event** is ignored (never assistant text), and
  `choices[].delta.reasoning_content` is a separate channel never concatenated
  into assistant text (R-0066);
- `data: [DONE]` (or a `finish_reason` fallback) completes; a `finish_reason` of
  `length` is a truncated failure; a stream with neither is
  `LLM_MALFORMED_RESPONSE` (R-0067);
- 401/403 → `LLM_AUTHENTICATION_FAILED`, 429 → `LLM_RATE_LIMITED`, other 4xx →
  `LLM_INVALID_REQUEST`, 5xx → `LLM_UNAVAILABLE`, a dropped connection →
  `LLM_NETWORK_FAILED`, and a streamed `error` body is mapped from its stable
  `type`/`code` only;
- a **302 is never followed** (the OkHttp engine disables redirects); the mock
  records that the redirect target is never requested (R-0074);
- a mid-stream cancellation emits no terminal event and the engine observes the
  cancellation (both with the scripted engine and over the loopback socket);
- a missing credential is `LLM_NOT_CONFIGURED` and no request is sent; an
  unsupported reasoning level is refused before any request is built, and
  `ReasoningLevel.NONE` sends the documented `model_options.reasoning.enabled =
  false` opt-out;
- the credential check (`GET /v1/models`) maps 401/403 to an authentication
  rejection and keeps a transient failure typed;
- the registry row declares streaming verified and keeps reasoning/usage marked
  unverified; no credential, prompt, or assistant text appears in the developer
  log.

### Opt-in real-server smoke test (marked NOT run)

`providers.hermes.HermesSmokeTest` is the one M19 test that can touch a real
Hermes Agent API Server. Because Hermes has no default endpoint, the **address
must be supplied externally**. It is **skipped** (JUnit `Assume`), and so never
runs in routine CI, unless **all** of `VOICECHAT_HERMES_SMOKE=1`,
`HERMES_BASE_URL`, and `HERMES_API_KEY` are set:

```powershell
$env:VOICECHAT_HERMES_SMOKE = "1"
$env:HERMES_BASE_URL = "https://hermes.example.com/v1"   # or http://127.0.0.1:8642/v1 for a local loopback server
$env:HERMES_API_KEY = "<your API_SERVER_KEY>"            # externally supplied only
$env:VOICECHAT_HERMES_SMOKE_MODEL = "hermes-agent"       # optional
.\gradlew.bat :app:testDebugUnitTest --tests "*HermesSmokeTest"
```

It **was not run** for this milestone. This is tracked as R-0150. What to record
for every run:

- Device model/build, Android version, build variant (`debug`), commit, the
  Hermes server version/profile, and the selected model.
- Network conditions and the observed time to first text and total completion
  time.
- The completion outcome and any typed failure reason — **never** the prompt or
  the response text, and **never** the API key. The smoke test itself asserts on
  completion/counts only and prints no content.
- Whether the live SSE framing (including the `hermes.tool.progress` event and
  the terminal frame) matches the mapping (R-0159).

Device-level latency, cancellation-acknowledgement timing, and live Hermes
behavior remain unmeasured and are tracked in R-0150, R-0156, and R-0159.
**Do not mark any of these passed unless the command was actually run.**

## M23 — Text-first end-to-end provider vertical slice

M23 is a **JVM-first** milestone. The vertical slice is proven by
`:app:testDebugUnitTest` with the **real** M17 OpenCode Go adapter over recorded
SSE fixtures, the real M05 context builder and repository, and the M13
credential-store seam. There is no device, network, socket, DNS, microphone, or
real credential in the automated path, so it runs credential-free in CI. See
[text-first-slice.md](./docs/text-first-slice.md).

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no network): full lifecycle, cancel,
# retry, typed failure, selection-driven adapter, bounded context, trace privacy.
.\gradlew.bat :app:testDebugUnitTest

# Lint / format / assemble
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck

# Compile the instrumented sources WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

What the JVM suite proves (details in the linked document):

- **Full lifecycle.** `ui.TextFirstSliceTest` sends a turn through the real
  state holder, the real registry factory, and the real OpenCode Go adapter over
  the recorded `chat_normal.sse` fixture: the deltas render, the assistant turn
  is persisted as `COMPLETED`, and reopening restores the same content.
- **Streamed deltas are visible before completion**, with a deterministic
  delayed fake so intermediate `liveAssistantText` is asserted.
- **Cancellation** mid-stream persists an interrupted (never completed) turn
  with only the delivered prefix.
- **Retry** after a failure replaces the failed reply without duplicating the
  user turn.
- **A typed provider failure** (`chat_error.sse` -> `LLM_RATE_LIMITED`) is shown
  and never persisted as a success; nothing was generated, so no phantom
  assistant turn is stored.
- **The persisted selection drives the adapter (R-0103).** Changing the M22
  model from `glm-5.3-flash` to `gpt-5.6-luna` in the same dialog resolves the
  other protocol family: the second request goes to `/responses`, the factory
  receives the new selection, and the trace names the new model.
- **The honest not-configured state** is kept when no provider/model is
  selected: `LLM_NOT_CONFIGURED`, and the provider factory is never consulted.
- **Bounded context.** A 30-turn stored history produces a request within the
  M05 `ModelContextBuilder` bounds (20 messages / 4000 chars), with the newest
  turn present and the oldest not resent.
- **Privacy-safe trace.** The whole turn is one trace whose attributes never
  contain the prompt or the reply.
- **Disclosure (R-0097, R-0139).** `ui.TextFirstSliceUiTest` (Robolectric)
  asserts the dialog shows the provider and model, the remote-transfer notice,
  and the registry's retention/training note before send, and the honest hint
  when nothing is selected.

### Opt-in real-provider smoke test (marked NOT run)

`ui.TextFirstSliceSmokeTest` is the one M23 test that can touch the real
OpenCode Go service, through the **same** registry-driven factory and selection
resolver the app uses. It is **skipped** (JUnit `Assume`), and so never runs in
routine CI, unless **both** `VOICECHAT_TEXT_SLICE_SMOKE=1` and
`OPENCODE_API_KEY` are set:

```powershell
$env:VOICECHAT_TEXT_SLICE_SMOKE = "1"
$env:OPENCODE_API_KEY = "<your key>"                          # externally supplied only
$env:VOICECHAT_TEXT_SLICE_SMOKE_MODEL = "glm-5.3-flash"       # optional; try gpt-5.6-luna, qwen3.8-flash
.\gradlew.bat :app:testDebugUnitTest --tests "*TextFirstSliceSmokeTest"
```

It **was not run** for this milestone. What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit, and the
  selected model and family.
- Network/region conditions and the observed time to first text and total
  completion time.
- The completion outcome and any typed failure reason — **never** the prompt or
  the response text, and **never** the API key. The smoke test asserts on
  completion/delta counts only and prints no content.

### Opt-in Pixel 10 smoke run (marked NOT run)

The device run exercises the real AndroidKeystore credential store and a real
network request through the app. It requires a user-configured,
provider-supported connection. **Device testing is deferred for M23; this run
was not performed.** Suggested procedure:

1. Build and install the debug APK: `.\gradlew.bat :app:installDebug`.
2. Open **Settings**; choose the **OpenCode Go** provider; choose a documented
   model (for example `glm-5.3-flash`); store your API key (it is written only
   to the AndroidKeystore-backed store).
3. Confirm the settings surface shows the destination
   (`https://opencode.ai/zen/go/v1`) and the retention/training note before any
   send.
4. Open a new conversation and type a short message. Confirm the disclosure
   banner names the provider/model and the remote-transfer notice, the reply
   streams in, and the finished conversation is still present after
   `adb shell am force-stop com.voicechat.agent` and relaunch.
5. Cancel mid-stream once and confirm the turn is stored as cancelled with only
   the delivered prefix; retry once and confirm the failed reply is replaced
   without duplicating the user turn.

What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit, provider
  and model, and the network/region conditions.
- Time to first streamed text and total completion time; the cancel and retry
  outcomes; the persisted conversation shape (turn count and generation state).
- That the settings selection and a stored credential actually drove the turn
  (the destination and model in the disclosure matched the request), and that
  no silent provider/model fallback occurred.
- The outcome only — **never** the prompt, the response text, or the API key.

Device-level behavior (real streaming framing, cancellation acknowledgement,
and the Keystore round-trip under the app) remains unmeasured and is tracked in
R-0138, R-0161, and the M17 section. **Do not mark any of these passed unless
the run was actually performed.**

## M24 - Voice loop and responsive barge-in

The current microphone path is capture, VAD/onset, on-device STT, optional Smart
Turn, transcript persistence, and — with an assistant wired — the LLM reply with
on-device TTS and VAD barge-in; see [voice-loop.md](docs/voice-loop.md).

M24 is a **JVM-first** milestone. The voice loop is a pure-Kotlin coordinator
(`com.voicechat.agent.voice`) wired over the M02 contracts, so the acceptance
behavior is proven by `:app:testDebugUnitTest` with deterministic fakes and the
M03 replay fixtures - no device, network, credential, microphone, or real
STT/TTS. The only platform file is `VoiceSessionAssembly.kt`, enforced by
`VoiceSourcePurityTest`. See [docs/voice-loop.md](./docs/voice-loop.md).

### Wired reply and barge-in (dev/LLM)

`VoiceAssistantLoopTest` covers the sequencing rules that the M24 guidelines
require, and `EchoCancellationTest` covers the capture-side echo wiring:

- **A finalized transcript is answered.** The assistant receives the conversation
  *before* the user turn, persists both turns itself, and the coordinator writes
  nothing — proving the loop never double-persists a transcript.
- **VAD onset interrupts first.** With the reply still outstanding, a speech-onset
  event interrupts the assistant before any transcript, recognizer result, or
  Smart Turn decision exists, returns the state to `LISTENING`, opens the next
  recognition turn, and does not wait for the interrupted reply to settle.
- **A failed reply stays visible.** A typed provider failure reaches
  `VoiceSessionListener.onError` and the session keeps listening.
- **Echo.** The capture path attaches the platform echo canceller per session,
  records its state (`ENABLED`/`NO_SESSION`/`UNAVAILABLE`/`FAILED`) as an
  `AUDIO_INPUT` diagnostic, and releases it when the session ends.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device): the isolated STT path, the wired reply
# and barge-in sequencing, echo-canceller wiring, stale-event dropping, and
# delivered-only history.
.\gradlew.bat :app:testDebugUnitTest

# Lint / format / assemble
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck

# Compile the instrumented sources WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest
```

The on-device matrix below is **still not run** for the wired loop: time to first
audible audio, echo behaviour per route, and a real barge-in remain unmeasured
(R-0254, R-0255).

What the JVM suite proves (details in the linked document):

- **Live text.** The committed voice turn shows interim revisions provisionally,
  streams assistant text into the dialog, and persists only the final transcript.
- **Interruptions at multiple LLM/TTS points.** A barge-in during LLM streaming,
  during TTS playback, and before any assistant text each stop playback, cancel
  generation, and start the next capture **without waiting** for the
  cancellation to settle (`captureResumedAtNanos` is recorded before
  `settledAtNanos`).
- **No stale events.** Interim revisions must be strictly newer; a late interim
  after the endpoint is dropped; live assistant text is tagged with, and only
  accepted for, the active generation turn; a settling turn cannot clobber a
  newer turn's state.
- **Delivered-only history.** An interrupted reply is stored with only the
  audible prefix and `INTERRUPTED`; a turn that generated nothing stores no
  phantom assistant turn; a completed next turn is stored after the interrupted
  one.
- **Explicit recovery.** A false/noise interruption commits no turn and leaves
  the interrupted reply interrupted (`NO_USABLE_SPEECH`); a short acknowledgement
  or true interruption commits as a new turn (`COMMITTED`).
- **Recorded timing.** `onsetToStopNanos`, `onsetToCaptureResumedNanos`, and
  `onsetToSettledNanos` are captured per barge-in, and `BARGE_IN_STOP_MILLIS` /
  `BARGE_IN_CAPTURE_RESUMED_MILLIS` reach the M04 diagnostics sink.
- **Stop.** `stop()` ends the session as `STOPPED` and cancels in-flight work
  while still persisting what was truthfully delivered.

`voice.VoiceSessionInstrumentedTest` **was not run** for this milestone; per the
device-testing policy it is compiled only, and it is a structural smoke check
with inline fakes, not a voice-quality or timing measurement.

### Platform SpeechRecognizer STT (M27; Pixel 10 run recorded 2026-10-16)

The STT engine is the platform `android.speech.SpeechRecognizer`, preferred
on-device (`createOnDeviceSpeechRecognizer` / `EXTRA_PREFER_OFFLINE`), with the
recognizer's `EXTRA_AUDIO_SOURCE` pipe fed from the captured PCM frames. It
replaced the app-managed Parakeet-EOU ONNX engine, which was removed; see
[docs/android-speech-recognition.md](./docs/android-speech-recognition.md).

The debug-only `DebugFileAudioInput` (selected when `files/debug-audio/input.wav`
or `input.m4a` exists; release ignores it) replays a recording through the real
`capture → VAD/onset → platform STT` path with the UI in the foreground, so no
live speech is needed. On Pixel 10 the staged-file harness path (below) returned
partials and a final transcript for both clips, confirming the recognizer reads
the piped PCM (R-0243). Recognition accuracy is not asserted; the transcripts are
recorded for review.

### STT pipeline stage timing harness (opt-in; run on Pixel 10, 2026-10-16)

`SttPipelineHarnessInstrumentedTest` (`app/src/androidTest/.../voice/`) replays a
recorded clip (WAV or M4A, decoded on the device) as real-time paced 20 ms frames
through the **real** `VoiceSessionCoordinator`, the M09 VAD/onset policy, the
platform `SpeechRecognizer` adapter, and — when enabled — the M10 Smart Turn
corroborator. It logs every VAD, Smart Turn, and STT stage to logcat under the
`SttHarness` tag and writes two content-free reports to app-private cache:
`summary.csv` (per-clip timing: capture-start/onset to first partial, VAD-endpoint
and end-of-speech to final, input-end to final, partial count) and `stages.csv`
(the full timestamped stage timeline). The CSVs hold only character counts; with
`-e sttHarnessLogTranscripts true` (the driver's default) the partial revisions
and the final text are additionally logged to logcat.

It is opt-in: without `-e sttHarnessRunId <id>` it is skipped, so ordinary
`connectedDebugAndroidTest` runs never replay personal audio. It also skips on a
device that exposes no on-device recognizer.

Run (Pixel 10 `57220DLCR002R6`, Android 17 / API 37, 2026-10-16, VAD-only):
both clips finished `FINAL`. `Walking .m4a` (22.7 s): first partial 8077 ms after
capture start (1131 ms after onset), VAD-endpoint → final 343 ms, input-end →
final 167 ms, 43 partials, final `Latest news please`. `eng-000001-000081dd.wav`
(13.0 s): first partial 1608 ms (585 ms after onset), VAD-endpoint → final
269 ms, input-end → final 1542 ms, 81 partials, final `What is the complete
government process for applying for a passport for a minor under the age of 16
including all necessary documentation parental consent forms and Associated
official fees`. The engine's `onEndOfSpeech` fired 14273 ms before the final on
the long clip, so it is not a turn boundary. One recording each; not accuracy
claims. Reports: `build/stt-harness/<runId>/`.

```powershell
# Replays test_data/Walking .m4a and test_data/eng-000001-000081dd.wav through the
# real capture -> VAD -> platform STT -> bounded endpoint path with timing reports.
.\scripts\run-stt-pipeline-harness.ps1
# Pass -InputPath <clips> to choose clips; -SmartTurn to include the Smart Turn
# corroborator when its model is staged.
```

### Pixel 10 checks (marked NOT run)

**Device testing is deferred for M24; none of the following was run.** The
JVM suite proves the logic and the recorded timings, not real echo/noise
behavior or audible latency. Onset-to-stop latency, echo/noise false-interrupt
behavior, first-word interruptions, double-talk, and route changes must be
measured on the device; do not copy thresholds, gain/RMS values, or a fixed
playback grace period from another project (R-0045).

What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit.
- Active input and output route kinds (built-in speaker/mic, wired headset,
  Bluetooth) and the `VadConfig` in effect at start.
- Per barge-in: the onset-to-stop time (`BARGE_IN_STOP_MILLIS`), the
  onset-to-capture-resumed time (`BARGE_IN_CAPTURE_RESUMED_MILLIS`), and the
  onset-to-settled time; whether the reply was persisted with only its delivered
  prefix, and the recovery (`COMMITTED`/`NO_USABLE_SPEECH`).
- The spoken/played phrase, the observed `SPEECH_STARTED`/`SPEECH_RESUMED`/
  `ENDPOINTED` events, and whether the assistant's unheard text was excluded from
  history.

| Check | How | Record |
| --- | --- | --- |
| End-to-end voice turn | Speak a short phrase on the built-in speaker/mic path. | Live interim text, streamed assistant text, on-device TTS playback, and a persisted user+assistant turn; no silent provider/model fallback. |
| First-word interruption | Start a long reply, then speak as soon as the first word is audible. | Playback stops on the fast onset (no fixed grace period); the new utterance is captured immediately; onset-to-stop time recorded. |
| Echo / built-in speaker | Play the assistant aloud through the built-in speaker and stay silent. | No false interruption from speaker echo; if the VAD flags it, the recovery is `NO_USABLE_SPEECH` and the reply is not falsely completed. Measure, do not tune by copying threshold values. |
| Road / wind noise | Repeat in a car (road/wind noise) with speech and with silence. | False-interrupt count and recovery per condition; no long fixed grace period hides the trigger. |
| Music | Play loud music, with and without speech. | The energy/ZCR detector may report activity for music; record the false-interrupt rate and whether a duration/echo layer is needed (R-0062). |
| Double-talk | Speak continuously while the assistant is speaking. | Playback stops, generation cancels, the new utterance becomes its own turn, and history keeps only what was delivered. |
| Route change | Plug/unplug a wired headset and connect/disconnect Bluetooth during capture and during playback. | The route kind changes; capture/playback continue or fail with a typed error; no crash. |
| Stop / cancel timing | Press stop mid-reply, and barge in once. | `STOPPED` state; in-flight provider/TTS cancelled; onset-to-stop and onset-to-settle timings recorded; delivered-only history persisted. |
| AEC investigation | Compare built-in-speaker vs headset false-interrupt rates. | Decide whether echo cancellation is needed from measured evidence; no AEC is added and no GVP thresholds are copied. |

Unrun device items are tracked as R-0046, R-0050, R-0060, R-0063, R-0080,
R-0081, R-0082, and R-0083; **do not mark any row passed unless it was run on the
device.**

## M10 - Smart Turn v3.2 (optional semantic end-of-turn)

Path: the optional `turn.SmartTurnCompletionDetector` over the pinned
`smart-turn-v3.2-int8.onnx` artifact behind the M02 `TurnCompletionDetector`
contract, invoked once per VAD-confirmed candidate pause by the M09
`BoundedTurnEndpointPolicy`. It is **opt-in and default off**
(`docs/decisions.md` §3.3, [docs/smart-turn.md](./docs/smart-turn.md)); with it
off the behavior is exactly the M09 VAD-only bounded endpoint.

Run the JVM suite first; it proves config validation, the adapter contract,
missing/corrupt model handling, install/integrity, the disabled path, and that the
detector runs once per candidate pause (never per frame). Then run the
compile-only instrumented build, and on the device the M10 instrumented test:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

# Fast, deterministic checks (no device, no network, no native runtime, no model)
.\gradlew.bat :app:testDebugUnitTest

# Compile the instrumented test WITHOUT a device
.\gradlew.bat :app:assembleDebugAndroidTest

# Run the M10 instrumented test ON the device (the model must be installed first)
.\gradlew.bat :app:connectedDebugAndroidTest `
  "-Pandroid.testInstrumentationRunnerArguments.class=com.voicechat.agent.turn.SmartTurnInstrumentedTest"
```

`SmartTurnInstrumentedTest` (a) reports availability, (b) when the pinned model is
installed in the app-private directory, loads the real graph and asserts one
inference returns a finite probability in `[0, 1]`, and (c) proves a wrong-size
file is a typed `MODEL_CORRUPT` unavailable, never a success. When the model is
**not** installed it returns early (not `Assume`, which Gradle reports as an
assumption violation) and never fabricates availability. **No model is committed
to the repository.**

**Run 2026-09-30 (Pixel 10 `frankel`, Android 17, `CP3A.260905.009`, locked
bootloader, `:app:connectedDebugAndroidTest`):** `SmartTurnInstrumentedTest`
passed 2/2. The pinned model was **not installed** (`Smart Turn availability:
Missing`; artifact `smart-turn-v3.2-int8`
`b48fdbe20772bcec1fef02f4a1a355236ef6359e`), so device inference was correctly
**not exercised** and availability resolved to `DownloadRequired`; the
wrong-size→`MODEL_CORRUPT` check passed.

**Run 2026-09-30 (same device, pinned artifact installed via
`scripts/push-models.sh`, `adb shell am instrument
… SmartTurnInstrumentedTest` on the already-installed APKs so `files/` is not
wiped):** `SmartTurnInstrumentedTest` passed 2/2 and the real graph was
exercised: `Smart Turn availability: Installed(… smart-turn-v3.2-int8.onnx,
sizeBytes=11123370)`, artifact
`smart-turn-v3.2-int8 b48fdbe20772bcec1fef02f4a1a355236ef6359e`, and one
inference on a silence window returned `probability 0.40689537` (finite, in
`[0,1]`). Load/inference/memory/CPU durations and false-commit/false-hold counts
remain **unmeasured** (R-0190, M25).

> **Note:** `:app:connectedDebugAndroidTest` reinstalls the app and therefore wipes
> app-private `files/`, so the model must be re-pushed *after* the last Gradle
> install. To run the model-dependent tests without wiping `files/`, install both
> APKs with `adb install` and run `adb shell am instrument -w -e class
> com.voicechat.agent.turn.SmartTurnInstrumentedTest
> com.voicechat.agent.test/androidx.test.runner.AndroidJUnitRunner`.

What to record for every run:

- Device model/build, Android version, build variant (`debug`), commit.
- The artifact revision/size/SHA-256 actually installed and the integrity result;
  the `smartTurnEnabled` setting and the detector's resolved state.
- Load duration, inference duration, and probability per candidate pause
  (`SMART_TURN_LOADED` / `SMART_TURN_INFERENCE` diagnostics), and the verdict
  (`COMPLETE`/`INCOMPLETE`).
- Peak memory and CPU while a Smart Turn session runs alongside STT/LLM/TTS.

| Check | How | Record |
| --- | --- | --- |
| Availability report | Read the instrumented test's logged availability and the settings Smart Turn state. | Installed ? Available; missing ? DownloadRequired; wrong-size/hash ? Unavailable with a reason. Never claim a model is ready without the size+SHA-256 check. |
| Missing model | Uninstall/delete the model file and start a voice session with Smart Turn enabled. | `SmartTurnDetectorFactory` returns `null` with a typed `MODEL_UNAVAILABLE`; the bounded VAD-only endpoint still terminates every turn. |
| Corrupt model | Replace the model with a wrong-size or wrong-hash file. | Typed `MODEL_CORRUPT`; no load attempt; the user-visible reason appears in settings; the VAD-only endpoint still terminates. |
| Load + inference | With a verified model installed, run one candidate pause. | One inference; a finite probability in `[0, 1]`; load and inference durations recorded. |
| Veto / hold | Speak a partial thought that pauses naturally, then keep going. | `INCOMPLETE` holds the turn (`HELD(SEMANTIC_INCOMPLETE)`); resumed speech joins the **same** logical turn; exactly one final endpoint. |
| Complete / false hold | Speak a complete short phrase and stop. | `COMPLETE` finalizes at the candidate pause; record the false-hold rate. |
| Exact-once inference | Count `SMART_TURN_INFERENCE` events per candidate pause over a long turn. | Exactly one inference per candidate pause, never per audio frame; the fast onset/barge-in path never calls it. |
| Silence cap | With Smart Turn enabled but always holding, stay silent past the cap. | Exactly one `ENDPOINTED(SILENCE_CAP)`; the turn never hangs open. |
| Resources | Watch CPU/memory during a Smart Turn session with STT/LLM/TTS. | Sustained CPU/memory and whether inference competes with STT/TTS; no numbers are claimed until this runs. |

**Real model numbers are M25.** On-device load time, inference time, memory, CPU,
false-commit/false-hold tradeoffs, and threshold calibration are **unmeasured**
and tracked as R-0190; M10's JVM suite proves the contract and opt-in behavior
only. This section is a checklist, not a result: **do not mark any row passed
unless it was run on the device.**

## M20 — Local model runtimes and curated catalog

M20 adds runtime discovery for AICore / ML Kit GenAI, a LiteRT-LM adapter behind
the M12 `LanguageModel` contract, an allow-listed (currently empty) `.litertlm`
catalog, typed availability for every lifecycle state, and explicit
local-versus-remote selection. See [local-models.md](./docs/local-models.md) and
R-0210–R-0219.

### JVM suite (run)

`local.LocalModelCatalogTest`, `local.LocalAvailabilityMappingTest`,
`local.LocalModelStoreTest`, `local.LocalLanguageModelTest`,
`local.LocalVsRemoteSelectionTest`, and `local.LocalSourcePurityTest` cover:

- runtime discovery maps each availability state to the correct typed result, and
  only `AVAILABLE`/`Installed` becomes ready;
- catalog validation rejects entries missing required metadata or carrying a bad
  checksum, and an empty catalog is a valid honest result;
- unavailable / unprovisioned / missing / corrupt / insufficient-resource paths;
- the AICore and LiteRT-LM adapters' request→stream mapping and typed errors via
  fakes, including partial text on a mid-stream failure and "never open a session
  for a missing or corrupt model";
- local versus remote selection is explicit and never crosses over in either
  direction;
- only the three vendor/platform files import `android.*` / `com.google.*`.

Verified command (Windows PowerShell, `2026-09-29`, debug variant):

```
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug spotlessCheck :app:assembleDebugAndroidTest --console=plain
```

All tasks passed. The suite is device-free, network-free, and does not load the
native LiteRT runtime or a real model.

### Device checks (run 2026-09-30)

`local.LocalRuntimeInstrumentedTest` was run on device (Pixel 10 `frankel`,
Android 17, `CP3A.260905.009`, locked bootloader,
`:app:connectedDebugAndroidTest`) and **passed 5/5**. It reports the real AICore
availability, and (only if a local model is installed) attempts an init and
records load time and heap delta. It uses early returns, not `Assume`, so an
absent capability is a pass with its state logged, not an assumption failure.

Observed on the Pixel 10:

- AICore Prompt probe → `DownloadRequired`; the availability snapshot reported
  `Unavailable(aicore.gemini-nano, MODEL_UNAVAILABLE, "the system-managed model is
  downloadable but not provisioned")` — correctly **not** ready.
- Catalog status → `NoAllowListedModel` (the documented empty catalog).
- AICore generation and LiteRT-LM init were **not applicable** (model
  unprovisioned / no allow-listed bundle) and were not fabricated.

Cold/warm startup, memory, thermal behavior, latency, and STT/TTS contention
remain **unmeasured** (R-0215). Do not claim any M20 performance result until the
manual rows below are run.

### Pixel 10 measurements (manual; marked NOT run)

No performance claim is made for M20. Cold/warm startup, memory, thermal
behavior, latency, and contention with STT/TTS are measured later (R-0215). For
every run, record: device model/build, Android version, debug variant, commit,
AICore `checkStatus()` result, whether the model was provisioned, and the
installed allow-listed model id (if any).

| Check | How | Record |
| --- | --- | --- |
| AICore availability | Run `LocalRuntimeInstrumentedTest` (or read `checkStatus()` in the debug probe) with and without AICore provisioning. | `AVAILABLE`/`DOWNLOADABLE`/`DOWNLOADING`/`UNAVAILABLE` and the mapped typed state; `DOWNLOADABLE` must report unprovisioned, never ready. |
| AICore cold/warm startup | Generate a short reply once after process start (cold) and again in the same process (warm). | Time to first token and to completion, heap delta, thermal state; note that AICore load is system-managed. |
| LiteRT-LM cold/warm startup | With an allow-listed bundle installed, initialize an `Engine`, then reuse. | Init time, first-token latency, peak heap, storage occupied; confirm `initialize()` failure is reported as a failed init, never success. |
| Missing / corrupt model | Remove or truncate the installed bundle, then attempt a turn. | Typed `NOT_CONFIGURED`/`CORRUPT` state, no session opened, no false completion. |
| Insufficient resources | Run with heavy memory pressure / a model larger than the device can hold. | The `INSUFFICIENT_RESOURCES`/failed state; no crash, no fabricated reply. |
| Contention with STT/TTS | Run a full voice turn on AICore while STT and TTS are active. | First-token latency and thermal/memory delta versus a text-only turn; whether TTS playback disturbs the local model. |
| Barge-in with a local model | Interrupt an on-device generation mid-stream. | Cancellation stops generation promptly; stored history keeps only delivered text. |

Unrun M20 device items are tracked as R-0215; **do not mark any row passed unless
it was run on the device.**

## Optimization branch smoke run (2026-10-01)

Branch: `optimization/voice-latency`.

Device: Pixel 10 (`frankel`), Android 17 / API 37,
`google/frankel/frankel:17/CP3A.260905.009/16091614:user/release-keys`.

Command:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Outcome: **passed**. The JVM suite reported 917 tests, 0 failures, and 8
skipped. The connected Pixel 10 suite reported 24 tests, 0 failures, and 0
skipped. This included microphone/VAD capture checks, ML Kit availability,
platform TTS initialization/stop, the local-runtime availability checks, and a
fake-backed voice-session integration check.

The real STT capability probe reported ML Kit **Advanced** as ready and **Basic**
as requiring a download. The instrumented STT tests only checked availability
and adapter behavior; they did not transcribe any file from `test_data`. The
voice-session test uses fakes, and the platform TTS test is not a first-audio or
voice-quality benchmark. No end-to-end latency or recognition-quality baseline
was produced. The file-replay harness is tracked in
[voice-latency-optimization.md](./docs/voice-latency-optimization.md).

The first attempt was blocked at Android test compilation by a stale exhaustive
`when` in `AndroidTtsInstrumentedTest`; the missing
`SelectedVoiceUnavailable` branch was added before the passing run above.

### Optimization branch formatted-source smoke run (2026-10-01)

Command (Windows PowerShell, attached Pixel 10):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest :app:connectedDebugAndroidTest
```

Outcome: **Gradle passed**. The JVM reports contain 922 tests, 0 failures, and 8
skipped. Pixel instrumentation completed with 24 ordinary checks passing. The
two new paced replay methods were intentionally not run because no replay run id
was supplied; their `AssumptionViolatedException`s appear as two failures in the
raw Android XML report even though the connected Gradle task completed
successfully. The dedicated replay driver supplies the opt-in arguments.
`spotlessCheck` also passed after running `spotlessApply`.

This smoke run compiled the debug-only Moonshine adapter and verified APK
packaging with a single `libonnxruntime.so`. It did not load Moonshine models or
transcribe corpus files, so the Moonshine/ONNX version combination and all
candidate latency/accuracy results remain unverified.

### Paced speech replay on Pixel 10 (2026-10-01)

The dedicated runner stages local files into the target app's private cache,
then invokes only the replay instrumentation method; it does not use a live
microphone. The local `Walking*.m4a` files and the WAV fixtures remain outside
the APK and repository. Run from PowerShell with the phone attached:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\scripts\run-stt-paced-replay.ps1 `
  -Engine Both -Mode Both -DeviceSerial '57220DLCR002R6' `
  -OutputCsv "$env:TEMP\voicechat-stt-mlkit.csv" `
  -MoonshineOutputCsv "$env:TEMP\voicechat-stt-moonshine.csv" `
  -ExportDraftLabels -ExportMoonshineDraftLabels
```

Keep all run outcomes, latency/accuracy progress, and tested source hashes in
the single [speech latency results log](./docs/voice-latency-results.md).

The former offline Parakeet candidate lane (`-Engine Parakeet`, its
`scripts/fetch-parakeet-candidate.ps1`/`scripts/push-parakeet-candidate.ps1`
fetchers, and the `parakeet-tdt-ctc-110m-en-int8` sherpa-onnx model set) was
**retired in M27** with the app-managed Parakeet STT engine; see
[docs/android-speech-recognition.md](./docs/android-speech-recognition.md). The
LiteRT ASR benchmark lane below is separate.

## LiteRT ASR CPU instrumentation spike (optimization/litert-speech-benchmark)

This debug/test-only spike loads the converted sample Moonshine Tiny FP32 model
and consumes one 5-second, 16 kHz mono PCM window through the pinned sample's
`CompiledModel` encode/decode signatures and default token decoder. It does not
replace production ML Kit. The sample tokenizer JNI library is not wired into
this test target, so the test records token counts only; transcript text,
accuracy, and first text from clip start are explicitly unavailable. File input
is offline and unpaced, not a streaming-latency measurement.

The expected ignored artifact is
`models/litert-asr/moonshine-tiny-fp32-5s.tflite` (109,330,616 bytes,
SHA-256 `c4f4735852d4a8de1003431c43a253376c4c91ee19713778a07bd0a446c7e930`).
The fixture is `test_data/eng-000001-000081dd.wav`; neither file is packaged in
the APK or test APK.

After the test-only runtime and instrumented test are built, install both APKs,
stage the inputs in app-private storage, and invoke instrumentation directly.
Do not use `connectedDebugAndroidTest` for the staged run because Gradle
reinstalls the app and clears its private files:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebugAndroidTest
.\gradlew.bat :app:installDebug
```

From WSL at the clean worktree root, run `bash scripts/stage-litert-asr-inputs.sh`
after the debug app is installed. Then install the instrumentation APK from
PowerShell and run the single test:

```powershell
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb shell am instrument -w -e class com.voicechat.agent.eval.litert.LiteRtSpeechCpuInstrumentedTest com.voicechat.agent.test/androidx.test.runner.AndroidJUnitRunner
```

The runtime is pinned to LiteRT 2.1.5 and is an `androidTestImplementation`
only. The test requests CPU with a CPU-only accelerator allowlist; a successful
encode therefore records observed CPU execution with fallback disabled. Before
the encoder completes, observed backend is `UNKNOWN`; initialization or
inference failures remain failures and are not reported as successful fallback.
Logcat tag: `LiteRtSpeechCpuTest`. The JSON records model/audio hashes and sizes,
device/OS/SoC, cold load, audio read/preprocessing, encode, decoder-first-token,
decoder-total, and thermal/battery conditions. It does not log audio or text.

The current branch has no device result row unless the test is actually run with
the verified artifact on a connected phone. A missing staged input is logged as
`INPUTS_NOT_STAGED` and returns without timing; that is not a benchmark pass.

The Android test APK also includes a minimal exhaustive
`SelectedVoiceUnavailable` branch in the pre-existing M11 TTS instrumentation
probe. This test-only logging case unblocks compilation after the result type
gained that state; it does not change production TTS and is unrelated to LiteRT.

### LiteRT CPU/GPU/NPU evaluation probes

Five opt-in, debug-only instrumentation probes cover the LiteRT ASR spike. None
is part of the routine suite, and none transcribes audio (the sample tokenizer
JNI is not linked), so they produce token counts and timings only:

- `LiteRtSpeechCpuInstrumentedTest` — Moonshine Tiny FP32/FP16, CPU-only allowlist.
- `LiteRtSpeechGpuInstrumentedTest` — Moonshine Tiny FP16/FP32, GPU-only allowlist.
- `LiteRtParakeetComparisonInstrumentedTest` — official Parakeet CTC and TDT INT8 artifacts, CPU-only allowlist.
- `LiteRtParakeetGpuInstrumentedTest` — the same Parakeet artifacts, GPU-only allowlist.
- `LiteRtParakeetNpuInstrumentedTest` — the upstream F32 AOT Google Tensor G5 TDT artifact, NPU-only allowlist.

Stage inputs with `scripts/stage-litert-asr-inputs.sh` (`STAGE_PARAKEET=1` for the
Parakeet INT8 set, `STAGE_PARAKEET_NPU=1` for the F32 G5 artifact). The NPU probe
additionally needs `scripts/fetch-litert-npu-runtime.sh`, which installs the pinned
v2.1.1 dispatch library under the gitignored `models/` tree. Every result,
including failures, is recorded in
[the single speech latency results log](./docs/voice-latency-results.md).

**Corpus WER path.** The sample tokenizer JNI is linked so transcripts are real
text, not token IDs:

1. `scripts/fetch-litert-speech-sample.sh` then
   `scripts/build-tokenizer-jni.sh` (needs an NDK; `ANDROID_NDK_HOME`) builds
   `libtokenizer_jni.so` into the gitignored `models/litert-asr/tokenizer-jni/`;
   the debug APK ships it only when present.
2. `scripts/stage-litert-asr-inputs.sh STAGE_PARAKEET=1 STAGE_CORPUS=1` stages the
   Parakeet models, tokenizers, and the 16 kHz `eng-*.wav` fixtures.
3. `LiteRtParakeetFeatureParityInstrumentedTest` compares both the pure-Kotlin
   and jlibrosa front ends against the SHA-pinned frozen TDT tensor. It reports
   max/mean absolute error by mel bin and frame, including edge/interior
   summaries. TDT uses the pure-Kotlin HF/NeMo-compatible profile: 512-point FFT,
   400-sample symmetric Hann, constant center padding, Slaney area-normalized
   mel filters, mel-major layout, and 499 valid 10 ms hops plus one zero-padded
   frame. `SampleMelProcessor` remains the CTC/sample comparison; its jlibrosa
   defaults are not selected for TDT.
4. `LiteRtParakeetCorpusInstrumentedTest` preprocesses each clip on-device and
   writes `litert-asr/<model>--<clip>.transcript.txt` sidecars in app-private
   storage.
5. `scripts/local-whisper-reference.sh` produces offline local Whisper
   pseudo-references under the gitignored `test_data/litert-asr-wer/`.
6. `scripts/run-litert-wer.ps1` scores the pulled transcripts against those
   references with the M25 `SpeechQualityScorer` and writes a content-free CSV.

A reference produced by a local model is a pseudo-reference: its WER is agreement
with that reference, not human-reviewed gold. No audio, transcript, or model is
committed.

### R-0239 — TDT feature parity and corpus verification

Do not use `connectedDebugAndroidTest` for these staged runs: it reinstalls the
debug app and clears app-private staged files. Build/install first, stage after
both APKs are installed, and invoke each instrumentation class directly:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug :app:assembleDebugAndroidTest
.\gradlew.bat :app:installDebug
adb install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk

# Run in WSL; ADB intentionally points to the Windows executable for USB access.
wsl -e bash -lc "SERIAL=57220DLCR002R6 ADB=/mnt/c/Users/genei/AppData/Local/Android/Sdk/platform-tools/adb.exe STAGE_PARAKEET=1 STAGE_CORPUS=1 /mnt/i/Android_Projects/VoiceChat/scripts/stage-litert-asr-inputs.sh"

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb logcat -c
& $adb shell am instrument -w -e class com.voicechat.agent.eval.litert.LiteRtParakeetFeatureParityInstrumentedTest com.voicechat.agent.test/androidx.test.runner.AndroidJUnitRunner
& $adb logcat -d -s ParakeetFeatureParity:I
& $adb shell am instrument -w -e class com.voicechat.agent.eval.litert.LiteRtParakeetComparisonInstrumentedTest com.voicechat.agent.test/androidx.test.runner.AndroidJUnitRunner
& $adb shell am instrument -w -e class com.voicechat.agent.eval.litert.LiteRtParakeetCorpusInstrumentedTest com.voicechat.agent.test/androidx.test.runner.AndroidJUnitRunner
& $adb logcat -d -s LiteRtParakeetTest:I LiteRtParakeetCorpus:I
```

The parity test asserts the frozen feature SHA-256 and a max-absolute error
below `1e-3` for the corrected TDT profile. The comparison test is the frozen
feature control; the corpus test records `tokenCount`/`hasTranscript` and writes
local-only transcript sidecars. TDT's 500-frame tensor has 499 valid frames in
the frozen reference, so long clips are evaluated only over their initial 4.99 s
feature window.
