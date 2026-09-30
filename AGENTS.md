# Agent Instructions

## Project intent

This repository is the documentation-first skeleton for a Kotlin Android voice
agent, initially targeting Pixel 10. The intended voice path is on-device
speech-to-text (STT) and text-to-speech (TTS), with the primary language-model
(LLM) path provided by an external API. Keep optional on-device inference
possible through supported AICore / ML Kit GenAI APIs and a deliberately
curated set of TensorFlow Lite (TFLite / LiteRT) models.

The app is scaffolded and its core domain types and replaceable contracts exist
(M02), with deterministic audio-replay fixtures (M03), privacy-safe turn
tracing (M04), durable Room-backed conversation persistence with a bounded
context builder (M05), a Compose conversation UI with a manual text path (M06),
microphone capture (M07), on-device ML Kit GenAI speech-to-text (M08),
measured-audio VAD/onset and bounded endpointing (M09), on-device platform TTS
(M11), a provider-independent LLM streaming contract with a deterministic fake
(M12), Keystore-backed credential storage with a provider capability registry
(M13), pure-Kotlin turn orchestration with cancellation (M21), the OpenAI (M14),
OpenRouter (M15), DeepSeek (M16), OpenCode Go (M17), OpenCode Zen (M18), and
Hermes (M19) adapters over a shared remote HTTP/JSON/SSE transport,
capability-aware settings with DataStore persistence (M22), a text-first
end-to-end provider slice that drives the selected adapter through orchestration
(M23), a voice-loop coordinator with responsive barge-in (M24), and optional
Smart Turn v3.2 semantic end-of-turn detection through ONNX Runtime (M10,
opt-in and default off), plus eligible on-device local LLM runtimes with a
curated (currently empty) model catalog and explicit local-versus-remote
selection (M20). Speech/latency evaluation
(M25) and release hardening (M26) are still not implemented. Treat the product
documents as
requirements and direction, not as proof that a feature, dependency, model, or
device capability already exists. Update this file when the project structure
and verified commands change. Unresolved risks, open decisions, and known
limitations are tracked in [docs/risks-and-decisions.md](./docs/risks-and-decisions.md).

## Product and platform constraints

- Use Kotlin for application and Android integration code. Do not introduce
  Java or C++ unless an unavoidable interop boundary is documented.
- Use Jetpack Compose for the native application UI. Use Android Views only
  for a specific interop need that Compose cannot reasonably cover.
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
- `minSdk`, `compileSdk`, `targetSdk`, and dependency versions are pinned in
  `gradle/libs.versions.toml` and justified in
  [docs/decisions.md](./docs/decisions.md). Re-verify against current official
  requirements before changing them; do not copy versions from the reference
  repositories.

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
  Note that the real on-device STT engine (ML Kit GenAI Speech Recognition)
  requires audio at a real-time rate (about 32 KB/s) and does not accept
  full-speed file-backed input, so replay drives the contract/fakes but on-device
  STT validation must use live capture or an explicitly paced feeder.
- Validate on Pixel 10 for the primary end-to-end path, and test unavailable
  AICore/model/network cases. Never make performance claims without recording
  device, model, runtime, conditions, and measurement method.
- Never claim a build, test, or device run passed unless you ran it. Report the
  exact command and its real outcome, including blockers.
- Record each new unresolved risk, open decision, or known limitation in
  [docs/risks-and-decisions.md](./docs/risks-and-decisions.md) with the next free
  `R-####` id, and mark items `resolved (Mxx)` rather than deleting them.

## Project structure and commands

Single Gradle module, no speculative feature modules:

```
build.gradle.kts            root build; pins KGP above AGP 9's bundled version
settings.gradle.kts         repository and module configuration
gradle.properties           build properties (JVM args, caching, AndroidX)
gradle/libs.versions.toml   version catalog (toolchain, AndroidX, tooling)
gradlew / gradlew.bat       Gradle wrapper (Gradle 9.6.0, checksum-verified)
app/                        the only application module
  lint.xml                  lint configuration
  src/main/kotlin/com/voicechat/agent/
    domain/                 pure-Kotlin conversation/domain models (M02)
    contracts/              replaceable platform/provider interfaces (M02)
    audio/                  microphone capture + lifecycle (M07)
    credentials/            Keystore-backed credential storage (M13)
    diagnostics/            privacy-safe turn tracing and timing (M04)
    log/                    release-safe developer logging (off in release)
    local/                  eligible on-device LLM runtimes + curated catalog (M20)
    orchestration/          turn state machine + orchestrator (M21)
    persistence/            Room conversation storage (M05)
    providers/              capability registry (M13) + OpenAI/OpenRouter/DeepSeek/OpenCode Go/Zen/Hermes adapters (M14-M19)
    remote/                 shared remote HTTP/JSON/SSE transport (M14)
    replay/                 deterministic PCM replay + fixtures (M03)
    settings/               settings model + DataStore persistence (M22)
    stt/                    ML Kit GenAI speech-to-text adapter (M08)
    tts/                    on-device platform TTS adapter (M11)
    turn/                   Smart Turn v3.2 ONNX detector + model lifecycle (M10)
    ui/                     Compose conversation + settings UI (M06/M22)
    vad/                    measured-audio VAD/onset + endpointing (M09)
    voice/                  voice session coordinator + barge-in (M24)
  schemas/                  exported Room schema JSON (M05)
  src/main/res/                              strings, theme, launcher icon, rules
  src/test/kotlin/com/voicechat/agent/
    domain/, contracts/     JVM domain and contract tests
    audio/, stt/, tts/      JVM capture, STT, and TTS adapter tests
    credentials/, providers/  JVM credential, registry, and adapter tests
    diagnostics/, log/      JVM tracing and logging tests
    local/                  JVM runtime discovery, catalog, lifecycle, and local-adapter tests
    orchestration/          JVM turn state-machine and orchestrator tests
    persistence/, settings/ JVM persistence (Robolectric) and settings tests
    replay/, remote/        JVM replay and remote-transport tests
    security/, ui/          JVM secret scan and Compose UI tests
    turn/                   JVM Smart Turn config/adapter/lifecycle/endpoint tests
    vad/, voice/            JVM VAD and voice-session tests
    fake/                   deterministic contract fakes
  src/test/resources/replay/                 frozen replay fixture bytes
  src/test/resources/llm/                    recorded provider SSE fixtures
  src/androidTest/                           on-device tests (see Tests.md)
  src/debug/                                 debug-only Application + credential import
  src/testDebug/                             debug-variant JVM tests
.github/workflows/ci.yml    CI running the same fast checks
scripts/                    fetch/push helpers: pinned models + debug credentials
models/                     downloaded model artifacts (gitignored; README/manifest tracked)
secrets/                    local secrets for debug runs (gitignored; README tracked)
test_data/                  speech sound files usable to simulate STT
                            input in tests
```

`test_data/` holds sound files that contain speech: local recordings (`.m4a`)
and the downloaded `smart-turn-v3.2-eng/` set (original `.flac` plus decoded
16 kHz mono PCM `.wav`, with `manifest.csv` provenance). They are local test
input for simulating STT capture in tests, not app assets; do not package them
in the APK. Check the license/consent terms before relying on any recording.

`models/` holds downloaded, pinned model artifacts (currently only the Smart Turn
v3.2 ONNX file). They are **not committed**; fetch them with
`scripts/fetch-models.sh` and install them into app-private storage with
`scripts/push-models.sh` (see `models/README.md`). `secrets/` holds developer
credentials for device debugging and is gitignored;
`scripts/push-credentials.sh` installs one into app-private storage, where a
debug-only Application imports it into the AndroidKeyStore-backed store (see
`docs/credentials.md`). Never commit a model file or a secret.

Gradle runs on the Windows host through the wrapper, never inside WSL. The
project has no `local.properties`, so set the SDK path before each invocation:

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat :app:assembleDebug        # build the debug APK
.\gradlew.bat :app:testDebugUnitTest    # JVM unit tests
.\gradlew.bat :app:lintDebug            # Android lint
.\gradlew.bat spotlessCheck             # formatting check (Spotless + ktlint)
.\gradlew.bat spotlessApply             # apply formatting
```

A clean checkout currently does not need the Android SDK license prompt; install
`platforms;android-37.0` and `build-tools;36.0.0` first if a machine is missing
them. Device-level checks (microphone, AICore/ML Kit, TFLite/LiteRT, real TTS,
audio routing) have no automation yet and must be reported as manual runs.

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