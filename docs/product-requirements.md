# Product Requirements

This document is the planned product baseline. The repository is currently
documentation-only; it does not imply that any listed behavior is implemented.

## Product goals

- Kotlin Android voice agent, primarily developed and validated on Pixel 10.
- On-device speech recognition and speech synthesis.
- Selectable LLM providers: external APIs from
  [LLM providers and connections](./llm-providers.md), plus eligible AICore /
  ML Kit GenAI and selected TFLite / LiteRT models.
- Low perceived response latency, natural turn-taking, and responsive user
  interruption.
- A persistent dialog with both voice and manual text input.

## Voice interaction

1. Show the conversation dialog while the voice session is active. Render the
   user's interim transcription as provisional and commit/update it when the
   selected recognizer revises or finalizes the hypothesis.
2. Start showing the assistant response as LLM text arrives; do not wait for
   the complete answer before updating the dialog.
3. Begin on-device TTS from safe incremental text chunks where the selected
   TTS engine supports it. Keep text and spoken playback state synchronized.
4. Let the user interrupt assistant speech to clarify, correct, or replace
   their request. Stop audible playback and cancel in-flight generation
   promptly, continue capturing the new utterance, and make the interrupted
   response state accurate in the conversation.
5. Provide a manual text composer so the user can submit a typed message or
   correct recognized text. Text and speech turns must use the same
   conversation, provider/model selection, and history behavior.
6. Do not silently rewrite recognized speech. Preserve revisions as provisional
   until finalization; show the text so a user can correct a recognition error.
   Use confirmation for ambiguous or consequential details rather than
   guessing.
7. Treat end-of-turn detection separately from VAD: plan optional support for
   on-device Smart Turn v3.2 after a VAD pause to decide whether the user has
   completed a thought. Keep a maximum-silence endpoint so a turn cannot remain
   open indefinitely. Keep it opt-in until its accuracy, latency, runtime, and
   resource cost have been validated for this app.

## Latency and turn quality

Latency is a first-class requirement, not a final polish task. Measure the
perceived time from user speech ending to first assistant text and to the first
TTS audio actually audible. Track each pipeline stage and optimize p50 and tail
latency without degrading recognition or causing false turn commits.

Stream audio/transcription, LLM deltas, and TTS incrementally wherever the
selected engines and provider permit. Keep prompts and transmitted context
bounded. Expose provider thinking/reasoning effort only when supported, and
make clear that higher reasoning effort may increase latency. Set numeric
budgets only after collecting reproducible Pixel 10 baselines for the relevant
provider/model and conditions.

## Dialog history

The app must persist multiple conversations and let users browse and reopen
older conversations after navigating away or restarting the process. Provide
new-conversation and clear deletion controls. Keep transcript storage separate
from the bounded context sent to an LLM; history is not automatically all
included in every remote request.

## Settings

Provide settings for:

- **STT:** supported on-device engine and model, language/options where
  supported, and calibrated capture/turn-detection controls that can be
  explained to the user. Expose Smart Turn when its model/runtime is installed
  and supported.
- **LLM:** provider, models available from that provider, connection/auth
  status, and provider-supported reasoning/thinking level. External provider
  choices include OpenAI, OpenRouter, OpenCode Go, OpenCode Zen, DeepSeek, and
  Hermes Agent API Server; eligible AICore / ML Kit and TFLite / LiteRT
  options are exposed when available.
- **TTS:** supported on-device engine/model, installed voice, and essential
  playback options.
- The selected engine/model and unavailable/error states must be visible.
  Never display settings that the selected provider or runtime does not
  support.

## Future release scope

Long-term personalized memory and configurable prompt profiles are roadmap
features, not implied requirements for the first usable release. Before
implementation, define what is remembered, how the user inspects/edits/deletes
it, when it is sent to remote providers, and how prompt changes are scoped to
conversations.

## Developer observability

Make LLM and voice-pipeline behavior straightforward to inspect in development.
Each turn should be traceable by ID across STT, provider/model request,
streamed response, TTS playback, interruption, and error states. Record
latencies and safe metadata by default; capture prompt/transcript content only
with explicit opt-in and clear local retention/deletion.

See [voice quality and latency](./voice-quality-and-latency.md),
[validation](./validation.md), and
[privacy and security](./privacy-and-security.md).
