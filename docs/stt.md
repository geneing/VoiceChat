# On-Device Speech-to-Text (M08)

This document describes the first on-device STT integration. It records the
verified engine, how it is gated and mapped onto the M02 `SpeechToText`
contract, the audio path, what is covered by automated tests, and the manual
Pixel 10 test that remains to be run. It complements
[decisions.md](./decisions.md) §2.1 (the decision), [audio-replay-harness.md](./audio-replay-harness.md)
(the replay fixtures M08 reuses), and [validation.md](./validation.md).

> Status: the adapter, availability mapping, and assembler are implemented and
> unit tested. The engine itself has **not** been run on a device from this
> repository; that is the manual test in [Manual Pixel 10 test](#manual-pixel-10-test).

## Engine

- **Engine:** ML Kit GenAI Speech Recognition — the initial and only STT engine
  ([decisions.md](./decisions.md) §2.1). There is no cloud fallback and no
  second engine.
- **Artifact:** `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1`
  (alpha, no SLA or deprecation policy).
- **Artifact verified:** resolved from Google Maven and inspected on
  **2026-09-28**. The public API matches the official sample:
  `SpeechRecognition.getClient(...)` → `SpeechRecognizer` with suspend
  `checkStatus()`, `download(): Flow<DownloadStatus>`,
  `startRecognition(request): Flow<SpeechRecognizerResponse>`,
  `stopRecognition()`, and `close()`.
- **Official sources re-verified 2026-09-28:**
  <https://developers.google.com/ml-kit/genai/speech-recognition/android>
  (page last updated 2026-09-24) and the sample
  <https://github.com/googlesamples/mlkit/tree/master/android/speech>.
- **Modes:** one engine, two modes on the same API.
  - `BASIC` uses the traditional on-device recognizer (API 31+).
  - `ADVANCED` uses the GenAI model; preferred where available (Pixel 10/11).
  - Both share one `EngineId` (`mlkit-genai-speech-recognition`); the mode is
    reported through a distinct `ModelId` (`mlkit-speech-basic` /
    `mlkit-speech-advanced`).

## Implementation map

All engine code lives in `app/src/main/kotlin/com/voicechat/agent/stt/`:

| File | Role |
| --- | --- |
| `SttEngine.kt` | Platform-free engine identity (`SttMode`, `SttEngine`), the single-engine catalog, and preferred-available selection. |
| `SttAvailability.kt` | Platform-free `SttFeatureStatus`, `SttAvailability`, `SttDownloadStatus`, and the "only `AVAILABLE` is ready" mapping. |
| `SttResultAssembler.kt` | Platform-free translation of vendor-neutral responses into the `SttEvent` contract; revisions, segment merging, empty/low-confidence/failure handling. |
| `PcmFraming.kt` | Platform-free re-encoding of 16 kHz mono PCM to the little-endian bytes the recognizer's custom-audio path requires. |
| `MlKitSttMapping.kt` | Vendor translations (options DSL, feature status, error code) — ML Kit types stay here. |
| `MlKitSttStatus.kt` | `check()` (mandatory runtime gate) and `download()` using the supported APIs, off the main thread. |
| `MlKitSpeechToText.kt` | The `SpeechToText` adapter: gates on `checkStatus()`, pumps frames to `AudioSource.fromPfd`, maps responses, records diagnostics. |

`SttSourcePurityTest` enforces that the four platform-free files contain no
`android.*` / `androidx.*` imports.

## Contract behavior

`SpeechToText.transcribe(audio)` returns a cold `Flow<SttEvent>` for one
session. The adapter:

- **Partial revisions.** Each `PartialTextResponse` becomes an interim
  `Transcript` with a monotonic `TranscriptRevision`, so consumers keep only
  the newest guess. A later hypothesis replaces an earlier one; the raw text is
  never rewritten by the app.
- **Finalization.** ML Kit may emit several `FinalTextResponse` segments for
  one session. `SttResultAssembler` merges segments and emits **exactly one**
  `isFinal = true` transcript when the session completes (`CompletedResponse`
  or the response flow ending). A session that produced only partials finalizes
  the last hypothesis; a session with no text finalizes an explicit empty
  transcript.
- **Empty input.** Empty/blank partials are ignored, but the session always ends
  with one final (possibly empty) transcript. It is never reported as a
  success with no result and never silently swallowed.
- **Low confidence.** `Transcript.confidence` carries the engine's score when it
  provides one and is preserved; `Transcript.isLowConfidence()` is available for
  orchestration. The selected engine currently exposes no confidence, so the
  value is `null` there and no threshold is applied in the STT layer.
- **Failure.** Errors map to typed `VoiceAgentError`s (`STT_UNAVAILABLE`,
  `STT_MODEL_NOT_READY`, `STT_RECOGNITION_FAILED`) and end the flow; no event is
  emitted after a final result or a failure.
- **Metadata.** Every transcript is stamped with the engine locale's language
  tag; diagnostics carry engine/model identity (below).

### Runtime gating (mandatory)

`MlKitSpeechToText` calls `checkStatus()` before starting and refuses to run
unless it is `AVAILABLE`. `MlKitSttStatus.check(engine)` additionally exposes the
state to settings/UI:

| Feature status | `SttAvailability` | User-visible effect |
| --- | --- | --- |
| `AVAILABLE` | `Ready` | STT may start. |
| `DOWNLOADABLE` | `DownloadRequired` | Offer `download()` (progress/cancel); STT is not ready. |
| `DOWNLOADING` | `Downloading` | Not ready yet. |
| `UNAVAILABLE` | `Unavailable(DEVICE_UNSUPPORTED, STT_UNAVAILABLE)` | Not usable (e.g. unlocked bootloader / unsupported device); keep the manual text composer. |

`SttEngines.preferred(...)` picks the first `Ready` engine in catalog order
(Advanced before Basic). A missing or unprovisioned model is never reported
ready, and there is no silent provider switch. The manual text path (M06)
remains usable whenever STT is unavailable.

## Audio path and preprocessing

- Input to the adapter is the M02 `AudioFrame` stream: **16-bit signed, mono,
  16 kHz** PCM (`AudioFormat.MONO_16_KHZ`), the format the recognizer's
  `AudioSource.fromPfd` requires.
- `PcmFraming.toLittleEndianPcm` re-encodes each frame to headerless
  little-endian bytes. A frame in any other format is rejected loudly; M07 owns
  resampling/gain/AEC/noise suppression.
- The frame bytes are written to a `ParcelFileDescriptor` pipe consumed by
  `AudioSource.fromPfd`. This path needs a real-time stream, which live capture
  provides; it is why replay tests use the M03 replay adapter instead of this
  class.
- No microphone permission is added here: `fromPfd` supplies audio from the
  capture boundary (M07). STT never sends audio off-device.

## Diagnostics

The adapter records `SPEECH_TO_TEXT` events through the M04 `DiagnosticsSink`
(`STARTED`, then one terminal `COMPLETED`/`FAILED`/`CANCELLED`) with
`ENGINE_ID`, `MODEL_ID`, `FRAME_COUNT`, `CHARACTER_COUNT`, and `ERROR_CODE`
attributes. It never records raw audio or transcript text; the count is a length
only. No `TraceId`/`TurnId` is attached, because the STT contract carries no turn
identity — orchestration binds STT events to the active turn (M21).

## Automated tests

`app/src/test/kotlin/com/voicechat/agent/stt/`:

- `SttResultAssemblerTest` — partial revisions, finalization, correction,
  merged final segments, empty input, completed-without-final, blank partials,
  typed failures, terminal stickiness, low/high/missing confidence,
  numbers/names/negation/disfluency preserved verbatim, and language metadata.
- `SttAvailabilityTest` — only `AVAILABLE` is ready; typed unavailable reason;
  single engine / distinct models; catalog order and preferred-available
  selection.
- `PcmFramingTest` — little-endian encoding and the format guard.
- `MlKitSttReplayTest` — drives the `SpeechToText` contract through the M03
  replay infrastructure (no duplication) for partial revisions, finalization,
  corrections, numbers, names, negation, disfluency, empty input, and repeatable
  low-SNR variants (street noise, car noise, compression).
- `MlKitSpeechToTextWiringTest` — the real adapter is a `SpeechToText` with the
  single engine id and idempotent `close()`, without a device or a claim of
  availability.

These tests need no credentials, network, model download, or microphone. They do
not fake engine availability.

## Manual Pixel 10 test

The engine requires a real device, so this is a manual run (no automation). It
was **not** executed for this milestone — no Pixel 10 was attached — so no
on-device result is claimed.

**Preconditions**

- Pixel 10 with a **locked bootloader** (the API does not support unlocked
  bootloaders), stock Android with AICore present and updated, signed in, and
  online at least once so AICore can fetch its configuration.
- `adb` access and the SDK configured (`$env:ANDROID_HOME`).
- For Advanced mode, confirm the device is Pixel 10/11; Basic is available from
  API 31.

**Steps (once M07/M24 wire the adapter into the app)**

1. Install and launch the debug build:
   `.\gradlew.bat :app:installDebug`.
2. Grant microphone permission at the point of use (M07). M08 itself installs no
   permission.
3. Open STT settings and record the reported state from `MlKitSttStatus.check`:
   mode, `SttFeatureStatus`, and the returned `SttAvailability`.
4. If `DOWNLOADABLE`/`DOWNLOADING`, wait for the model to provision (or trigger
   `download()` and record progress); re-check until `AVAILABLE`.
5. Speak fixed phrases covering the same categories as the automated fixtures —
   a number, a name, a negation, a disfluency, and a correction — and capture the
   live partials and the final transcript.
6. After provisioning, repeat the recognition in **airplane mode** to confirm
   the transcription is on-device (no network, no cloud STT).
7. Force the unavailable paths: deny/revoke microphone permission, and if
   available, a device with an unlocked bootloader or without AICore, and confirm
   the app shows an explicit unavailable state and keeps the text composer.

**Interim alternative, before M07/M24 exist:** build and run Google's official
ML Kit GenAI Speech sample
(<https://github.com/googlesamples/mlkit/tree/master/android/speech>) on the
Pixel 10 to confirm the device reports `AVAILABLE` and transcribes with the
locked-bootloader/airplane-mode checks. Record the same fields below.

**What to record for every run**

- Device model/build, Android version, AICore version, bootloader state.
- Engine artifact version (`1.0.0-alpha1`), mode (Basic/Advanced), model id.
- `checkStatus()` result before and after provisioning.
- Audio path (16 kHz mono 16-bit PCM → `AudioSource.fromPfd`), and that no
  preprocessing (gain/AEC/denoise/resample) was applied.
- Spoken phrase vs. partial and final transcripts, and network state
  (online vs. airplane mode).
- Unavailable-case behavior (permission denied, unlocked bootloader /
  unsupported device).

## Known limitations and open risks

- **No on-device validation yet.** The adapter compiles against the real API and
  is unit tested, but its runtime behavior (status gating, `fromPfd` streaming,
  segment merging) is unverified on hardware.
- **Final-segment semantics.** Merging multiple `FinalTextResponse` segments
  follows the official sample (`curText += response.text`). If the engine instead
  returns cumulative text per final, the merge would duplicate it; confirm and
  adjust during the manual run.
- **No confidence.** The engine does not expose a confidence score, so the
  low-confidence path is a pass-through (`null`) for now.
- **Real-time pacing.** `fromPfd` requires real-time audio. The adapter relies on
  live capture for pacing and is not intended for feed-forward replay; replay
  coverage uses the M03 adapter.
- **Wiring.** Settings/UI selection and the voice-loop integration are M22/M24;
  M08 exposes the catalog and availability API but no UI yet.
