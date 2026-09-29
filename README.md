# Android Voice Agent

A Kotlin Android voice-agent project using Jetpack Compose, targeting Pixel 10.
The repository contains a buildable Android scaffold, a pure-Kotlin domain and
contract layer for the voice pipeline, and the design and decision documentation.
The voice, model, and provider features are not implemented yet.

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

The repository contains a single-module Gradle app (`:app`) that builds and runs
a minimal Jetpack Compose screen. Its `com.voicechat.agent.domain` and
`com.voicechat.agent.contracts` packages hold the pure-Kotlin conversation
models and the replaceable audio, STT, VAD/turn-completion, LLM, TTS, model,
persistence, and diagnostics interfaces (M02). It declares no permissions and
contains no microphone capture, STT/TTS, model, provider, or persistence
implementation; those arrive in later milestones. The normal external-LLM path
will need network access and is not fully offline.

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

The repository builds and runs a minimal Android app (milestone M01) and exposes
the core domain types and replaceable contracts for the voice pipeline (milestone
M02). `:app` is the only Gradle module. The Compose screen remains a placeholder;
the `domain` and `contracts` packages are pure Kotlin (no `android.*` imports,
enforced by a unit test), and the JVM test suite covers domain invariants,
contract event ordering, cancellation, and the deterministic fakes under
`app/src/test/kotlin/com/voicechat/agent/fake`. There is still no microphone,
STT/TTS, model, provider, or persistence implementation, and the app declares no
permissions.

The scaffold requires JDK 17 or newer (AGP 9.4's minimum) and sets Java 17
source/target compatibility; it pins Gradle 9.6.0, Android Gradle Plugin 9.4.0,
Kotlin 2.4.20, Compose BOM 2026.09.00, `compileSdk 37`, `targetSdk 36`, and
`minSdk 31`. Coroutines are pinned to 1.9.0, the version already resolved
transitively by AndroidX lifecycle. The reasoning behind each value is in the
[decision record](./docs/decisions.md).

The M00 [decision record](./docs/decisions.md) also records the on-device speech
choices, the initial model-runtime allow-list, and each provider's
endpoint/auth/streaming/reasoning capabilities, with the deferred and unsupported
items called out. Model catalog entries, provider implementations, and speech
integration are added by later milestones and are not implemented yet.
