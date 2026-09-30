# Speech Quality, Endpoint, and Latency Evaluation (M25)

This document defines **how** the voice path is evaluated and records the
results that exist. It is deliberately split into a reproducible, host-only part
(the `com.voicechat.agent.eval` harness) and a device-only part (the Pixel 10
matrix), because the two have very different evidence strength.

**Status.** The harness and its tests are implemented. There is **no** Pixel 10
numeric baseline yet: no row below is passed, and no performance budget is fixed.
Smart Turn stays opt-in and default-off until the device rows meet the
acceptance criteria stated here (`docs/smart-turn.md`, R-0190, R-0192).

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

| metric | n | p50 ms | p90 ms | p95 ms | max ms |
| --- | --- | --- | --- | --- | --- |
| *(no run recorded — R-0043, R-0047)* | | | | | |

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
