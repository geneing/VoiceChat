# Architecture

## Intended voice path

```text
Microphone
  -> audio capture
  -> VAD + streaming on-device STT
  -> VAD pause candidate -> optional semantic end-of-turn decision
  -> finalized user turn -> conversation orchestration
  -> selected LLM provider
       -> external API (normal path)
       -> supported AICore / ML Kit GenAI or selected TFLite / LiteRT model
  -> response preparation
  -> on-device TTS
  -> speaker
```

This is a target architecture, not an implemented call graph. Add or remove
stages only when the user experience requires it. For example, VAD may improve
turn boundaries and power use, but STT must still handle incomplete, empty, or
interrupted captures.

## Component boundaries

Keep platform and provider details out of conversation policy and UI. The
implementation should have focused boundaries for:

- **Audio capture:** microphone permission, recording lifecycle, audio format,
  and capture errors.
- **STT:** on-device transcription with partial/final results, language and
  model metadata where supported.
- **Turn orchestration:** owns the active turn, cancellation, transcript
  assembly, ordering of LLM/TTS events, and the commit/continue result from
  endpoint detection.
- **Turn detection:** separates fast speech activity/onset detection from
  deciding whether a pause ends a thought. Keep VAD on the audio path; evaluate
  semantic end-of-turn models only at candidate pauses.
- **LLM provider:** a common app-facing contract, with separate adapters for
  external API services and each supported local runtime. Keep provider
  identity, model selection, and authentication method distinct so one API
  protocol does not imply that every provider uses the same credentials.
- **TTS:** on-device synthesis/playback, voice selection, completion, and
  interruption.
- **Model selection:** exposes only models compatible with the current task,
  runtime, and device.
- **Conversation repository:** persists conversations and turns, restores a
  selected conversation, and separates durable transcript history from the
  smaller context sent to a model.
- **Settings and diagnostics:** persists user choices and exposes privacy-safe
  per-turn traces, stage timings, provider/model status, and errors.
- **Conversation UI:** Jetpack Compose screens present live user and assistant
  turns, provisional transcripts, streaming response text,
  playback/interruption state, and a manual text-entry path.

Use the project's eventual module/package conventions rather than introducing
extra modules prematurely. Avoid a single manager that owns recording,
transcription, network requests, model downloads, persistence, and UI state.
The GVP README is useful prior art for a Kotlin/Compose state-holder, coroutine
Flow, explicit speech-engine/router boundaries, and a foreground service only
when microphone use must continue outside the visible UI; adapt rather than
copy its package layout or device-specific constants.

## Streaming and lifecycle

- Keep blocking audio, inference, and network work off the main thread.
- Use structured concurrency. Cancelling a turn should stop its in-flight LLM
  request, close or flush audio resources, and stop TTS playback.
- Treat partial STT text as provisional; commit final transcript text once and
  associate all events with a turn identifier.
- Stream LLM deltas to the dialog immediately and feed safe, complete text
  chunks to on-device TTS as soon as practical. Do not wait for the complete
  LLM response if the provider and TTS engine support incremental output.
- Track the assistant text/audio actually delivered. If the user interrupts,
  stop playback, cancel generation and synthesis, and do not persist unheard
  response text as if the user had heard it.
- Detect likely end-of-turn with tuned acoustic and, where available, semantic
  signals. Consider speculative/preemptive work only if it can be cancelled
  and rolled back safely when the user resumes speaking.
- During playback, distinguish user speech from assistant echo and brief
  backchannels where feasible. Do not rely on a long fixed onset grace period
  that makes a real interruption feel ignored.
- Define behavior for no speech, low-confidence/empty transcription, provider
  errors, timeout, offline state, and user interruption.
- Ensure a failed provider is surfaced as a failure. Any fallback must be an
  explicit, observable selection rather than a hidden change in data handling.
- Keep the full conversation history available through the UI, but bound and
  optimize the context sent each turn. Do not resend every historical
  conversation by default.

## End-of-turn detection

Speech activity and turn completion are separate decisions. VAD identifies
speech onset and candidate pauses; it cannot determine by itself whether a
speaker has finished a thought. Plan optional support for Pipecat Smart Turn
v3.2 as an on-device semantic endpoint classifier after a VAD-confirmed pause.
The speech-android integration describes the audio-native model as evaluating
the preceding eight seconds of the current turn. Running it once per candidate
pause rather than on every frame limits unnecessary inference work.

The initial implementation should use ONNX Runtime for the pinned Smart Turn
artifact only. Keep it behind a replaceable turn-completion interface so a
different model/runtime can replace it later without changing session
orchestration; this does not imply general ONNX model support.

If the classifier says the thought is incomplete, keep the same user turn open
so resumed speech joins the existing transcript. A configurable maximum-silence
cap must still finalize a trailing turn if speech never resumes. Validate
probability thresholds and finite/non-negative silence limits before starting
runtime work. If the model is unavailable, use the explicit VAD-only policy and
report that semantic detection is inactive.

Smart Turn is an endpoint decision, not the interruption detector. Barge-in
must use the low-latency speech-onset path while TTS is playing; do not wait for
an end-of-turn model to stop assistant audio. Evaluate the model on replayed
natural pauses and incomplete phrases, and compare added endpoint latency,
false commits, false holds, memory, and CPU/accelerator behavior before making
it the default. See [device notes](./android-device-notes.md) and
[voice-quality evaluation](./voice-quality-and-latency.md).

## Provider selection

The external API is the default LLM direction. Local LLM inference is optional:
AICore / ML Kit GenAI where the device and requested feature are supported, or
an explicitly supported TFLite / LiteRT model. Keep model/runtime discovery
separate from provider execution so unavailable models can be disabled with an
explanation rather than failing after a turn starts.

For external APIs, expose the planned providers—OpenAI, OpenRouter, OpenCode
Go, OpenCode Zen, DeepSeek, and Hermes Agent API Server—through a provider
catalog, not provider-specific UI branches spread throughout the app. Reuse
an OpenAI-compatible transport only after verifying the selected provider's
current endpoint and required request features; keep provider-specific
authentication and protocol exceptions in adapters.

STT and TTS are on-device components; they should not be routed through the
external LLM provider. See [model and runtime support](./model-runtime.md) and
[LLM providers and connections](./llm-providers.md), and
[privacy and security](./privacy-and-security.md).

## State and interaction model

Use a lifecycle-aware state holder (for example, a ViewModel exposing immutable
StateFlow state) between Compose UI and a voice-session coordinator. Model
capture, transcription, generation, playback, cancellation, and errors as
explicit state/events rather than inferring all behavior from UI booleans.
Persist conversations and messages behind a repository so the conversation
list can reopen earlier chats after process restart. Keep long-term
personalized memory and configurable prompt profiles as a later phase, with
their own user controls and privacy policy.

The primary screen is a dialog: show live/final user transcription and
incremental assistant text while it is spoken. Provide an always-available
manual text composer and clear voice controls. Settings should select an
on-device STT engine/model, an on-device TTS engine/voice, and an LLM provider
plus that provider's models and optional reasoning/thinking level. Show only
capabilities actually supported by the selected provider/runtime.

## Observability

Assign a correlation/turn ID and emit structured stage events for capture,
speech onset/end, STT partial/final, LLM request/first delta/completion,
first audible TTS, interruption, cancellation, and failure. Track stage
durations and provider/model/configuration metadata without logging credentials
or raw audio. Make richer prompt/transcript traces opt-in and local, with clear
redaction and deletion behavior. See
[voice quality and latency](./voice-quality-and-latency.md).
