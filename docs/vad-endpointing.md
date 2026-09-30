# VAD, Fast Onset, and Bounded Endpointing (M09)

This document describes the M09 speech-activity and endpointing path: how
continuous speech onset is separated from the decision that a thought is
complete, how the VAD-only bounded endpoint guarantees a trailing turn always
terminates, and how the behavior is configured, validated, instrumented, and
tested. It complements
[architecture](./architecture.md#end-of-turn-detection),
[decisions.md](./decisions.md) §2.3 and §3.3,
[voice quality and latency](./voice-quality-and-latency.md),
[audio replay harness](./audio-replay-harness.md), and
[logging](./logging.md).

M09 implements the VAD-only policy the architecture requires. Semantic
completion (Smart Turn v3.2) is M10 and plugs into this path without changing
the audio path.

## No VAD model

`docs/decisions.md` §2.3 left VAD deferred to M09 with an explicit constraint:
**no VAD model is allow-listed**. ONNX Runtime is scoped to Smart Turn v3.2
only, and no TFLite/LiteRT VAD artifact is verified. M09 therefore works from
**measured audio characteristics** — frame RMS plus zero-crossing rate — and
adds no model, runtime, dependency, or permission. The optional semantic
completion decision stays behind the existing M02 `TurnCompletionDetector`
contract for M10.

## Two separate decisions

The architecture requires speech activity and turn completion to be independent:

1. **Activity (fast path).** `EnergyVoiceActivityDetector` emits the M02
   `SPEECH_STARTED`, `CANDIDATE_PAUSE`, and `SPEECH_RESUMED` events from measured
   audio, with onset latency of roughly `onsetFrames` × frame duration (~60 ms by
   default). It never asks whether a thought is complete, so it is the correct
   signal for barge-in: assistant playback can stop on `SPEECH_STARTED`.
2. **Completion (bounded policy).** `BoundedTurnEndpointPolicy` composes activity
   with the optional semantic detector and finalizes each logical user turn
   exactly once — at a `COMPLETE` candidate pause, or when the configurable
   maximum-silence cap elapses. With no detector (or an unavailable one) the
   bounded cap alone ends the turn.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/
  contracts/Diagnostics.kt          VAD_EVENT / VAD_REASON / VAD_OFFSET_MILLIS (additive)
  vad/
    VadConfig.kt                    validated thresholds + route-aware defaults
    AudioFrameFeatures.kt           normalized RMS + zero-crossing rate
    VadStateMachine.kt              pure onset/pause/resume state machine
    EnergyVoiceActivityDetector.kt  VoiceActivityDetector (fast onset/barge-in)
    RecentAudioWindow.kt            bounded recent-audio window for the detector
    BoundedTurnEndpointPolicy.kt    holds, one endpoint per logical turn, silence cap
    TurnDetectionDiagnostics.kt     TURN_DETECTION events on the M04 sink + AppLog
  audio/
    CaptureTurnDetection.kt         app-boundary route -> config / detector factory
app/src/test/kotlin/com/voicechat/agent/vad/   JVM tests (replay-driven)
app/src/androidTest/kotlin/com/voicechat/agent/audio/
    VadCaptureInstrumentedTest.kt   live capture -> detector smoke (device only)
```

The `vad` package is pure Kotlin (`android.*`-free), enforced by
`VadSourcePurityTest`; route-to-threshold mapping is the only thing at the
`audio/` boundary. Nothing here requests the microphone or starts capture.

The policy's stream also carries the `Activity` events, so a single collection can
serve both barge-in and endpointing; `EnergyVoiceActivityDetector` exists for
consumers that need only the M02 activity contract.

## Measured features

`AudioFrameFeatures` computes exactly two normalized quantities per frame and
retains neither the samples nor any other content:

- **RMS**, normalized to `[0, 1]` against 16-bit full scale. This is the primary
  onset/pause gate.
- **Zero-crossing rate**, crossings per sample interval in `[0, 1]`. This is a
  secondary gate that rejects clearly hissy or clicky frames; it does not claim
  to separate speech from music.

## State machine

`VadStateMachine` is a small three-state machine (`SILENCE`, `SPEECH`, `PAUSE`)
driven frame by frame, so live capture and replay traverse identical logic:

- **Onset:** `onsetFrames` consecutive frames at or above
  `onsetRmsThreshold` (and below `maxZeroCrossingRate`) emit `SPEECH_STARTED`.
- **Pause:** `pauseFrames` consecutive frames below the lower
  `hangoverRmsThreshold` emit `CANDIDATE_PAUSE`. The hysteresis floor keeps a
  turn open through soft frames and the sub-threshold word gaps inside a phrase.
- **Resume:** onset-qualifying energy after a pause emits `SPEECH_RESUMED`.

Every transition carries a stable `VadReason` (`ONSET_RMS`, `PAUSE_SILENCE`,
`RESUME_RMS`) and an offset in milliseconds from capture start.

## Bounded endpoint policy

`BoundedTurnEndpointPolicy.observe(audio)` emits `TurnDetectionEvent`s:

| Event | Meaning |
| --- | --- |
| `Activity` | A `SPEECH_STARTED` / `CANDIDATE_PAUSE` / `SPEECH_RESUMED` transition, independent of turn completion. |
| `Held` | A candidate pause that did **not** finalize the turn (`VAD_ONLY` or `SEMANTIC_INCOMPLETE`); the same turn stays open. |
| `Endpointed` | The single final endpoint of a logical turn: `SEMANTIC_COMPLETE`, `SILENCE_CAP`, `CAPTURE_ENDED`, or `EMPTY_NO_SPEECH`. |
| `Failed` | Detection could not continue (for example a non-mono frame). |

**One logical turn across pause and resume.** A candidate pause emits `Held` and
keeps the turn open; resumed speech emits `SPEECH_RESUMED` and joins the same
turn. Only when a pause is *not* resumed does the cap produce one
`Endpointed(SILENCE_CAP)`. Speech that begins after an endpoint starts a new
logical turn. If capture itself ends while a turn is still open (a finite replay
window or a stopped session), the trailing turn finalizes once with
`Endpointed(CAPTURE_ENDED)`; a live capture is continuous, so there the cap is
the guarantee.

**Maximum-silence cap.** After a `CANDIDATE_PAUSE`, silence is counted in
frames; when it reaches `ceil(maxSilenceMillis / frameDuration)` the turn always
finalizes, with or without Smart Turn. A zero cap endpoints at the candidate
pause. The cap is validated (a `Long`, so always finite, and non-negative)
before any audio is processed, so endpointing can never be silently disabled.

**Empty/no-speech.** If capture ends without any detected speech the policy
emits exactly one `Endpointed(EMPTY_NO_SPEECH)`, so callers can tell an empty
capture from a committed utterance instead of committing silence.

**Semantic extension point.** `semanticDetector` is a `TurnCompletionDetector?`.
When present it is evaluated **once per candidate pause** (never per frame) over
the recent-audio window (`RecentAudioWindow`, default 8 s), handed to
`evaluate` as one concatenated `AudioFrame`. `COMPLETE` finalizes; `INCOMPLETE`
holds the turn open; `UNAVAILABLE` or a detector failure records the failure and
falls back to the bounded VAD-only policy. M10 adds Smart Turn v3.2 by passing
its detector — the audio path does not change.

## Configuration and validation

`VadConfig` validates in its constructor, before any audio is processed:

| Field | Default | Validation |
| --- | --- | --- |
| `onsetRmsThreshold` | `0.02` | positive, finite |
| `hangoverRmsThreshold` | `0.01` | positive, finite, `<= onset` |
| `maxZeroCrossingRate` | `0.5` | finite, within `[0, 1]` |
| `onsetFrames` | `3` (~60 ms) | `>= 1` |
| `pauseFrames` | `14` (~280 ms) | `>= 1` |
| `maxSilenceMillis` | `2000` | non-negative |
| `semanticWindowMillis` | `8000` | non-negative |

`VadConfig.forRoute(AudioRouteType)` is **route-aware**: Bluetooth (higher link
noise) and wired/USB headset routes get higher onset/hangover thresholds than the
built-in mic. `CaptureTurnDetection` maps the route observed at the capture
boundary to the config. **All numeric values are provisional starting points,
not Pixel 10 measurements**; M25 owns calibration and R-0045 forbids copying
another project's thresholds.

## Diagnostics

Every transition, hold, and endpoint is recorded through the M04
`DiagnosticsSink` on `DiagnosticStage.TURN_DETECTION`, using the existing
`DiagnosticsSink`/`DiagnosticEvent` model plus three additive attributes:

| Attribute | Meaning |
| --- | --- |
| `VAD_EVENT` | Event name (`SPEECH_STARTED`, `HELD`, `ENDPOINTED`, …). |
| `VAD_REASON` | Stable reason code (`ONSET_RMS`, `PAUSE_SILENCE`, `RESUME_RMS`, `SILENCE_CAP`, …). |
| `VAD_OFFSET_MILLIS` | Offset from capture start. |

Activity events also carry the normalized `AUDIO_RMS_LEVEL` and the active
`AUDIO_ROUTE`; failures carry only `ERROR_CODE`. No sample value, transcript, or
raw audio is ever recorded. A short `AppLog` debug line mirrors each decision so
a developer can follow onset/hold/endpoint in logcat on a debug build; logging
is not per frame. The detector and policy accept optional `TraceId`/`TurnId`, so
M21 can correlate these events with a turn.

## Tests

JVM tests replay the M03 fixtures through the real M02 `AudioInput` contract;
the M03 harness is reused, not duplicated. `BoundedTurnEndpointPolicyTest`,
`EnergyVoiceActivityDetectorTest`, `VadConfigTest`, and `VadSourcePurityTest`
cover:

- onset, candidate pause, and resume detected from clean audio;
- exactly one final endpoint per logical turn, and a pause-plus-resume staying
  one turn (`Held` + `SPEECH_RESUMED`, endpoint only after resume);
- resumed speech after a committed endpoint starting a new turn (two turns, two
  endpoints);
- the maximum-silence cap firing before capture ends, and a finite window's
  trailing turn finalizing once as `CAPTURE_ENDED`;
- empty/no-speech producing exactly one explicit `EMPTY_NO_SPEECH` endpoint;
- complete/incomplete phrases, short acknowledgements, and a sub-threshold
  natural pause not becoming a candidate pause;
- echo, street/car/wind noise, competing speech, gain, clipping, compression,
  codec artifacts, and a music-like tone each ending exactly once and
  deterministically;
- semantic `COMPLETE`/`INCOMPLETE`/`UNAVAILABLE`/failure behavior and the
  bounded semantic window;
- config validation (finite/non-negative thresholds and silence cap) and the
  pure-Kotlin source guard.

Verified commands (Windows host, wrapper):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck
.\gradlew.bat :app:assembleDebugAndroidTest
```

## Manual Pixel 10 checks (no automation)

Onset latency, false endpoints/holds, the right silence cap, and per-route
behavior are **measured on device**, never claimed from the JVM tests. The
instrumented `VadCaptureInstrumentedTest` only proves live capture feeds the
detector and the policy terminates without crashing. See [Tests.md](../Tests.md)
for the M09 checklist.

## Unresolved decisions

- Thresholds, the silence cap, and route-aware values are provisional until M25
  (R-0061).
- The energy/ZCR detector reports activity for loud music or continuous noise;
  barge-in false-interrupt defense must layer duration/echo evidence (R-0062).
- The pure path is not yet wired into the live loop; end-to-end latency, CPU, and
  memory are unmeasured (R-0063).
- The exact semantic window/contract for Smart Turn is confirmed in M10 (R-0064).
