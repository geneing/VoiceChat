# STT Latency Voice Path

The microphone session is isolated for STT latency work. Its active path is:

```text
microphone capture
  -> VAD speech onset and independent endpoint
  -> streaming on-device STT
  -> VAD force-finalize for pending Parakeet text
  -> finalized transcript saved as a user turn
```

The voice session does not resolve an LLM provider, build an LLM request, call
`TurnOrchestrator`, generate assistant text, or initialize TTS. The TTS adapter,
settings, and tests remain in the project, but the microphone session does not
connect to them. The separate text-entry path is unchanged.

This is a focused voice path, not the complete voice-agent experience. It returns
to listening after saving a transcript and produces no spoken or generated
assistant reply. Device validation is still required; see [Tests.md](../Tests.md).

## Ownership and boundaries

- `VoiceSessionAssembly` resolves the configured on-device STT engine and
  creates capture/STT resources. Optional Smart Turn remains available for
  non-Parakeet engines.
- `VoiceSessionCoordinator` owns the capture, VAD event stream, per-utterance STT
  jobs, transcript ordering, timeout, endpoint timing comparison, and transcript
  persistence.
- `VoiceTurnDetector` adapts the bounded VAD endpoint policy. For Parakeet, that
  VAD endpoint runs independently of the model's EOU signal and drives
  force-finalization. The UI shows live recognized text, an EOU tag when the
  model confirms one, and the signed timing difference between EOU and VAD.
- `VoiceSessionListener` reports session state, provisional/final text, Parakeet
  EOU/VAD timing, persisted conversation changes, and typed errors to the UI.
- TTS remains available under `tts/`, but this assembly neither constructs an
  engine nor passes one to the coordinator.

The coordinator stays free of Android types and is JVM-testable. Platform capture,
ML Kit GenAI STT, and model/runtime selection stay behind app-boundary adapters.

## Recognition behavior

- Interim revisions are shown only for the active recognition turn and only when
  their revision number is newer than the previous one.
- Parakeet's adapter keeps one decoder session for the capture and calls
  `forceEndOfUtterance()` on VAD endpoint only when unfinalized text remains. The
  operation commits that segment while retaining encoder/predictor caches for the
  next utterance. A model EOU final is not force-finalized again.
- Parakeet EOU and VAD endpoint offsets are measured independently on the audio
  timeline. The displayed signed difference is `EOU - VAD`; a negative value
  means the model reported EOU first. EOU timing is relative to the STT turn's
  input, and VAD timing is relative to its speech-onset event, so the comparison
  includes the small capture-to-recognizer handoff offset.
- Finalization runs in an owned job while the detector continues to consume
  capture frames. A later finalization waits for the previous one before saving,
  preserving spoken order.
- STT gets a bounded five-second completion window after its input closes. A
  timeout becomes a typed `STT_RECOGNITION_FAILED` error.
- A non-empty final transcript is appended as a persisted voice `UserTurn`. Empty
  or failed recognition does not create a turn.
- Stopping capture cancels recognition and endpoint work. Completed transcript
  data remains in conversation history.

`VoiceSessionState` describes only `IDLE`, `LISTENING`, `FAILED`, and `STOPPED`.
There is no generation or playback state in this microphone path.

## Tests and device status

The JVM coordinator coverage should exercise provisional/final transcript
handling, stale revision rejection, no-speech behavior, timeout recovery,
transcript ordering, persistence, and the real bounded endpoint policy with
deterministic audio/STT fakes. It must not depend on an LLM or TTS fake.

The on-device matrix in [Tests.md](../Tests.md) remains **not run**. Pixel 10
latency/accuracy rows remain empty; no performance claim is established.
