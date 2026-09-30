# Smart Turn v3.2 Semantic End-of-Turn (M10)

This document records the M10 deliverable: an **optional, opt-in** semantic
end-of-turn detector backed by the pinned Smart Turn v3.2 ONNX artifact and a
narrowly scoped ONNX Runtime dependency. It complements
[VAD and endpointing](./vad-endpointing.md),
[voice loop](./voice-loop.md), [decisions](./decisions.md) §2.3 and §3.3,
[model and runtime support](./model-runtime.md), and
[logging](./logging.md).

M10 adds **one** decision to the M09 path: at a VAD-confirmed candidate pause the
bounded policy may ask the semantic model whether the thought is complete. It does
not touch the fast onset/barge-in path, and with Smart Turn off (the default) the
behavior is exactly the M09 VAD-only bounded endpoint.

## Pinned artifact (verified)

The single allow-listed artifact, re-verified against the Hugging Face model API
on **2026-09-29**:

| Property | Value |
| --- | --- |
| Repository | `huggingface.co/soniqo/Smart-Turn-v3.2-ONNX` |
| Revision | `b48fdbe20772bcec1fef02f4a1a355236ef6359e` (published 2026-09-02) |
| File | `smart-turn-v3.2-int8.onnx` |
| Size | **11,123,370 bytes** (exact) |
| SHA-256 | `00cd131551e8d1e9011f31345edb632a26116dd83e159782c4ddff610718ea31` |
| License | BSD-2-Clause (publisher `LICENSE`, 1,406 bytes) |
| Type | `whisper-tiny-encoder + attention-pool + mlp`, dynamic int8 quantization |
| Opset | 18 (per the publisher's `config.json`) |
| Input | `audio` float32 `[1, 128000]`, 16 kHz mono, most recent audio last, zeros at the front |
| Output | `probability` float32 `[1, 1]`; turn complete when `> 0.5` |
| Front-end | Whisper log-mel + zero-mean/unit-variance normalization **embedded in the graph** |

The verification source is
`https://huggingface.co/api/models/soniqo/Smart-Turn-v3.2-ONNX?blobs=true` and the
publisher `config.json`/`README.md` at that revision. The byte count and SHA-256
match the values already recorded in [decisions](./decisions.md) §3.3.

**It is a third-party re-export** (publisher `soniqo`) of
`pipecat-ai/smart-turn-v3`, not the upstream Pipecat artifact. The project's
contract is the M02 `TurnCompletionDetector` interface, so swapping the artifact
must not change orchestration. If an upstream artifact with the same embedded
front-end becomes available, prefer it (R-0194).

## Input/output contract

Feed the model **raw 16-bit PCM as float32**, not features: because the log-mel
front-end and the normalization are inside the graph, there is no Whisper mel
extraction in Kotlin. The detector (`SmartTurnCompletionDetector`) receives the
recent-audio window from the M09 `BoundedTurnEndpointPolicy` as one `AudioFrame`
and:

1. requires 16 kHz **mono** PCM;
2. right-aligns the window into a `[1, 128000]` buffer (`SmartTurnWindow`),
   discarding the earliest audio when the turn is longer than 8 s and
   zero-padding the front when it is shorter;
3. runs one inference on the CPU execution provider;
4. maps `probability > config.completionThreshold` (default `0.5`) to
   `COMPLETE`, otherwise `INCOMPLETE`.

A non-mono frame, an unexpected sample rate, a non-finite probability, or an
inference failure resolves to `UNAVAILABLE` — never a guessed `COMPLETE` — so the
bounded VAD-only policy still terminates the turn. `SmartTurnConfig` validates the
window length (positive), the threshold (strictly inside `(0, 1)`), and the 16 kHz
assumption in its constructor, before any audio is processed.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/turn/
  SmartTurnArtifact.kt            pinned identity (revision, size, SHA-256, license)
  SmartTurnConfig.kt              validated window / threshold / sample rate
  SmartTurnWindow.kt              left-pad / truncate raw PCM to the model window
  SmartTurnInferenceEngine.kt     fakeable native seam (no ONNX or Android type)
  OnnxSmartTurnEngine.kt          the only ai.onnxruntime user (CPU EP)
  SmartTurnCompletionDetector.kt  TurnCompletionDetector implementation
  SmartTurnModelState.kt          integrity/state/install types + download seam
  ContentHasher.kt                streamed SHA-256
  SmartTurnModelStore.kt          app-private verify / atomic install / remove
  OkHttpSmartTurnModelSource.kt   the only network source (reuses OkHttp)
  SmartTurnAvailability.kt        ModelAvailabilityProvider + settings mapping
  SmartTurnDetectorFactory.kt     opt-in provider that reads smartTurnEnabled
  SmartTurnDiagnostics.kt         privacy-safe load / inference / unavailable events
app/src/test/kotlin/com/voicechat/agent/turn/     JVM tests (fake engine, no native lib)
app/src/androidTest/kotlin/com/voicechat/agent/turn/
  SmartTurnInstrumentedTest.kt    on-device scaffold (see Tests.md)
```

## Opt-in behavior

Smart Turn is **default off** (`VoiceSettings.smartTurnEnabled = false`,
`docs/decisions.md` §3.3) and stays opt-in until M25 evidence supports another
default. The `SmartTurnDetectorFactory` reads the persisted flag at the start of
each voice session:

| State | Result |
| --- | --- |
| Disabled (default) | `null`; the detector is **never constructed**, the engine is never opened, and the M09 bounded VAD-only policy is used. |
| Enabled, model missing | `null`, typed `MODEL_UNAVAILABLE`, recorded; the settings surface reports `DownloadRequired`. |
| Enabled, model corrupt (wrong size/hash) | `null`, typed `MODEL_CORRUPT`, a user-visible reason, recorded; the VAD-only policy applies. |
| Enabled, verified, graph fails to load | `null`, typed `MODEL_UNAVAILABLE`, recorded; the VAD-only policy applies. |
| Enabled and verified | A `SmartTurnCompletionDetector` is built and evaluated once per candidate pause. |

Availability is exposed through the M02 `ModelAvailability` contract
(`ModelTask.TURN_COMPLETION`, `ModelRuntime.ONNX_RUNTIME`) and mapped to the
existing `SmartTurnState` in the settings model, so the settings screen disables
the toggle with a reason when the model is not installed, and
`SettingsValidator` clears an enabled flag that is no longer supportable. No
settings model was restructured for M10.

**No per-frame inference.** The detector is invoked only from
`BoundedTurnEndpointPolicy` at a `CANDIDATE_PAUSE` transition, over the
recent-audio window; the fast onset/barge-in path (`SPEECH_STARTED`) never calls
it. A false `INCOMPLETE` keeps the same logical user turn open and resumed speech
joins it.

## Model lifecycle

The artifact is app-private and is **never bundled in the APK or placed in
`assets/`** (it is a turn-detection model, not speech):

- **Integrity first.** `SmartTurnModelStore.state()` verifies the exact size and
  SHA-256 before the file is ever loaded; a wrong-size/hash file is `Corrupt`, not
  `Ready`.
- **Atomic install.** `install()` downloads to a sibling `.part` file, verifies
  it, and only then moves it into place (`ATOMIC_MOVE`, with a verified fallback),
  so a crash or a cancelled download cannot leave a half-written model.
- **Cancellable download.** `SmartTurnModelSource` is a seam; the only network
  implementation (`OkHttpSmartTurnModelSource`) reuses OkHttp, streams to the
  destination, and checks for cancellation between chunks. A transport or
  integrity failure is a typed `MODEL_DOWNLOAD_FAILED`/`MODEL_CORRUPT`.
- **Removal.** `remove()` deletes the installed model and any partial download.
- **Off the main thread.** Hashing, download, install, and native load run on an
  IO dispatcher; inference runs on `Dispatchers.Default`.

The app does **not** currently expose a download button for the model; the
lifecycle code and its tests exist, and the settings surface reports the missing
model as a required download (R-0191).

## ONNX Runtime

`com.microsoft.onnxruntime:onnxruntime-android` is pinned to **1.29.0** (MIT,
verified on Maven Central 2026-09-29) in
[`gradle/libs.versions.toml`](../gradle/libs.versions.toml), and the decision
record's R8 rule `-keep class ai.onnxruntime.** { *; }` is in
[`app/proguard-rules.pro`](../app/proguard-rules.pro). Only this artifact is
supported; the runtime is not a general ONNX path. The engine uses the **CPU**
execution provider with 2 intra-op threads, matching the publisher's reference
measurement; NNAPI and accelerator benchmarking are deliberately deferred
(R-0193). The shared `OrtEnvironment` is not closed per detector, so closing one
detector does not tear down the runtime.

## Diagnostics and logging

`SmartTurnDiagnostics` records on `DiagnosticStage.TURN_DETECTION` through the
existing M04 `DiagnosticsSink`, and mirrors a short line to the release-off
`AppLog`:

- `SMART_TURN_LOADED` with the byte size, duration, and `MODEL_INTEGRITY=VERIFIED`;
- `SMART_TURN_INFERENCE` with `TURN_COMPLETION_PROBABILITY` and the verdict
  reason (`COMPLETE`/`INCOMPLETE`), plus the inference duration;
- `SMART_TURN_UNAVAILABLE` with a stable `ERROR_CODE` and an integrity reason
  (`MISSING`, `CORRUPT`, `LOAD_FAILED`, `FORMAT_MISMATCH`).

Only identities, counts, the probability number, timings, and reason codes are
recorded. **Raw audio, the audio window, and transcripts are never logged.**

## Tests

JVM tests under `:app:testDebugUnitTest` (41 tests, no device, no network, no
native ONNX runtime, and no real model — a fake `SmartTurnInferenceEngine` stands
in for the native seam):

- `SmartTurnConfigTest` — window length, threshold bounds, and the 16 kHz
  assumption are validated in the constructor.
- `SmartTurnWindowTest` — left-padding, truncation, and raw-value preservation.
- `SmartTurnCompletionDetectorTest` — probability → `COMPLETE`/`INCOMPLETE` at the
  threshold, format/failure/non-finite → `UNAVAILABLE`, idempotent `close`, and
  probability diagnostics without audio content.
- `SmartTurnModelStoreTest` — missing / wrong-size / wrong-hash / verified states,
  install with a fake transport + hasher, cleanup of partial files, removal, and a
  known-SHA-256 check of the real hasher.
- `SmartTurnAvailabilityTest` — state → `ModelAvailability` and `SmartTurnState`
  mapping, and that the provider reports only `TURN_COMPLETION`.
- `SmartTurnDetectorFactoryTest` — disabled never constructs the detector or opens
  the engine; enabled-but-missing/corrupt/load-failure is a typed unavailable;
  enabled-and-verified builds the detector once.
- `SmartTurnEndpointIntegrationTest` — over the real M09
  `BoundedTurnEndpointPolicy` and replay fixtures: the detector runs **once per
  candidate pause, never per frame**; `COMPLETE` finalizes at the pause;
  `INCOMPLETE` holds and the silence cap finalizes; resumed speech after an
  `INCOMPLETE` verdict stays one logical turn.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck
.\gradlew.bat :app:assembleDebugAndroidTest
```

`SmartTurnInstrumentedTest` (see [Tests.md](../Tests.md)) exercises the real
graph on a device when the model is installed; it returns early when it is not and
never fabricates availability. It was **compiled but not run** for M10.

## Measured vs unmeasured

The **contract, opt-in behavior, integrity, and exactly-once invocation are
proven by the JVM suite.** Everything numeric about the model on Pixel 10 is
**not measured here**:

- on-device load time, inference time, peak memory, and CPU cost;
- false-commit / false-hold tradeoffs and threshold calibration;
- accelerator (NNAPI) behavior — not implemented;
- accuracy — the publisher's Apple-Silicon numbers (~93% on its own test set) are
  **not** this app's performance and must not be quoted as such.

M25 owns calibration, the acceptance evidence, and any decision to change the
default. Until then Smart Turn stays opt-in and default off (R-0190).

## Sources

- Hugging Face model API:
  `https://huggingface.co/api/models/soniqo/Smart-Turn-v3.2-ONNX?blobs=true`
  (accessed 2026-09-29).
- Model card and `config.json`:
  `https://huggingface.co/soniqo/Smart-Turn-v3.2-ONNX` at revision
  `b48fdbe20772bcec1fef02f4a1a355236ef6359e`.
- ONNX Runtime for Android:
  `https://onnxruntime.ai/docs/build/android.html`,
  `https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-android`
  (1.29.0, MIT, accessed 2026-09-29).
- Upstream model: `https://huggingface.co/pipecat-ai/smart-turn-v3`.
