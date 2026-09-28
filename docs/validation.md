# Validation Plan

The app and test infrastructure do not exist yet. This plan is a checklist for
the implementation phase, not a report of completed testing.

## Automated speech and pipeline test harness

Most routine speech-path regression tests should replay fixed inputs rather
than depend on a live microphone or a human being available for every run.
Build a harness that can feed timestamped PCM recordings directly into capture,
VAD, and STT boundaries and run deterministic LLM/TTS fakes for orchestration.

- Keep a labeled, permissioned reference corpus of human speech with expected
  transcripts and metadata such as language, speaker/accent where consented,
  microphone path, and environment.
- Generate additional speech with TTS in the harness, then create reproducible
  variants for street and in-car conditions: road/engine/wind noise, competing
  voices, reverberation, speaker echo, gain changes, clipping, compression, and
  codec artifacts. Record transformations and random seeds.
- Use synthetic speech for broad deterministic coverage, not as the only
  accuracy evidence; retain consented human-speech and on-device acceptance
  tests because TTS does not represent real accents, disfluencies, and
  microphone behavior.
- Measure STT word/character error rates by condition and also task-relevant
  entity/intent errors (names, numbers, negation, corrections, and short
  acknowledgements). Keep model/engine versions and audio preprocessing
  attached to each result.
- Test partial-hypothesis revisions, endpointing during natural pauses,
  false barge-ins from noise/echo, true first-word interruptions, and recovery
  when an interruption hypothesis proves false.
- For Smart Turn v3.2, test both the model load/download path and endpoint
  behavior with fixed replay clips: completed statements, incomplete phrases
  followed by resumed speech, short/long pauses, and trailing speech followed
  by silence. Verify a veto keeps the same turn open and the maximum-silence
  cap eventually emits exactly one endpoint.
- Sweep the completion threshold and maximum-silence cap against labeled
  speech; report false-commit rate, false-hold rate, added end-of-turn latency,
  memory, and CPU use. Compare Smart Turn with VAD-only behavior before
  selecting a default.
- Verify endpoint inference runs only after a candidate VAD pause, not for
  every audio frame, and verify missing/corrupt optional model behavior is
  explicit and bounded.

Use LLM fakes for deterministic unit coverage, then a small opt-in provider
smoke suite for real APIs. Each run should produce a trace correlating provider,
model, reasoning level, request/response lifecycle, streamed deltas, failures,
and timing. Default traces must omit keys, full prompts, transcripts, and audio;
local sensitive-content capture must require explicit developer opt-in.

## Unit tests

Add tests for:

- Turn orchestration, partial/final transcript handling, and event ordering.
- Provider/model availability and explicit selection behavior.
- Provider-specific API-key setup, supported sign-in/QR pairing, credential
  replacement/removal, and authentication failures. Confirm unsupported auth
  methods are not displayed as available.
- LLM request construction, response parsing, streaming, cancellation, timeout,
  and HTTP error mapping.
- Response preparation before TTS, including empty and interrupted responses.
- Conversation create/list/reopen/rename/delete and separation of transcript
  persistence from bounded model context.
- STT correction/revision handling and UI state for voice and text turns.
- Model catalog validation and app-managed download integrity/lifecycle, if
  model downloads are implemented.

Use fake capture, STT, LLM, and TTS implementations where possible; unit tests
must not require real credentials, network access, or downloaded models.

## Android integration tests

Test microphone permission denial/revocation, capture start/stop, lifecycle
cleanup, audio focus/routing, and TTS stop/interruption behavior. Keep tests for
hardware-dependent features distinct from deterministic unit tests.

For AICore / ML Kit and TFLite / LiteRT, test both a supported configuration and
an unavailable or failed initialization state. Validate actual input/output
contracts using the selected model artifacts and runtime.

Validate the planned Smart Turn v3.2 ONNX Runtime dependency and quantized
artifact on Pixel 10. The speech-android test suite demonstrates a useful
integration pattern: synthesize a fixed phrase with TTS, resample to the
pipeline's expected 16 kHz PCM, feed fixed-size frames, create a pause, and
assert hold/maximum-silence endpoint behavior. This complements, but does not
replace, labeled human-speech endpoint evaluations.

## Pixel 10 end-to-end checks

On the target Pixel 10, verify:

- The complete local STT -> external LLM API -> local TTS path.
- Optional on-device provider paths when the relevant model/API is available.
- Unavailable AICore/model, denied microphone permission, airplane mode,
  network loss, API error, empty speech, and user barge-in behavior.
- Run the same labeled recordings and synthetic noise/distortion variants
  through the actual Pixel capture path, including built-in microphone,
  speakerphone playback/echo, and headset routing where supported.
- Calibrate capture level, noise floor, STT thresholds, endpointing, and
  interruption behavior on this device. Do not copy gain, RMS thresholds,
  debounce, or TTS grace-period values from GVP without measuring their effect
  in this app.
- Measure time from end of user speech to first LLM text, first TTS audio
  actually audible, and completed response, plus each stage's latency. Report
  distributions (including tail latency) per selected provider/model and
  reasoning level; set performance budgets from measured baselines.
- Interrupt at multiple points during LLM streaming and TTS playback. Verify
  playback stops promptly, generation is cancelled, the next user utterance
  is captured, and stored history reflects only delivered assistant output.
- Reopen past conversations and verify state survives process death. Verify
  manual text turns use the same conversation and provider/settings path.
- Model/API choice and the remote text transfer are apparent to the user.
- Each configured provider works only with its documented auth methods; QR
  pairing never exposes a reusable credential.
- Latency and memory measurements are repeatable and labeled with device,
  Android version, provider, model, and test conditions.

Do not report a build, test, model, or performance result until it has actually
been run. Add exact Gradle wrapper commands here after the Android project is
scaffolded.

## External best-practice references

These inform the test and design strategy; provider-specific and cloud-STT
recommendations must be adapted and validated for this app's on-device engines.

- [LiveKit: Adaptive interruption handling](https://docs.livekit.io/agents/logic/turns/adaptive-interruption-handling/)
  and [turn handling](https://docs.livekit.io/agents/logic/turns/) — false
  interruption handling, backchannels, turn detection, and preemptive work.
- [Microsoft: Voice agent best practices](https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/voice-agent-best-practice)
  and [voice agent observability](https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/voice-agent-observability)
  — time-to-first-audio, turn tuning, streaming, and trace/monitoring concepts.
- [Google Cloud Speech-to-Text best practices](https://docs.cloud.google.com/speech-to-text/docs/best-practices)
  and [model adaptation](https://cloud.google.com/speech-to-text/docs/adaptation)
  — audio capture and vocabulary-biasing principles; these are cloud STT docs,
  not a claim that this app sends speech to Google Cloud.
- [GVP README](https://github.com/m15-ai/droidkaigi2026-app-gvp/blob/main/README.md)
  — Android architecture and Pixel 10 observations documented in
  [device notes](./android-device-notes.md).
- [speech-android Smart Turn v3.2 integration](https://github.com/soniqo/speech-android/blob/main/README.md#end-of-turn-detection)
  — optional semantic completion after VAD pauses; use its behavior and tests
  as a design reference, not as pre-validated settings for this app.
