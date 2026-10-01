# Android Voice Agent

A Kotlin Android voice-agent project using Jetpack Compose, targeting Pixel 10.
The repository contains a buildable Android app that implements the on-device
voice loop (microphone capture → on-device speech-to-text → a selected external
LLM → on-device text-to-speech) with responsive barge-in, durable conversation
storage, capability-aware settings, an optional on-device semantic end-of-turn
detector, and a set of external provider adapters, alongside the design and
decision documentation. Features are implemented in source and covered by a JVM
test suite; Pixel 10 device validation and the speech/latency evaluation (M25)
are still pending, so no performance or recognition-accuracy claim is made here.

## Intended experience

- Capture speech and run STT on-device.
- Send the user-approved transcript and relevant conversation context to a
  selected external LLM API by default.
- Stream the assistant's text and speak it with on-device TTS as the response
  arrives; keep response latency low and let the user interrupt promptly to
  clarify or change the request.
- Plan optional on-device semantic turn completion with Pipecat Smart Turn
  v3.2 after a VAD-detected pause; VAD remains responsible for fast speech
  activity and barge-in onset. Include narrowly scoped ONNX Runtime support
  for this artifact behind a replaceable turn-detector interface; the model
  may be replaced later.
- Show the live dialog, allow text entry as an alternative to speaking, and
  preserve conversations so users can return to them later.
- Keep provider boundaries open for supported AICore / ML Kit GenAI features
  and a curated set of TFLite / LiteRT models.

Planned external LLM providers are OpenAI, OpenRouter, OpenCode Go, OpenCode
Zen, DeepSeek, and Hermes Agent API Server. Connection setup should support
user-supplied API keys and any provider-supported sign-in or pairing flow,
including QR-based flows where offered. These are requirements, not implemented
integrations; do not assume every provider offers every connection method.

Settings are intended to configure on-device STT and TTS, LLM provider and
provider-offered model, and a reasoning/thinking level when that API exposes
one. Longer-term roadmap items include conversation memory and configurable
prompts. Development will emphasize deterministic replay of recorded and
synthetic speech, including noisy and distorted car/street scenarios, alongside
clear local diagnostics for LLM requests and pipeline timing.

The repository contains a single-module Gradle app (`:app`). Its
`com.voicechat.agent.domain` and `com.voicechat.agent.contracts` packages hold
the pure-Kotlin conversation models and the replaceable audio, STT,
VAD/turn-completion, LLM, TTS, model, persistence, and diagnostics interfaces
(M02); the remaining packages implement the pipeline. The app declares the
`RECORD_AUDIO` and `INTERNET` permissions. The external-LLM path is a network
feature and is not fully offline; STT and TTS stay on-device.

## Device and runtime direction

Pixel 10 is the primary development and validation device. Device model
availability, AICore provisioning, API levels, and accelerator behavior must be
checked at runtime and verified against current official documentation.
AICore / ML Kit GenAI support is optional and must not be assumed on every
Android device.

Only add TFLite / LiteRT models after recording their task, origin, license,
runtime compatibility, hardware requirements, and expected storage and memory
cost. STT and TTS remain on-device even when the selected LLM provider is
remote.

## Documentation

- [Agent instructions](./AGENTS.md) — implementation and validation guardrails.
- [Decision record (M00)](./docs/decisions.md) — verified toolchain, speech,
  model-runtime, and provider decisions, the provider capability matrix, and the
  deferred/unsupported list, with sources.
- [Risks and open decisions](./docs/risks-and-decisions.md) — living tracker of
  unresolved risks, open questions, and known limitations across the milestones.
- [Architecture](./docs/architecture.md) — intended pipeline and component
  boundaries.
- [Model and runtime support](./docs/model-runtime.md) — provider and model
  selection requirements.
- [LLM providers and connections](./docs/llm-providers.md) — planned provider
  list, API-key setup, and provider-supported sign-in/pairing options.
- [Product requirements](./docs/product-requirements.md) — interaction,
  conversation-history, settings, and future roadmap requirements.
- [Voice quality and latency](./docs/voice-quality-and-latency.md) — streaming,
  interruption, recognition quality, and measurement strategy.
- [Audio replay harness](./docs/audio-replay-harness.md) — deterministic PCM
  replay, fixture manifest, and synthetic speech-test variants (M03).
- [Turn tracing](./docs/turn-tracing.md) — per-turn trace IDs, stage timing,
  bounded non-blocking export, and default redaction (M04).
- [Conversation persistence](./docs/persistence.md) — Room schema/migrations,
  repository semantics, process-death recovery, and bounded context (M05).
- [Conversation UI](./docs/conversation-ui.md) — Compose screens, state holder,
  manual text path, and the shared text/voice turn seam (M06).
- [Audio capture](./docs/audio-capture.md) — microphone lifecycle, permission,
  route/focus handling, and capture diagnostics (M07).
- [Speech-to-text](./docs/stt.md) — ML Kit GenAI STT adapter, availability
  gating, and the manual Pixel 10 validation run (M08).
- [VAD and endpointing](./docs/vad-endpointing.md) — measured-audio onset,
  pause/resume, and the bounded VAD-only silence cap (M09).
- [Smart Turn](./docs/smart-turn.md) — optional Smart Turn v3.2 semantic
  end-of-turn via a narrowly scoped ONNX Runtime, opt-in and default off (M10).
- [Text-to-speech](./docs/tts.md) — on-device platform TTS, embedded-only voice
  selection, and delivery/interruption accounting (M11).
- [LLM streaming contract](./docs/llm-contract.md) — provider-independent
  request/event/error model and the deterministic fake (M12).
- [Credentials and provider registry](./docs/credentials.md) — Keystore-backed
  credential storage, endpoint/TLS validation, and the provider capability
  registry (M13).
- [Turn orchestration](./docs/orchestration.md) — the pure-Kotlin turn state
  machine, cancellation, and delivered-only persistence (M21).
- [Text-first slice](./docs/text-first-slice.md) — the registry-driven provider
  factory, selection/credential wiring, and disclosure before send (M23).
- [Voice loop](./docs/voice-loop.md) — the voice session coordinator, barge-in,
  delivered-only reconciliation, and interruption recovery (M24).
- [LLM remote transport](./docs/llm-transport.md) — the shared HTTP/JSON/SSE
  transport, error mapping, and fixture replay harness (M14).
- [OpenAI adapter](./docs/openai-adapter.md) — the verified OpenAI endpoint,
  streaming, reasoning, and credential integration (M14).
- [OpenRouter adapter](./docs/openrouter-adapter.md) — constrained routing, no
  silent model fallback, and reasoning round-trip (M15).
- [DeepSeek adapter](./docs/deepseek-adapter.md) — OpenAI-format chat
  completions, thinking control, and chain-of-thought exclusion (M16).
- [OpenCode Go adapter](./docs/opencode-go-adapter.md) — per-model protocol
  dispatch (Chat Completions / Responses / Messages) with no assumed parity (M17).
- [OpenCode Zen adapter](./docs/opencode-zen-adapter.md) — independent Zen
  protocol dispatch with `/systemone` kept out of the chat path (M18).
- [Hermes adapter](./docs/hermes-adapter.md) — configurable server destination,
  TLS/redirect hardening, and server-side-tool disclosure (M19).
- [Settings](./docs/settings.md) — capability-aware selection and DataStore
  persistence (M22).
- [Local model runtimes](./docs/local-models.md) — AICore/ML Kit GenAI discovery,
  the allow-listed (currently empty) LiteRT-LM catalog, app-managed lifecycle, and
  explicit local-versus-remote selection (M20).
- [Logging](./docs/logging.md) — release-safe developer logging and redaction.
- [On-device test plan](./Tests.md) — what to run on the phone and what to
  record, per milestone.
- [Evaluation (M25)](./docs/evaluation.md) — the speech-quality/endpoint/latency
  harness, how to run it, the Smart Turn acceptance criteria, and the (currently
  empty) Pixel 10 result tables.
- [Release checklist (M26)](./docs/release-checklist.md) — first-run setup,
  provider/model configuration, supported devices, and known limitations.
- [Android device notes](./docs/android-device-notes.md) — Pixel 10 findings
  from GVP and Smart Turn v3.2 integration lessons from speech-android to
  validate for this app rather than copy blindly.
- [Privacy and security](./docs/privacy-and-security.md) — audio, transcript,
  API, and credential handling.
- [Validation](./docs/validation.md) — planned test coverage and device checks.
- [Implementation plan](./docs/implementation-plan.md) — sequenced,
  agent-sized milestones with dependencies and acceptance checks.

## Building and testing

Gradle always runs on the Windows host through the wrapper. Set the Android SDK
location first because the project has no `local.properties`:

```powershell
# Windows PowerShell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat :app:assembleDebug        # build the debug APK
.\gradlew.bat :app:testDebugUnitTest    # JVM unit tests
.\gradlew.bat :app:lintDebug            # Android lint
.\gradlew.bat spotlessCheck             # formatting check (Spotless + ktlint)
.\gradlew.bat spotlessApply             # apply formatting
```

CI (`.github/workflows/ci.yml`) runs the same fast checks on push and pull
requests. Toolchain versions are pinned in
[`gradle/libs.versions.toml`](./gradle/libs.versions.toml) and recorded in the
[decision record](./docs/decisions.md); the Gradle wrapper pins Gradle 9.6.0 with
a verified `distributionSha256Sum`.

## Project status

Implemented **in source** and covered by the JVM suite (M01–M24): the buildable
Compose app, the pure-Kotlin domain and contracts, deterministic replay fixtures,
privacy-safe turn tracing, Room conversation persistence with a bounded context
builder, the conversation UI with manual-text and voice paths, microphone
capture, ML Kit GenAI STT with runtime availability gating, measured-audio
VAD/onset with bounded endpointing, optional Smart Turn v3.2 over a narrowly
scoped ONNX Runtime (opt-in, default off), on-device platform TTS (embedded
voices only), the provider-independent LLM streaming contract with a
deterministic fake, Keystore-backed credential storage and the provider
capability registry, the OpenAI/OpenRouter/DeepSeek/OpenCode Go/OpenCode
Zen/Hermes adapters over the shared HTTP/JSON/SSE transport, pure-Kotlin turn
orchestration with cancellation and delivered-only persistence,
capability-aware DataStore settings, the text-first provider slice, and the
voice session coordinator with responsive barge-in. M20 adds eligible on-device
local LLM runtimes with a curated (currently empty) allow-listed catalog and
explicit local-versus-remote selection that never falls back silently.

M25 adds the speech-quality/endpoint/latency evaluation harness
([docs/evaluation.md](./docs/evaluation.md)); its Pixel 10 result rows are empty
and no performance budget is fixed. M26 (release hardening) is in progress: the
voice loop bounds a stalled recognizer, surfaces a typed text-only state when TTS
is unavailable, and honors the persisted STT mode; the transport bounds
in-flight provider calls; and app-scoped dependencies live in an `AppContainer`
owned by the `Application` instead of being rebuilt per activity. M27 closes the
code-review follow-ups: the ViewModel serializes voice-session and generation
state, model-catalog availability is a first-class state with a refresh path, and
the settled/committed turn is appended or updated instead of rewriting the whole
transcript. Tests pass as `:app:testDebugUnitTest`; the exact count is in the
test report, not hand-maintained here.

**Status vocabulary.** "Implemented in source" ≠ "host-tested" ≠ "compiled for
device" ≠ "executed on Pixel 10". The `androidTest` sources compile but the
device matrix in [Tests.md](./Tests.md) is **not run**, and each provider has an
opt-in, credential-gated smoke test that is skipped in routine CI. Do not treat a
feature as device-validated until its Tests.md row records the run.

The `domain`, `contracts`, `log`, `diagnostics`, `eval`, `orchestration`,
`replay`, `settings`, `vad`, and `turn` packages (and the `tts` engine seam,
credential/providers logic, and the `remote` transport core) are pure Kotlin (no
`android.*` imports, enforced by unit tests); `audio`, `stt`, `tts`, `local`,
`turn/OnnxSmartTurnEngine`, `turn/OkHttpSmartTurnModelSource`,
`remote/OkHttpStreamingEngine`, and the AndroidKeyStore/DataStore
implementations hold the platform / ML Kit / network adapters.

The app requires JDK 17 or newer (AGP 9.4's minimum) and sets Java 17 source and
target compatibility; it pins Gradle 9.6.0, Android Gradle Plugin 9.4.0, Kotlin
2.4.20, Compose BOM 2026.09.00, `compileSdk 37`, `targetSdk 36`, and `minSdk 31`.
Coroutines are pinned to 1.9.0, the version already resolved transitively by
AndroidX lifecycle. The reasoning behind each value is in the
[decision record](./docs/decisions.md), and first-run setup, provider/model
configuration, supported devices, and known limitations are in
[release checklist](./docs/release-checklist.md).
