# Agent Instructions

## Project intent

This repository is the documentation-first skeleton for a Kotlin Android voice
agent, initially targeting Pixel 10. The intended voice path is on-device
speech-to-text (STT) and text-to-speech (TTS), with the primary language-model
(LLM) path provided by an external API. Keep optional on-device inference
possible through supported AICore / ML Kit GenAI APIs and a deliberately
curated set of TensorFlow Lite (TFLite / LiteRT) models.

The app and its architecture are not implemented yet. Treat these documents as
requirements and direction, not as proof that a feature, dependency, build,
model, or device capability already exists. Update this file when the actual
project structure and verified commands are established.

## Product and platform constraints

- Use Kotlin for application and Android integration code. Do not introduce
  Java or C++ unless an unavoidable interop boundary is documented.
- Pixel 10 is the primary physical test device. You can assume it has AICore, but it may not have a
  provisioned model, a particular accelerator, or the same system TTS voices.
- Keep microphone capture, STT, LLM, and TTS behind small replaceable
  boundaries. A failure or unavailable optional provider must be visible and
  recoverable; do not silently switch providers or report false success.
- Treat perceived latency as a primary product requirement. Stream partial
  STT, LLM output, and TTS audio where supported; instrument stage timings and
  optimize time-to-first-audio, not only total completion time.
- Make barge-in responsive: detect speech during assistant playback, stop
  audible TTS promptly, cancel in-flight generation, and reconcile stored
  conversation state with what was actually delivered. Defend against
  echo/noise false interruptions without adding a long fixed interruption
  grace period.
- Keep speech-onset VAD and end-of-turn detection as separate decisions.
  Plan optional Smart Turn v3.2 support after a VAD-confirmed pause, with a
  bounded maximum-silence fallback; do not run an endpoint classifier on every
  audio frame or use it as the barge-in onset detector.
- Keep the dialog visible during voice use, including live transcript and
  assistant output; provide text entry and persistent, reopenable conversations.
- Keep STT and TTS on-device. The normal cloud path sends only the user-approved
  text/context needed for an LLM request, not microphone audio.
- Treat external LLM use as a network feature: disclose the transfer, handle
  authentication securely, support cancellation and network errors, and do not
  claim the full conversation works offline.
- Plan for OpenAI, OpenRouter, OpenCode Go, OpenCode Zen, DeepSeek, and Hermes
  Agent API Server as external LLM providers. Support user-entered API keys and
  other connection methods a provider officially offers, such as browser/device
  authorization or QR pairing. Provider availability and auth methods must be
  checked individually; do not imply all providers support the same methods.
- Use AICore only through supported platform or ML Kit GenAI APIs. Check
  feature/model availability at runtime; system-managed models are not app
  assets to download or modify.
- Support only explicitly selected and validated TFLite / LiteRT models. Record
  each model's task, source, license, format/runtime requirements, and
  performance constraints before adding it.
- Include ONNX Runtime support narrowly for the pinned Smart Turn v3.2 ONNX
  artifact. Keep it behind a replaceable turn-detector boundary so the model
  can be changed later; do not infer general support for arbitrary ONNX models
  or assume TFLite/LiteRT can load ONNX artifacts.
- Add settings for on-device STT/TTS choices and for each supported LLM API,
  its available models, and its reasoning/thinking control when supported.
  Never show unsupported model options or provider parameters.
- Treat durable conversation history as a core requirement. Long-term
  personalized memory and user-configurable prompts are future-release scope;
  keep them distinct from basic transcript persistence and define consent,
  retention, and deletion before implementing memory.
- Determine `minSdk`, `compileSdk`, `targetSdk`, and dependency versions from
  verified Android and library requirements when the Gradle project is added.
  Do not infer them from the reference repositories.

## Architecture guidance

Follow the existing repository structure once code exists. Keep clear
responsibilities for audio capture / turn detection, STT, conversation
orchestration, LLM providers, TTS, model selection, and UI. Suggested
interfaces are design guidance, not mandated symbol names.

- Keep Android lifecycle and permission handling at the app boundary.
- Keep audio I/O and model/network work off the main thread; use structured
  concurrency, cancellation, and bounded streaming/backpressure.
- Preserve partial versus final transcripts and associate events with the
  correct conversation turn.
- Handle interruption / barge-in so new user speech can stop ongoing LLM work
  and TTS playback without leaking resources.
- Keep provider-specific request formats and SDKs inside adapters. Keep
  conversation policy and UI independent of a particular LLM vendor.
- Do not put API keys, access tokens, private endpoints, or other secrets in
  source, resources, build files, logs, or generated docs. A mobile binary
  cannot protect a bundled long-lived provider secret.
- Store user-supplied credentials only using an appropriate Android
  Keystore-backed design, with clear replace/remove behavior. Never put
  reusable credentials in QR codes; QR pairing must use a provider-supported,
  short-lived, single-use authorization flow.
- Keep LLM diagnostics useful but private: correlate a turn's provider/model,
  state transitions, token/timing metadata, cancellation, and errors without
  logging secrets, raw audio, or full transcripts by default.

## Models and data

- Keep model metadata and provider selection explicit and typed; validate a
  selected model against its task, runtime, device availability, and license.
- Do not package large model files in the APK by default. If a model must be
  downloaded, use app-private storage, verify its expected identity/integrity,
  report progress and failures, and make deletion/update behavior clear.
- Avoid logging raw audio, full transcripts, prompts, API credentials, or
  unredacted provider responses. Persist conversation data only when the user
  expects it and provide a clear deletion path.
- Document any change that causes audio, transcript, prompt, or model data to
  leave the device.

## Changes and validation

- Read the relevant documentation and existing implementation before changing
  behavior. Reuse established patterns and update directly related docs.
- Add focused unit tests for orchestration, provider selection, parsing,
  cancellation, and failure handling. Use device tests for microphone,
  AICore/ML Kit, TFLite, audio routing, and real TTS behavior where applicable.
- Make the routine speech regression suite replayable and mostly independent
  of live microphones: use labeled recordings and harness-generated TTS speech
  with reproducible noise, reverberation, echo, and distortion variants.
  Retain a smaller consented human-speech and physical-device validation set.
- Validate on Pixel 10 for the primary end-to-end path, and test unavailable
  AICore/model/network cases. Never make performance claims without recording
  device, model, runtime, conditions, and measurement method.
- This is currently a docs-only skeleton: there is no Gradle wrapper or Android
  source yet. Do not claim a build or tests passed until they exist; add the
  verified project-specific commands here when scaffolding is introduced.

## Git workflow

- Create a dedicated branch from `master` for each significant feature, bug fix,
  or project change before implementing it. Use a descriptive prefix such as
  `feat/`, `fix/`, `docs/`, or `chore/`.
- Commit each significant, reviewable change after implementation and its
  applicable validation; do not accumulate multiple unrelated changes into one
  commit. For multi-step work, commit completed milestones separately.
- After implementation and all applicable tests/checks pass, merge the branch
  into the local `master` branch. Documentation-only changes should use the
  relevant documentation checks in place of unavailable app tests.
- Never push branches, commits, or tags to GitHub or any other remote. Leave
  publishing and pushing to the user.

## Reference projects

Use the neighboring DroidKaigi voice app for examples of explicit provider
selection, status reporting, and modular voice stages. Use `speech-android` for
ideas about separating speech capabilities and model management. These are
references, not dependencies or requirements to copy. Prefer current official
Android / ML Kit / AICore / TFLite documentation when implementing APIs.
See [product requirements](./docs/product-requirements.md),
[voice quality and latency](./docs/voice-quality-and-latency.md), and
[Pixel 10 reference notes](./docs/android-device-notes.md) for the voice UX,
test strategy, and hardware caveats.