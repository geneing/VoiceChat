# Voice Quality and Latency

Low perceived latency, accurate transcription, and responsive interruption
should be designed and tested together. Speech stages are coupled: changing
capture gain, noise suppression, VAD thresholds, or endpointing can affect both
STT quality and barge-in.

## Latency strategy

Track at least:

- speech-end / turn-commit time;
- STT first partial and final transcript time;
- LLM request start, time-to-first-token/text, inter-delta gaps, and completion;
- TTS first synthesis result and first audio actually audible;
- user-speech onset to playback-stop/cancel acknowledgment during barge-in;
- per-stage and total latency, including p50 and tail distributions.

Use a per-turn trace ID to correlate events. Separate local processing,
provider/network wait, and audio playback so regressions have an actionable
cause. Benchmark each provider, model, and reasoning level independently.
Measure audible playback, not merely when a TTS API returns.

To improve responsiveness:

- Stream transcript hypotheses, LLM deltas, and TTS chunks when supported.
- Begin LLM/TTS work as early as safely possible, but keep provisional-turn
  work cancellable and roll back results if the user resumes speaking.
- Keep prompts, history sent to the model, and output length purposeful and
  bounded. Persisted conversation history is not the same as inference context.
- Use provider-supported model/reasoning settings deliberately; higher
  reasoning effort may trade latency for answer quality.
- Profile repeated-turn context/prefill behavior. Persistent sessions or
  provider prompt caching are optimization options only when the selected
  runtime supports them and interruption/history semantics remain correct.
- Define and track performance budgets after establishing repeatable baselines
  on Pixel 10; do not infer a universal threshold from one provider or device.

## Responsive barge-in

Treat interruption as a coordinated state transition, not just a VAD callback:

1. Detect likely user speech during assistant playback.
2. Stop the audible TTS stream immediately and cancel its queued audio.
3. Cancel the in-flight LLM request and any speculative work.
4. Continue capturing/transcribing the user's new utterance without waiting for
   every cancellation acknowledgment.
5. Reconcile conversation context with assistant content actually delivered;
   do not represent unheard text as spoken.
6. If evidence later shows the event was noise/backchannel rather than an
   interruption, use an explicit recovery policy rather than silently losing
   the assistant response.

Use layered onset evidence (for example, VAD/voice classification, duration,
and acoustic echo information) where available. Tune thresholds for built-in
speaker and headset paths. Test true interruptions, short acknowledgements,
echo, music, road noise, and other transients. Avoid a long fixed TTS-onset
grace period: it can hide echo triggers but also make users wait to interrupt.
Use measured, adaptive policies and log why each interrupt decision was made.

Endpointing must balance latency with natural pauses and corrections. Do not
commit a turn merely because one partial transcript arrived or speech energy
briefly stopped. Use STT endpoint signals and acoustic/semantic turn detection
when supported, with a safe cancel/revise path for eager work.

### Smart Turn v3.2 as a semantic endpoint

The speech-android project uses Pipecat Smart Turn v3.2 as an optional
audio-native classifier after VAD has identified a candidate pause. VAD
identifies that speech has paused; Smart Turn estimates whether the speaker's
thought is complete. On a veto, resumed speech remains in the same turn. A
maximum-silence cap guarantees a final endpoint if the speaker does not resume.

This is separate from barge-in onset detection. Keep interruption response on
the fast VAD/acoustic path; run semantic completion only at candidate
endpoints, not on every audio frame. Tune the completion probability threshold
and silence cap against the replay corpus. Sweep thresholds and report both
false commits (cutting off an unfinished thought) and false holds (avoidable
delay), along with inference cost and end-of-turn latency. Do not treat a model
score as ground truth.

The referenced speech-android configuration defaults Smart Turn to opt-in,
uses a `0.5` completion threshold, and caps silence at `2.0 s`; these are
starting values for that library, not established defaults for this app. Use a
maximum silence in all configurations, including VAD-only mode.

## Recognition quality

- Preserve streaming hypotheses as revisable; downstream conversation state
  consumes a finalized transcript, not a stale interim guess.
- Use language, vocabulary hints, or contextual adaptation only when the
  selected on-device engine supports them. Bias toward relevant names/terms,
  not a huge generic phrase list.
- Capture clean PCM at the recognizer's expected format. Measure the effect of
  gain, AEC, and denoising by model/device; do not assume stronger
  preprocessing always improves recognition.
- Keep the user's raw recognition visible/editable. Do not silently "correct"
  names, numbers, negation, or intent using the LLM.
- Evaluate both transcription error (WER/CER) and task-critical meaning errors
  such as changed numbers, names, negation, and short yes/no acknowledgements.
- Include varied speakers, accents, speaking rates, disfluencies, pauses,
  overlapping assistant echo, and low-SNR cases.

## Repeatable evaluation

Use the test harness described in [validation](./validation.md):

- replay labeled pre-recorded PCM through the same pipeline interfaces;
- generate broad deterministic speech using harness TTS, then apply
  repeatable street/car noise, competing speech, echo, reverberation, gain,
  clipping, compression, and codec transformations;
- preserve source, expected transcript, transformation parameters, random seed,
  sample rate, engine/model versions, and device/audio path for every case;
- compare against a smaller permissioned human-speech corpus and Pixel 10
  microphone/speaker/headset checks; synthetic TTS alone is not an accuracy
  benchmark.

## LLM traceability

Expose a local development trace with turn ID, selected provider/model,
reasoning setting, request lifecycle, streamed delta timing, token/usage
metadata where returned, cancellation, retries, error category, and TTS
playback state. Default production logs must not include secrets, raw audio,
full prompts, or full transcripts. Sensitive-content capture for debugging
requires explicit opt-in and bounded local retention.

## References

- [GVP README](https://github.com/m15-ai/droidkaigi2026-app-gvp/blob/main/README.md)
  — architecture, measured Pixel 10 capture/AEC behavior, AICore contention,
  and repeated-turn prompt latency; see
  [Android device notes](./android-device-notes.md).
- [speech-android README: end-of-turn detection](https://github.com/soniqo/speech-android/blob/main/README.md#end-of-turn-detection),
  [SmartTurnTest](https://github.com/soniqo/speech-android/blob/main/sdk/src/androidTest/kotlin/audio/soniqo/speech/SmartTurnTest.kt),
  and [SpeechConfig](https://github.com/soniqo/speech-android/blob/main/sdk/src/main/kotlin/audio/soniqo/speech/SpeechConfig.kt)
  — optional Smart Turn v3.2 after VAD pauses, bounded silence, configuration
  validation, and an instrumented TTS-generated pause test.
- [LiveKit adaptive interruption handling](https://docs.livekit.io/agents/logic/turns/adaptive-interruption-handling/)
  and [turn handling](https://docs.livekit.io/agents/logic/turns/) — adaptive
  interruption decisions, false interruptions, and turn detection.
- [Microsoft voice agent best practices](https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/voice-agent-best-practice)
  and [voice agent observability](https://learn.microsoft.com/en-us/azure/foundry/agents/concepts/voice-agent-observability)
  — first-audio responsiveness, streaming, and correlated traces.
- [Google Cloud Speech-to-Text best practices](https://docs.cloud.google.com/speech-to-text/docs/best-practices)
  and [model adaptation](https://cloud.google.com/speech-to-text/docs/adaptation)
  — microphone/audio quality and phrase adaptation principles. These are
  cloud-STT references; the app's speech audio remains on-device.
