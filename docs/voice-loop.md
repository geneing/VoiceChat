# Voice Loop and Responsive Barge-in (M24)

This document records the M24 voice loop: the pure-Kotlin session coordinator
that wires M07 capture → M09 VAD/onset → M08 STT → M21 turn orchestration → M11
TTS, and the rules that keep an interrupted reply truthful. It implements the
"Streaming and lifecycle" and "Responsive barge-in" requirements in
[architecture.md](./architecture.md) and
[voice-quality-and-latency.md](./voice-quality-and-latency.md) over the M02
contracts.

It closes or advances several tracked items: the missing voice path (R-0033), the
unerwired live capture → VAD → STT → orchestration loop (R-0063, R-0082), the
single-active-turn UI limitation and barge-in onset path (R-0083), and the
still-open on-device barge-in measurement (R-0046). The platform assembly is
compiled and wired but **device testing is deferred** (see [Tests.md](../Tests.md)).

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/voice/
  VoiceSessionController.kt   start/stop contract + factory (UI seam)
  VoiceSessionListener.kt     progress sink for the dialog
  VoiceSessionState.kt        session states + interruption recovery + timings
  VoiceTurnDetector.kt        onset/endpoint seam + M09-backed implementation
  VoiceTurnProvider.kt        per-turn provider identity/adapter seam
  VoiceSessionCoordinator.kt  the pure-Kotlin core (android.*-free)
  VoiceSessionAssembly.kt     the only platform file (capture/STT/TTS/Context)
app/src/test/kotlin/com/voicechat/agent/voice/   JVM tests (+ source purity guard)
app/src/test/kotlin/com/voicechat/agent/fake/VoiceFakes.kt  deterministic fakes
app/src/androidTest/kotlin/com/voicechat/agent/voice/  compile-only smoke
```

Only `VoiceSessionAssembly.kt` touches `android.*` (`Context`,
`MicrophoneAudioCapture`, `MlKitSpeechToText`, `OnDeviceTts`); every other file
in `voice/` is pure Kotlin and does not depend on the Compose `ui` package,
enforced by `VoiceSourcePurityTest`. The coordinator therefore runs under
`:app:testDebugUnitTest` with deterministic fakes, no device, network, credential,
microphone, or real TTS.

## Session states

`VoiceSessionState` is deliberately coarser than the per-turn
`domain.TurnPhase`: one capture session spans many logical turns, so it describes
what the loop is doing *right now*.

```text
IDLE -> LISTENING -> WORKING -> SPEAKING -> (LISTENING | IDLE)
LISTENING -> LISTENING            (a new logical turn after an endpoint/barge-in)
any -> STOPPED                    (user stop or capture end; terminal)
any -> FAILED                     (typed STT/capture/provider error; recoverable)
```

`STOPPED` is terminal: a turn that settles asynchronously after `stop()` (for
example, an interrupted reply being persisted in a `NonCancellable` block) must
not clobber it back to `IDLE`/`LISTENING`. The coordinator latches that in
`setState`, while still persisting the interrupted turn truthfully.

The dialog renders the state as a short status line (for example "Listening…",
"Thinking…", "Speaking…"); the portable `VoiceLoopUiIntegrationTest` asserts the
start/stop wiring and the live-text mapping.

## Two independent decisions

Barge-in and end-of-turn are separate, as the architecture requires:

1. **Fast onset (barge-in).** The M09 activity path emits `SPEECH_STARTED` /
   `SPEECH_RESUMED`. On either, while a generation is active, the coordinator
   immediately interrupts and starts the next capture. It never runs a semantic
   model on the onset path, so a barge-in does not wait for end-of-turn
   inference.
2. **Bounded endpoint (turn completion).** The same M09
   `BoundedTurnEndpointPolicy` emits exactly one `Endpointed` per logical turn
   (semantic complete, silence cap, capture end, or empty/no-speech). Only then is
   the finalized transcript committed as a user turn.

Endpoint finalization is an **owned per-turn job**, not work done inside the
detector collector. The detector keeps draining audio/events while the recognizer
finishes (bounded by the completion timeout), and each finalization joins the
previous one before committing, so turns persist in spoken order and a new onset
is never blocked behind a slow recognizer (R-0225).

`VoiceTurnDetector` is the seam: production uses `PolicyVoiceTurnDetector` over
the real bounded policy, and tests drive a deterministic script. The coordinator
receives both signals from one event stream and keeps the two decisions distinct.

## Barge-in does not wait for cancellation

On a speech onset while a generation is active, the coordinator:

1. calls `TurnOrchestrator.interrupt(onsetAtNanos)`, which cancels the in-flight
   provider request and all TTS jobs and settles the turn as `Interrupted` with
   only the delivered prefix. The pending/running generation is one explicit state
   (turn id, job, optional orchestrator): if a newer turn is still queued behind
   the interrupted one and has **no orchestrator attached yet**, there is no
   provider request to interrupt, so the queued job is replaced by the new
   utterance instead of being mistaken for an interruptible request (R-0226);
2. stops audible playback at once from a separate coroutine (`TextToSpeech.stop`,
   which also clears queued audio) and does **not** join it;
3. begins the next capture/recognition turn immediately.

The next turn's *generation* is serialized behind the interrupted turn's
persistence (`previous?.join()`), so the new provider request's bounded context
includes the interrupted turn's stored truth and the two saves cannot race — but
capture and transcription of the new utterance are already underway, so the loop
never waits for a cancellation acknowledgement to keep listening.

Every barge-in records a `BargeInTiming` with monotonic timestamps:

| Field | Meaning |
| --- | --- |
| `onsetAtNanos` | When the fast onset path reported speech. |
| `stopIssuedAtNanos` | When playback stop was issued (before any ack). |
| `captureResumedAtNanos` | When the next capture/recognition started. |
| `settledAtNanos` | When the interrupted provider/TTS work reached its terminal state. |
| `recovery` | How the new utterance resolved (see below). |

`onsetToStopNanos` is the number the user perceives; `onsetToCaptureResumedNanos`
proves the loop kept listening without waiting for the cancellation. Both are
recorded as privacy-safe diagnostics (`BARGE_IN_STOP_MILLIS`,
`BARGE_IN_CAPTURE_RESUMED_MILLIS` on `DiagnosticStage.TURN`) and in a short
`AppLog` debug line; no transcript or audio is ever recorded.

## Turn IDs drop stale events

The coordinator adds a layer of turn-ID gating above the M21 machine:

- an interim STT revision is delivered to the dialog only while it belongs to the
  *active listening* turn, and only when its revision is strictly newer;
- a late interim that arrives after the turn's endpoint (while the recognizer is
  finalizing) is recorded on the turn but never shown;
- live assistant text is delivered only while its turn is the *active generation*
  turn (the explicit active-generation state), so a straggler from an
  interrupted/superseded turn cannot update the dialog;
- a turn that settles after a newer generation started does not overwrite the
  newer turn's session state.

Inside the turn, the M21 `TurnStateMachine` applies the same rule to provider and
TTS events (see [orchestration.md](./orchestration.md)).

## Truthful history and interruption recovery

The coordinator adopts only the orchestrator's persisted `TurnResult.conversation`;
it never re-derives assistant text from live deltas. An interrupted reply is
stored with only the delivered prefix (`AssistantDelivery.deliveredText` is
always a prefix of generated text) and `INTERRUPTED` delivery; a turn that
generated nothing stores no phantom assistant turn.

A barge-in detection can turn out to be a real interruption, a short
acknowledgement/backchannel, or noise/echo the fast VAD flagged. The recovery is
explicit (`VoiceInterruptionRecovery`):

- **`COMMITTED`** — a usable transcript was recognized and committed as a new
  user turn (a true interruption, or a short acknowledgement that the recognizer
  produced text for). The new turn proceeds normally.
- **`NO_USABLE_SPEECH`** — the onset produced no usable transcript (noise/echo,
  or a sub-threshold backchannel). No turn is committed; the interrupted reply
  stays interrupted with only its delivered prefix. The dialog is left usable
  (the cancelled notice is cleared) and the event is not silently lost.

No long fixed playback grace period is added: a real interruption is never made
to feel ignored while echo is hidden. Echo/noise defense is layered on the
existing VAD and the recovery policy; **AEC is not implemented and is not copied
from another project** — it is investigated only from measurements (R-0045,
R-0046). The onset path is the fast VAD; a duration/echo-evidence layer is future
tuning (R-0062).

## Platform assembly

`VoiceSessionAssembly.platformFactory` is the only app-boundary entry point. It
builds a fresh session per call from the single-use platform resources:

- **STT** — the first catalog engine whose `MlKitSttStatus.check` is `Ready`
  (`MlKitSpeechToText`); if none is ready the session reports a typed
  `STT_UNAVAILABLE` and stays text-only rather than fabricating a session.
- **Capture** — `MicrophoneAudioCapture` (M07), which owns the permission and the
  `AudioRecord` lifecycle.
- **TTS** — `OnDeviceTts` (M11); if no embedded on-device voice exists, the
  session runs without speech rather than using a network voice.
- **Turn detection** — `PolicyVoiceTurnDetector.forRoute`, the route-aware M09
  bounded policy.
- **Provider** — resolved per committed utterance through the M22/M23 selection
  (`VoiceTurnProviderSource`), so changing the model in Settings affects the next
  voice turn without restarting the session. A provider failure is its own typed
  failure; the loop never silently falls back.

`MainActivity` builds the factory once and passes it to `VoiceAgentRoot`; the
microphone permission is requested at the point of use, and the voice control is
rendered only when the app attached a factory, so the M06/M23 text path is
unchanged when voice is off.

## Tests

Deterministic JVM tests under `:app:testDebugUnitTest`:

- `VoiceSessionCoordinatorTest` drives the real coordinator over the M02 contracts
  with deterministic fakes and the M03 replay fixtures. It covers a committed
  voice turn, no-speech, stale interim revisions, a late post-endpoint interim,
  and interruptions at multiple points:
  - during LLM streaming (after deltas, before the reply finishes);
  - during TTS playback (a slow chunk is cut off; only the audible prefix is
    stored);
  - before any assistant text (no phantom assistant turn);
  - with the next turn then completing normally, proving each turn's live text is
    tagged with its own turn ID and history keeps A-interrupted + B-completed.
- It also covers false/noise interruption recovery (`NO_USABLE_SPEECH`, no
  phantom turn), short acknowledgements (`COMMITTED`), stop ending the session,
  the recorded stop/capture/settle timing and its diagnostic, and the real M03
  replay + M09 bounded policy end to end.
- `VoiceSourcePurityTest` enforces the `android.*`-free core.
- `ui.VoiceLoopUiIntegrationTest` asserts the dialog's live-text mapping and
  start/stop wiring against a scripted session controller.

A compile-only instrumented smoke test (`voice.VoiceSessionInstrumentedTest`)
runs the real coordinator with inline fakes on device; it is compiled by
`:app:assembleDebugAndroidTest` and run only by `:app:connectedDebugAndroidTest`.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck
.\gradlew.bat :app:assembleDebugAndroidTest
```

## Manual Pixel 10 checks (no automation)

Onset-to-stop latency, echo/noise false-interrupt behavior, first-word
interruptions, double-talk, and route changes are **measured on device**, never
claimed from the JVM tests. The M24 checklist lives in [Tests.md](../Tests.md)
(echo, road noise, music, double-talk, first-word interruptions, route changes,
and stop/cancel timing); it **was not run** for this milestone.

## Limitations and next steps

- The live path is wired and compiled but not run on hardware; barge-in timing,
  echo/noise false-interrupt rates, and route behavior are unmeasured (R-0046,
  R-0063, R-0083).
- Capture (M07) and TTS (M11) still hold transient `USAGE_ASSISTANT` audio focus
  independently; simultaneous operation and the duck/pause policy are unmodeled
  (R-0050, R-0060).
- TTS delivery still uses one child coroutine per complete chunk rather than a
  bounded queue, and first-audible/inter-chunk timing is unmeasured (R-0080).
- The delivery wait is bounded by a 60 s timeout that degrades a wedged engine to
  `Interrupted`; real engine stop/hang behavior is unverified (R-0081).
- AEC is not implemented; any future echo cancellation or onset-evidence layer is
  driven by Pixel 10 measurements, not copied thresholds (R-0045, R-0062).
