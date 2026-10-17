# Voice Loop

The microphone session, from speech to a spoken reply. Its active path is:

```text
microphone capture (one AudioRecord, one PCM fan-out)
  -> VAD speech onset and bounded endpoint          (M09; optional Smart Turn at a pause)
  -> streaming on-device STT                        (M08)
  -> finalized transcript persisted as a user turn  (M05)
  -> assistant reply: provider stream + on-device TTS (M21/M17/M11)
  -> capture and VAD keep running throughout        (barge-in)
```

The loop is the coordinator plus two replaceable seams: `VoiceTurnDetector` for
onset/endpoint and `VoiceAssistant` for the reply. With **no** assistant wired the
coordinator is the STT-only loop it started as: a finalized transcript is saved as
a user turn, no reply is generated, and TTS is not constructed. With an assistant
wired, the assistant owns both turns through M21 orchestration and the coordinator
owns the conversational sequencing below.

## Barge-in rules

1. **Capture never stops for playback.** VAD and STT run against the same incoming
   audio while the assistant speaks, so the next turn is already being recognized
   when the reply is still being delivered.
2. **VAD onset stops the reply immediately.** `VoiceAssistant.interrupt` is called
   from the detection path the moment speech activity starts — with no transcript,
   no recognizer result, and no Smart Turn decision in front of it. TTS playback
   stops and the in-flight provider request is cancelled; orchestration records
   how much audio was actually audible, and the interrupted assistant turn is
   persisted with only that delivered prefix. The saved conversation therefore
   matches what the user heard.
3. **STT supplies the words throughout.** Recognizer partials update the
   provisional transcript for the new turn. `SpeechRecognizer.onBeginningOfSpeech`
   is at most a corroborating signal: its timing and availability depend on the
   recognition service.
4. **A VAD pause is what asks Smart Turn.** The semantic detector runs only after a
   VAD-confirmed pause, never on every frame and never as the barge-in detector.
   "Incomplete" keeps listening; "complete" finalizes the transcript. The bounded
   maximum-silence cap remains the fallback when STT or Smart Turn stalls, and
   `onEndOfSpeech` is surfaced for timing but never ends a turn.

Ordering is preserved: a new user turn waits for the interrupted reply to finish
persisting, so an interrupted assistant turn is never written after the turn that
interrupted it.

## Audio capture path

One capture is opened and fanned out: the platform recognizer consumes the same
PCM through `RecognizerIntent.EXTRA_AUDIO_SOURCE` (segmented session mode on API
33+), so VAD, Smart Turn, and STT all see one stream rather than competing for the
microphone. A recognizer that ignores the extra opens the microphone itself, and
Android may silence one of two competing capture clients, so the piped path is the
supported one (`docs/android-speech-recognition.md`).

**Echo.** The assistant is audible on the same device that is listening, so its own
speech can reach the microphone and look like user speech. Android's
`AcousticEchoCanceler` is optional and session-scoped: the capture path attaches it
to the `AudioRecord` session, and reports `ENABLED`, `NO_SESSION`, `UNAVAILABLE`,
or `FAILED` as a capture diagnostic instead of assuming the mic is echo-free. A
Pixel 10 probe measured the residual at mean `0.005` / p95 `0.010` / peak `~0.02`
with AEC enabled, so AEC alone does not keep the assistant out of the VAD. The
loop therefore raises the onset bar while the assistant is audible
(`VadConfig.playbackOnsetRmsThreshold`, a state and not a fixed grace period), so
the residual cannot start a turn while the user's own voice still can. Whether
the canceller actually removes the assistant on a given route (speaker, wired, or
Bluetooth) is a device measurement (R-0254, R-0260).

**Pre-roll (opening-word clipping).** A recognition turn starts when the VAD
declares speech onset, which is a few frames *after* speech actually begins — and
an opening syllable quieter than the onset threshold can delay the decision by
several words. So the coordinator keeps a small rolling buffer of the most recent
capture frames (`CapturePreRoll`, 50 frames ≈ 1.0 s) and, when a turn starts,
feeds those frames to the recognizer before the live audio continues. Without it
the recognizer only ever saw audio from the onset onward and the transcript began
mid-sentence. The buffer is cleared when a turn ends, so the next turn cannot
inherit the previous utterance's tail; on a barge-in the pre-roll can still
contain up to a second of the assistant's (echo-cancelled) audio, which is a
device measurement (R-0259).

## Ownership and boundaries

- `VoiceSessionAssembly` resolves the configured on-device STT engine, builds
  capture/STT resources, resolves the optional Smart Turn detector, and resolves
  the optional assistant from the persisted settings.
- `VoiceSessionCoordinator` owns capture, the VAD/Smart Turn event stream,
  per-utterance STT jobs, transcript ordering and timeout, barge-in, endpoint
  timing, and — only when no assistant is wired — transcript persistence.
- `VoiceTurnDetector` adapts the M09 bounded endpoint policy; the semantic detector
  is optional and evaluated at a pause only.
- `VoiceAssistant` is the reply seam: `OrchestratingVoiceAssistant` runs the M21
  `TurnOrchestrator` (stream → sentence chunking → TTS → delivery accounting) and
  maps its callbacks onto `VoiceSessionListener`.
- `VoiceSessionListener` reports session state, provisional/final text, live
  assistant text, the spoken reply, persisted conversation changes, and typed
  errors to the UI.

The coordinator, the assistant, and its tests stay free of Android types;
platform capture, STT, TTS, and provider adapters stay behind app-boundary
factories. A provider, TTS, or persistence failure is surfaced as a typed error
and the loop keeps listening: a failed reply is visible and recoverable, never a
dead session and never a silent fallback to another model.

## Recognition and reply behavior

- Interim revisions are shown only for the active recognition turn and only when
  their revision number is newer than the previous one.
- Finalization runs in an owned job while the detector continues to consume
  capture frames. A later finalization waits for the previous one before saving,
  preserving spoken order.
- STT gets a bounded five-second completion window after its input closes; a
  timeout becomes a typed `STT_RECOGNITION_FAILED` error.
- A non-empty final transcript is the turn's text. Empty or failed recognition
  does not create a turn, and the recognizer's own final is never forced.
- The provider stream is chunked at sentence boundaries (160-character cap) and
  each complete sentence is spoken as soon as it exists, which is what makes the
  first sentence audible about a second after the request starts
  (`docs/voice-llm-selection.md`).
- The selection (provider, model, reasoning level) comes from the persisted
  settings through the same resolver the typed path uses, so a spoken reply and a
  typed reply never silently use different models.
- `VoiceSessionState` is `IDLE`, `LISTENING`, `SPEAKING`, `FAILED`, or `STOPPED`.
  `SPEAKING` covers generation and playback; speech onset returns the loop to
  `LISTENING` immediately, so the state never delays a barge-in.

## Tests and device status

JVM coverage: `VoiceSessionCoordinatorTest` (the isolated capture → STT → endpoint
path with no assistant), `VoiceAssistantLoopTest` (a transcript answered, onset
interrupting a reply before any new transcript exists, a failed reply staying
visible), `EchoCancellationTest` (the canceller decision and capture wiring), and
the opt-in `VoiceLlmHarnessTest` for the model timings.

The on-device matrix in [Tests.md](../Tests.md) remains **not run** for the voice
loop with a reply: Pixel 10 time-to-first-audio, echo behaviour on the real
routes, and the end-to-end turn are unmeasured (R-0254, R-0255). No performance
claim is established.
