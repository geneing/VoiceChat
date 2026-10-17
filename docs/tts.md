# On-Device Text-to-Speech (M11/M28)

This document records the original M11 platform TTS integration and the active
M28 distilled-Kokoro voice path. The platform adapter remains in the tree but
is not wired for voice replies. It complements [decisions.md](./decisions.md) §2.2,
[architecture.md](./architecture.md), [voice-quality-and-latency.md](./voice-quality-and-latency.md),
and [validation.md](./validation.md).

> Status: M28 wires the pinned `af_heart` student through the existing TTS
> contract. Kokoro engine behavior is tested on the JVM with fake state, graph,
> and sink; no graph/native runtime or audio device is needed for those tests.
> The Pixel 10 figures later in this document are from the retained M11 platform
> engine and do not measure Kokoro. Kokoro model loading, AudioTrack routing,
> first-audible latency, interruption timing, and audible quality have not been
> run on device (R-0261).

## Engine and decision

- **Active engine (M28):** the pinned distilled Kokoro `af_heart` ONNX graph.
  The app-private store verifies size and SHA-256 before the engine opens a
  session. There is no platform or network fallback; missing, corrupt, or
  unloadable models remain unavailable and the text path stays usable.
- **Voice choice:** only verified Kokoro artifacts are offered. The graph is
  English (`en-US`); a locale with no Kokoro voice gets an explicit unavailable
  state. The upstream model card marks the weights Apache-2.0; see
  [model-runtime.md](./model-runtime.md) for the exact pin.
- **Retained M11 adapter:** `AndroidTtsEngine` and `OnDeviceTts` still implement
  the historical platform engine/voice policy below, but AppContainer no longer
  uses them for voice replies.

## Implementation map

All engine code lives in `app/src/main/kotlin/com/voicechat/agent/tts/`:

| File | Role |
| --- | --- |
| `TtsVoice.kt` | Platform-free `TtsVoice`, `TtsEngineAvailability` (`Ready` / `NoOnDeviceVoice` / `SelectedVoiceUnavailable` / `SelectedEngineUnavailable` / `Unavailable`), and the `OnDeviceVoiceSelector` policy that excludes network voices and prefers the most natural embedded voice. |
| `TtsEngineInfo.kt` | Platform-free descriptor of one installed TTS **engine** (`TextToSpeech.EngineInfo`), for the settings engine picker. |
| `TtsEngine.kt` | The replaceable `TtsEngine` seam: `initialize`, `installedVoices`, `installedEngines`, `selectVoice`, per-utterance `speak` flow, `stop`, `close`, plus `TtsEngineEvent` / `TtsEngineFailureKind`. |
| `TtsOutputRoute.kt` | Platform-free output route kind for diagnostics (speaker/headset/Bluetooth/…); distinct from the M07 input `AudioRouteType`. |
| `EngineTextToSpeech.kt` | The M02 `TextToSpeech` contract adapter: gates on availability, maps engine events to `TtsEvent`, enforces the on-device-only policy, and emits privacy-safe diagnostics. |
| `TtsPlaybackAccounting.kt` | Truthful ledger of generated / queued / started / delivered text, aligned with `AssistantDelivery`. |
| `AndroidTtsEngine.kt` | The platform `android.speech.tts.TextToSpeech` implementation (all platform TTS types stay here), including speech-engine selection at construction and `getEngines()` discovery. |
| `AndroidTtsOutputRoute.kt` | Maps `AudioDeviceInfo` output types to `TtsOutputRoute`. |
| `OnDeviceTts.kt` | App-boundary factory: `AndroidTtsEngine` wrapped by `EngineTextToSpeech`; takes the persisted engine and voice ids. |
| `kokoro/KokoroArtifact.kt` | Pinned Kokoro voice identity, ONNX graph contract, sample rate, and phoneme bound. |
| `kokoro/KokoroTtsEngine.kt` | Verified graph initialization, serialized synthesis/playback, and first-audible/interruption accounting. |
| `kokoro/AudioTrackKokoroSink.kt` | The only Kokoro playback implementation using `AudioTrack` (24 kHz mono float PCM). |
| `kokoro/KokoroTts.kt` | App-boundary factory wrapping Kokoro in `EngineTextToSpeech`. |

`TtsSourcePurityTest` enforces that `TtsVoice.kt`, `TtsEngine.kt`,
`TtsOutputRoute.kt`, `EngineTextToSpeech.kt`, and `TtsPlaybackAccounting.kt`
contain no `android.*` / `androidx.*` imports.

## Contract behavior

`TextToSpeech.speak(text, utteranceId)` returns a cold per-utterance flow. Each
call maps one text chunk to one engine utterance, so orchestration can enqueue
incremental chunks in order while earlier chunks are still playing and collect
each chunk's terminal event. The adapter (`EngineTextToSpeech`):

- **Queued chunking.** Emits `TtsEvent.Queued`, then enqueues with the engine;
  the engine uses `QUEUE_ADD`, so chunks play in order.
- **Started / first audible.** `TtsEngineEvent.Started` becomes
  `TtsEvent.Started`; this is the first-audible timing point.
- **Delivered.** `TtsEngineEvent.Completed` becomes `TtsEvent.Delivered` (the
  whole chunk was audible).
- **Interrupted.** On `stop()` the engine interrupts every in-flight chunk;
  `TtsEngineEvent.Interrupted(deliveredText)` becomes
  `TtsEvent.Interrupted(utteranceId, deliveredText)`, where `deliveredText` is
  the prefix the engine could confirm (see [Open risks](#known-limitations-and-open-risks)).
- **Empty input.** Completes without playback and never touches the engine.
- **Failure.** A failed engine utterance maps to a typed `VoiceAgentError`
  (`TTS_SYNTHESIS_FAILED` / `TTS_PLAYBACK_FAILED`). If no embedded voice exists,
  the adapter emits a single `TtsEvent.Failed(TTS_NO_ON_DEVICE_VOICE)` and never
  speaks through a network voice. An engine flow that ends with no terminal
  event is reported as a typed failure, never as success.
- **Accounting.** Every non-empty utterance emits `Queued` first and ends in
  exactly one terminal (`Delivered`, `Interrupted`, or `Failed`), so an
  interruption leaves nothing unaccounted.

`TtsPlaybackAccounting` aggregates those events into the turn-level quantities
the product requires and clamps delivery to a valid prefix of the generated text
via `toDelivery(...) -> AssistantDelivery`.

## Kokoro model discovery and policy

`KokoroTtsEngine.initialize()` resolves the persisted voice id or pinned default,
asks `KokoroModelStore` for its integrity-checked state, and loads the native
session only for `Installed`. `installedVoices()` contains only voices whose
store state was `Installed`; `installedEngines()` is empty because Kokoro is one
fixed speech API, not a platform engine picker. Settings offers the user-approved
model install and reports the missing/corrupt state directly.

The synthesis pipeline maps text to graph-sized phoneme chunks and synthesizes
only the next piece before playback. The 24 kHz mono float output is streamed to
`AudioTrack`. `Started` comes only from the first piece's `onAudible` callback; a
source-text piece enters the delivered prefix only after its full sample stream
finishes. `stop()` interrupts queued work and the active sink without reporting an
unheard piece. Punctuation-only input completes without playback.

## Retained M11 platform voice discovery and policy

`AndroidTtsEngine.initialize()` awaits `TextToSpeech` init, sets the
`UtteranceProgressListener`, enumerates `getVoices()`, and calls
`OnDeviceVoiceSelector.mostNatural(voices, locale)`:

- `installedVoices()` returns every reported voice with its
  `requiresNetwork` flag; nothing is inferred from a voice name.
- `OnDeviceVoiceSelector.onDeviceVoices` filters to
  `requiresNetwork == false`; `mostNatural` (and its alias `select`) prefers an
  exact locale match, then the highest quality (the platform naturalness rank),
  then the lowest latency.
- `installedEngines()` lists the platform's TTS engines with the system default
  flagged, for the settings picker; it never fabricates an engine.
- `null` selection becomes `TtsEngineAvailability.NoOnDeviceVoice(locale)`,
  which the adapter surfaces as `TTS_NO_ON_DEVICE_VOICE`. The app stays
  text-only; it never selects a network voice.

Availability is therefore read from the device at runtime. A "ready" state is
backed by a voice the platform actually reported; no voice/model is claimed
available from app state.

## Retained M11 platform audio focus and route

- The engine holds transient `USAGE_ASSISTANT` audio focus for the span in which
  any utterance is active and abandons it when idle, via the same
  `AudioFocusController` boundary as M07 capture. The final cross-stage focus
  policy (duck/pause, stop capture on focus loss) is M24's (R-0050).
- At `Started`, the engine reports the current output route *kind*
  (`TtsOutputRoute`) for diagnostics; the platform's user-visible device name is
  never carried. Speaker/headset/Bluetooth routing is a manual device check.
- `stop()` calls `tts.stop()` and then force-completes every still-registered
  flow with `Interrupted` (the confirmed prefix, or empty), so a queued
  utterance cannot hang the turn if the engine omits a callback.

## Kokoro playback route

`AudioTrackKokoroSink` writes the graph's native 24 kHz mono `ENCODING_PCM_FLOAT`
samples in streaming mode with `USAGE_ASSISTANT` / `CONTENT_TYPE_SPEECH`. It
reports only a `TtsOutputRoute` kind read from the routed device; its name is
never stored. The sink itself does not request audio focus. Device routing,
focus interaction, underruns, and interruption latency are not yet verified on
Pixel 10 (R-0261; focus policy also remains under R-0050).

## Diagnostics

`EngineTextToSpeech` records `TTS_SYNTHESIS` and `TTS_PLAYBACK` events through
the M04 `DiagnosticsSink`:

- synthesis `STARTED` with `ENGINE_ID`, `MODEL_ID` (selected voice id), and
  `CHARACTER_COUNT`;
- Kokoro graph load `COMPLETED`/`FAILED` with `ENGINE_ID` and measured duration;
- playback `first-audible` with `AUDIO_ROUTE` (route kind only);
- playback `completed` / `interrupted` with `DELIVERED_CHARACTER_COUNT` and
  `TOTAL_CHARACTER_COUNT`;
- `FAILED` with only the stable `ERROR_CODE`.

No transcript or assistant text is ever recorded, only lengths. No
`TraceId`/`TurnId` is attached, because the `TextToSpeech` contract carries no
turn identity; orchestration binds TTS events to the active turn (M21). Wiring
the per-turn `TurnTraceRecorder.playbackStarted` / `playbackDelivered` helpers
and barge-in stop timing remains M21/M24 (R-0029, R-0059).

## TTS-stage harness (M25)

The TTS stage is measured in `app/src/main/kotlin/com/voicechat/agent/eval/tts/`
(`VoiceTtsHarness`, `VoiceTtsHarnessReport`, `VoiceTtsHarnessCases`):

- it drives the M02 `TextToSpeech` contract with fixed assistant-shaped texts and
  records, per attempt, the time from the `speak()` call to the first audible
  `Started` event and to full `Delivered`, aggregated as the
  `tts_first_audible` / `tts_completion` metrics in a `LatencyReport`;
- it never fakes a duration: an attempt that fails or is interrupted is recorded
  as a failed outcome and produces no latency sample, so an unmeasured stage stays
  absent rather than zero;
- it carries `MeasurementConditions` (device, route, locale, voice/engine) with
  every record.

`VoiceTtsHarnessTest` runs it on the JVM with a deterministic clock-advancing
`TextToSpeech`, proving the aggregation and failure handling. The device run is
`VoiceTtsHarnessInstrumentedTest` (opt-in via `-e ttsHarnessRunId`), which reads
the persisted locale/engine/voice from the settings store, speaks through the
real M11 platform engine, and writes the reports; it does not exercise Kokoro.
It **was run** on 2026-10-17 on the
Pixel 10 (see the status note above and
[evaluation.md](./evaluation.md#latency-pixel-10));
`scripts/run-tts-harness-device.ps1` stages, runs, and copies the reports back.

## Automated tests

`app/src/test/kotlin/com/voicechat/agent/tts/` (JVM, no device):

- `EngineTextToSpeechTest` — queued→started→delivered order; empty input
  without engine work; explicit `TTS_NO_ON_DEVICE_VOICE` when only network voices
  exist (and the engine is never called); a missing selected engine is the same
  typed failure, not a fallback; chunk ordering; typed engine failure;
  `stop()` interrupting every queued chunk with nothing left unaccounted;
  cancelling one collection without hanging; idempotent `close` and
  speak-after-close; privacy-safe first-audible/delivered diagnostics.
- `TtsVoiceSelectionTest` — network voices excluded; selection never returns
  a network voice; `null` when no embedded voice matches the language; exact
  locale, then quality (naturalness), then latency preference; `mostNatural`
  agrees with `select`.
- `TtsPlaybackAccountingTest` — generated/queued/started/delivered counts;
  interruption leaves nothing unaccounted and keeps only the audible prefix;
  unaccounted and failed states; clamping to the generated prefix; empty ledger.
- `TtsSourcePurityTest` — the platform-free TTS types import no
  `android.*`/`androidx.*`.

`app/src/test/kotlin/com/voicechat/agent/tts/kokoro/KokoroTtsEngineTest.kt`
uses fake model state, synthesizer, and audio sink to cover lazy first-piece
playback, ordering, interruption accounting, typed failures, unavailable models,
empty phoneme output, and close behavior.

`app/src/test/kotlin/com/voicechat/agent/eval/tts/VoiceTtsHarnessTest.kt` covers
the harness aggregation, the failure/no-latency path, and the record's
conditions.

The instrumented test `app/src/androidTest/.../tts/AndroidTtsInstrumentedTest.kt`
compiles on the host (`:app:assembleDebugAndroidTest`) and runs only on a device:

- enumerates the real embedded voices and asserts the selected "ready" voice is
  one `getVoices()` reported and not network-required (availability is recorded,
  never faked);
- exercises immediate `stop()` during playback and requires a terminal event
  without hanging (skipped with `Assume` when no embedded voice is installed);
- the `OnDeviceTts` contract adapter rejects empty input and closes.

`app/src/androidTest/.../eval/tts/VoiceTtsHarnessInstrumentedTest.kt` is the
opt-in device harness; it was run on 2026-10-17 (see the status note above).

## Historical M11 platform TTS Pixel 10 test

The TTS-stage harness was run on a Pixel 10 on 2026-10-17
(`.\\scripts\\run-tts-harness-device.ps1 -Repeats 3`): Android 17 / API 37,
build `CP3A.261005.005`, `debug`, engine `com.google.android.tts`, voice
`en-us-x-tpf-local` (quality 400, latency 200), locale `en-US`, built-in
speaker. All 9 attempts delivered; `tts_first_audible` p50 23 ms (the first
`speak()` after init 305 ms, later attempts 15-26 ms) and `tts_completion` p50
4958 ms for 51-94 characters. The remaining manual checks in
[Tests.md](../Tests.md) (M11) — route/headset/Bluetooth routing, focus
interaction, immediate-stop latency, audible naturalness, and the airplane-mode
proof — are **not** run; no claim is made for them.

## Known limitations and open risks

- **Kokoro device validation (R-0261).** The current engine tests use fakes only.
  The pinned graph has not been loaded on Pixel 10; first-audible timing, memory,
  AudioTrack output/routing, underruns, immediate stop, and naturalness remain
  unmeasured. The M11 platform numbers below do not apply to this engine.

- **On-device validation (R-0057, partial).** The TTS-stage harness now measures
  voice enumeration and first-audible/playback time on the Pixel 10 (2026-10-17).
  Speaker/headset/Bluetooth routing, focus changes, and immediate-stop latency
  remain unverified on hardware.
- **Interrupted-prefix precision (R-0058).** The confirmed audible prefix comes
  from `UtteranceProgressListener.onRangeStart`, which not every engine calls. If
  it is never called, an interrupted chunk reports an empty delivered prefix —
  conservative (never over-reports) but the audible portion is not recorded.
- **Turn correlation (R-0029, R-0059).** TTS diagnostics are emitted without a
  trace/turn id and the per-turn `TurnTraceRecorder` playback helpers are not
  called yet; barge-in stop timing and per-turn first-audible stay M21/M24.
- **Shared focus boundary (R-0060, R-0050).** Capture and TTS both request
  transient `USAGE_ASSISTANT` focus via the same boundary; simultaneous operation
  and the final duck/pause policy are unvalidated until M24.
- **Wiring.** Voice and speech-engine selection are M22 and the voice-loop
  integration is M24; both are implemented and wired through `AppContainer`, so
  the persisted engine/voice drives the session's TTS engine. The harness
  exercises that same persisted-configuration path on the Pixel 10; the full app
  voice session (route, focus, barge-in stop) is still unmeasured (R-0057).
- **Naturalness rank (R-0258).** "Most natural" is `Voice.getQuality()`, the only
  naturalness signal `android.speech.tts` exposes. It is coarse; on Android 15+
  the platform's own supertonic voices are reported with the same quality ranks,
  so the app cannot prefer one by API. Re-verify when the platform exposes a
  naturalness/feature signal.
- **Selected-engine fallback (R-0257).** `TextToSpeech(context, listener, engine)`
  is created with the persisted package, and the app refuses a package the device
  does not report, but the platform does not expose which engine a live instance
  actually bound to. A device/engine that silently ignores the package cannot be
  detected from the API; the settings validation is the primary guard.
