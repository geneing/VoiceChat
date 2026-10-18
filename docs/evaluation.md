# Speech Quality, Endpoint, and Latency Evaluation (M25)

This document defines **how** the voice path is evaluated and records the
results that exist. It is deliberately split into a reproducible, host-only part
(the `com.voicechat.agent.eval` harness) and a device-only part (the Pixel 10
matrix), because the two have very different evidence strength.

**Status.** The host harness and its tests are implemented. Historical Pixel 10
STT measurements exist in [`voice-latency-results.md`](./voice-latency-results.md)
for ML Kit replay attempts, Moonshine streaming, and sherpa-onnx Parakeet offline
recognition; those rows are separate from this document's still-empty endpoint
sweep and full voice-loop latency tables. The **TTS stage** now has a Pixel 10
run (see [Latency](#latency-pixel-10)); the STT/LLM/end-to-end rows stay empty.
The current LiteRT benchmark branch
has no Pixel inference measurements yet. No performance budget is fixed.
Smart Turn stays opt-in and default-off until the endpoint/device rows meet the
acceptance criteria stated here (`docs/smart-turn.md`, R-0190, R-0192).

The LLM stage of the loop is measured separately and host-side only: see
[voice-llm-selection.md](./voice-llm-selection.md) for the chosen OpenCode Go
model and thinking parameter and [voice-llm-results.md](./voice-llm-results.md)
for the per-model run. Those rows are single runs from a Windows host with no
device, microphone, or TTS, so they are input to the model choice below and not
latency results for this document's tables (R-0253).

## What is measured

Per `docs/voice-quality-and-latency.md` and the M25 handoff:

1. **Recognition quality** — WER/CER plus name, number, negation, correction,
   and acknowledgement error buckets, by condition.
2. **Endpoint quality** — false commits, false holds, and endpoint delay as the
   VAD thresholds, silence cap, and semantic threshold vary.
3. **Latency** — per-stage and end-to-end p50/p90/p95: speech end → first
   assistant text, first audible TTS, completion; and barge-in onset → playback
   stop, capture resumed, and cancellation settled.

Every result must carry the configuration that produced it: device, OS/build,
route, locale, fixture, provider/model/reasoning, and network
(`MeasurementConditions`).

## The harness (`app/src/main/kotlin/com/voicechat/agent/eval`)

The eval package is pure Kotlin (`android.*`-free) and JVM-testable. It measures
artifacts a run already produces; it never runs a recognizer or a network call
itself.

| Type | Purpose |
| --- | --- |
| `MeasurementConditions` | The configuration metadata every report carries. |
| `LatencyMetric`, `LatencySample` | The named stage/end-to-end durations. |
| `LatencySummary`, `LatencyReport` | Nearest-rank p50/p90/p95/max per metric, plus a markdown table. `LatencyReport.fromBargeIns` builds the barge-in metrics straight from the M24 `BargeInTiming` records. |
| `SpeechQualityScorer`, `SpeechQualityResult`, `SpeechErrorCategory` | Normalized reference-vs-hypothesis WER/CER and best-effort error buckets. |
| `LabeledEndpointCase`, `EndpointSweep`, `EndpointAccuracy` | Runs the real `BoundedTurnEndpointPolicy` over labeled timelines for each candidate `VadConfig` and classifies accurate / false-commit / false-hold / miss. |
| `eval.tts.VoiceTtsHarness`, `VoiceTtsHarnessReport` | Drives the M11 `TextToSpeech` contract over fixed texts and reports `tts_first_audible` / `tts_completion` per voice/engine. |

Percentiles use the **nearest-rank** method, so a reported value is always an
observed sample; no interpolation invents a duration that never occurred. An
empty sample set has no summary (`LatencySummary.of` returns `null`), so an
unmeasured metric is absent rather than a misleading zero.

### Running the host harness

The host harness over the deterministic M03 fixtures is the `eval` unit tests:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest --tests "com.voicechat.agent.eval.*"
```

Synthetic fixtures are **not intelligible speech** and keep their base labels, so
they can exercise the endpoint/latency plumbing but can never produce a
recognition-quality number. Only the permissioned human corpus can
(`docs/audio-replay-harness.md`, R-0035, R-0036).

### The TTS stage harness

The TTS step is measured by `eval.tts.VoiceTtsHarness`, which drives the M02
`TextToSpeech` contract over the fixed assistant-shaped texts in
`VoiceTtsHarnessCases.TTS_TEXTS`. Per attempt it records the monotonic time from
the `speak()` call to the first audible `Started` event (`tts_first_audible`) and
to full `Delivered` (`tts_completion`), and aggregates them with the same
nearest-rank `LatencyReport`. An attempt that fails or is interrupted is a failed
outcome with no latency sample, so an unmeasured stage is absent rather than zero.

- Host, deterministic: `VoiceTtsHarnessTest` runs it with a clock-advancing fake
  and is part of `:app:testDebugUnitTest` (no device, no audio). It proves the
  aggregation, the failure path, and the record format — not a real engine.
- Device, opt-in: `VoiceTtsHarnessInstrumentedTest` (`-e ttsHarnessRunId`) speaks
  through the real platform engine and the selected embedded voice and writes
  `summary.md`/`record.md` to app-private cache;
  `scripts/run-tts-harness-device.ps1` stages, runs, and copies them back. It is
  **not run** yet (no Pixel 10 attached), so the device table below stays empty.
  A device with no embedded voice is skipped, never measured against a network
  voice.

### The Kokoro TTS stage (M28)

The distilled-Kokoro engine splits the same question into two measurable halves,
so a regression is attributable rather than just visible. `KokoroTtsStageHarness`
drives the real phonemizer and the real ONNX graph — no audio device — and
records three stages from one instant (the start of the text):

- `tts_phonemize` — text → phoneme chunks planned;
- `tts_first_pcm` — text → the first synthesized piece is in memory. This is
  **the synthesis side of time-to-first-sound**: everything that must happen
  before the audio sink can be handed its first buffer;
- `tts_synthesis_complete` — text → every piece is in memory, with the
  synthesized audio duration alongside it so the run reports a realtime factor
  (audio seconds per synthesis second).

`tts_first_audible` from `VoiceTtsHarness` remains the perceived number, measured
through real playback; the gap between it and `tts_first_pcm` is the audio
device's own start cost. Both run in one opt-in pass,
`KokoroTtsHarnessInstrumentedTest` (`-e kokoroHarnessRunId`), staged by
`scripts/run-kokoro-tts-latency.ps1`, and both use the same fixed texts so the
rows are comparable. A run with no installed voice is skipped, never downloaded
mid-measurement. The host aggregation is proven by `KokoroTtsStageHarnessTest`
with a deterministic clock, which is not a device claim.

## Endpoint sweep

`EndpointSweep` runs the production bounded policy over each labeled case for
each candidate `VadConfig` and reports:

- **false commit** — the endpoint is more than the commit tolerance *before* the
  labeled true end (the user was cut off);
- **false hold** — the endpoint is more than the hold tolerance *after* the true
  end (the reply felt late);
- **accurate** — inside the window;
- **miss** — no endpoint (the bounded policy should never miss).

The tolerances are the acceptance window, not tuned defaults, and are recorded
with the result. A Smart Turn threshold sweep is a device-only refinement: it
needs the real ONNX probabilities, so it is run from the instrumented test and
the results pasted here (`docs/smart-turn.md`).

## Acceptance criteria for enabling Smart Turn

Smart Turn becomes default-on only when all of the following are recorded from a
repeatable Pixel 10 run and reviewed:

- false-commit rate no worse than the VAD-only baseline on the labeled corpus;
- endpoint delay materially lower than the VAD-only silence cap, without a
  compensating false-hold rate;
- load time, inference time, and peak memory inside a budget the device run
  establishes (no number is invented before the run);
- no regression in the barge-in onset path, which must never evaluate the model.

Until then the feature is opt-in and default-off.

## Results

No device rows exist yet. The tables are the exact shape a run fills in; leave a
row out rather than guessing a value.

### Latency (Pixel 10)

No end-to-end voice-loop latency row exists yet; the STT/LLM/barge-in metrics
remain unmeasured (R-0043, R-0047). The **TTS stage** has been measured on its
own.

**TTS stage** — `scripts/run-tts-harness-device.ps1`, 2026-10-17, Pixel 10
(`frankel`), Android 17 / API 37, build `CP3A.261005.005`, engine
`com.google.android.tts`, voice `en-us-x-tpf-local` (quality 400, latency 200),
locale `en-US`, built-in speaker, 3 attempts per text (n=9). Every attempt
delivered.

| metric | n | p50 ms | p90 ms | p95 ms | max ms |
| --- | --- | --- | --- | --- | --- |
| tts_first_audible | 9 | 23.0 | 305.0 | 305.0 | 305.0 |
| tts_completion | 9 | 4958.0 | 6055.0 | 6055.0 | 6055.0 |

The single 305 ms `tts_first_audible` sample is the first `speak()` after engine
initialization (cold); every later attempt was 15-26 ms. `tts_completion` is the
full playback time for the utterance (51-94 characters), not a start delay. This
is one device, one voice, and one moment — not a p50/p95 claim, and the audible
first-audio time still needs the manual route/echo checks (R-0254).

| metric | n | p50 ms | p90 ms | p95 ms | max ms |
| --- | --- | --- | --- | --- | --- |
| *(speech end → first text / barge-in rows: no run recorded — R-0043, R-0047)* | | | | | |

**Kokoro TTS stage** — `scripts/run-kokoro-tts-latency.ps1`, no run recorded yet
(R-0264). The table is the exact shape the run fills in; the four metrics come
from one pass, so a row is left out rather than guessed:

| metric | n | p50 ms | p90 ms | p95 ms | max ms |
| --- | --- | --- | --- | --- | --- |
| *(tts_phonemize / tts_first_pcm / tts_synthesis_complete / tts_first_audible — no run recorded — R-0264)* | | | | | |

**Kokoro TTS stage, host components** — measured 2026-10-17 on the development
machine, **not** the device, and not a substitute for the run above. The two
halves of "text start to first sound" that a host can measure honestly:

| component | where | measurement |
| --- | --- | --- |
| text normalization (real rule pipeline) | JVM, `KokoroPhonemizerTimingTest` | 1-3 ms per rule-heavy sentence |
| phonemize (real port: lexicon + OpenNLP POS + chunker) | JVM, `KokoroPhonemizerTimingTest` | 3-4 ms per assistant-shaped sentence (median 3 ms) |
| one graph call per sentence (real int8 graph, Python ONNX Runtime 1.31.0, CPU) | WSL, `scripts/kokoro-onnx-contract.py` | 111 ms / 140 ms / 177 ms for the three harness sentences, 31-34x realtime |

So the host estimate for text start to first PCM is roughly 115-180 ms per
sentence, dominated by the graph; normalization and phonemization together are
under 10 ms of it. The device number differs on both sides: the
Pixel's CPU is slower than this host's, and the app pins ONNX Runtime 1.29.0
rather than 1.31.0, while the audio device's start cost only appears in
`tts_first_audible`. Treat the table as an order of magnitude, not a claim.

### Endpoint sweep (Pixel 10)

| onsetRms | pauseFrames | maxSilence ms | accurate | false commit | false hold | miss | mean delay ms | p95 \|delay\| ms |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| *(no run recorded — R-0061, R-0192)* | | | | | | | | |

### Recognition quality (human corpus)

| condition | WER | CER | names | numbers | negation | correction | acknowledgement |
| --- | --- | --- | --- | --- | --- | --- | --- |
| *(no consented corpus yet — R-0036)* | | | | | | | |

## Related documents

- Spec and budgets: [voice-quality-and-latency.md](./voice-quality-and-latency.md)
- Replay harness and fixtures: [audio-replay-harness.md](./audio-replay-harness.md)
- Endpointing policy: [vad-endpointing.md](./vad-endpointing.md)
- Smart Turn: [smart-turn.md](./smart-turn.md)
- Device test matrix: [Tests.md](../Tests.md)
- End-to-end latency and engine comparison plan:
  [voice-latency-optimization.md](./voice-latency-optimization.md)
