# Decision Record (M00)

This is the M00 deliverable: a compact, evidence-backed record that replaces the
open-ended assumptions in the other documents with chosen (or explicitly
deferred) decisions. Every library, API, provider, and artifact below has a
source and a compatibility basis. Anything not verified is labeled **unknown**
or **deferred** rather than assumed.

- All sources were read on **2026-09-28** (the M00 access date). Provider and
  platform documentation changes over time; re-verify a decision before
  implementing the milestone that depends on it.
- This record **adds no application dependencies**. It only names versions and
  runtimes that M01 and later milestones must pin in the version catalog.
- The section below is the M00 record. Items M00 deferred to M01 (the exact
  Kotlin/KSP/Compose-compiler versions and the formatting plugin) are now
  resolved; see §1 and "Build items resolved at M01".
- No secret, key, token, or private endpoint appears here. Provider endpoints
  listed are the providers' public documented base URLs.

## 1. Toolchain, project identity, and platforms

Chosen to satisfy verified minimums, not copied from the reference projects.

| Decision | Value | Basis (accessed 2026-09-28) |
| --- | --- | --- |
| JDK | 17 | AGP 9.4 minimum and default JDK is 17 (AGP 9.4 release notes; "JDK 17"). A Java 21 toolchain may be used later but is not required. |
| Gradle | 9.6.0 | AGP 9.4 minimum/default Gradle is 9.6.0 (AGP 9.4 release notes). |
| Android Gradle Plugin | 9.4.0 | Current release notes are AGP 9.4.0 (Sept 2026); max supported API level 37; Android Studio Quail 4 (2026.1.4) supports AGP 7.1–9.4. |
| Kotlin | **2.4.20**, pinned via the root buildscript classpath (AGP 9.4 bundles 2.2.10) | Resolved at M01. AGP 9.0+ enables built-in Kotlin by default, and `org.jetbrains.kotlin.android` must not be applied. The AGP 9.4.0 POM depends on KGP 2.2.10; the AGP release notes document raising it with a `buildscript { classpath(...) }` entry, which M01 pins to 2.4.20. KGP 2.4.20 supports Gradle 7.6.3–9.7.0, so Gradle 9.6.0 is in range. Verified with `gradlew buildEnvironment`: `kotlin-gradle-plugin:2.2.10 -> 2.4.20`. |
| Compose compiler | `org.jetbrains.kotlin.plugin.compose` **2.4.20** | Must match the Kotlin compiler version; M01 pins both to 2.4.20. Kotlin 2.0+ uses the Compose Compiler Gradle plugin; the compatibility map says "you don't have to check Compose to Kotlin compatibility" when the plugin is used. |
| compileSdk | 37 (Android 17) | Compose 1.12.0+ **requires** `compileSdk 37` and AGP 9; API 37 min AGP is 9.1.1. |
| targetSdk | 36 (Android 16) | Google Play requires target ≥ 36 for new apps/updates from 31 Aug 2026 (extension to 1 Nov 2026); 37 becomes required 31 Aug 2027. `compileSdk` is independent of `targetSdk`. |
| minSdk | 31 (Android 12) | On-device STT is only broadly available at API 31+: ML Kit GenAI Speech Recognition Basic needs API 31+, and platform `SpeechRecognizer.createOnDeviceSpeechRecognizer` is API 31. The ML Kit library itself needs API 26+, but below 31 there is no on-device STT path, so the app could not do its core job. |
| SDK Build Tools | 36.0.0 | AGP 9.4 minimum/default. |
| NDK | 28.2.13676358 **only if** a module compiles native code | AGP 9.4 default NDK. ONNX Runtime and LiteRT ship prebuilt AARs, so no NDK is needed to consume them. |
| Compose UI toolkit | Jetpack Compose + Material 3 | Required by `AGENTS.md`; no alternate native UI toolkit is evaluated. |
| Compose BOM | 2026.09.00 (Compose 1.12.1) | Compose BOM mapping page; "Always use the latest Compose BOM version: 2026.09.00". |
| activity / lifecycle (Compose) | `androidx.activity:activity-compose:1.13.0`, `androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0` | Compose setup guide example. |
| Persistence | Room 2.8.5 (+ KSP) for conversations; DataStore (Preferences) for settings | Room stable 2.8.5 (2026-09-09); Room minSdk 23; Room recommends KSP. Settings are typed key/value, which DataStore covers. |
| Credential storage | Implemented in M13: AndroidKeyStore AES-256/GCM + app-private ciphertext (see [credentials.md](./credentials.md)); do **not** use `androidx.security:security-crypto` | `EncryptedSharedPreferences`, `MasterKey`, `EncryptedFile` are deprecated in `security-crypto` 1.1.0; the API docs direct callers to AndroidKeyStore via `javax.crypto.KeyGenerator`. M13 adds `AndroidKeystoreCredentialStore` exactly that way. |
| Testing | JUnit4 + Kotlin test for JVM unit tests; `androidx.compose.ui:ui-test-junit4` for UI; Turbine for Flow | Standard, dependency-level choices; pin at M01. |
| Package identity | Provisional `applicationId`/`namespace` `com.voicechat.agent` | No owner domain is established in the repository. This is a convenience identifier, not a claim of ownership; it must be finalized to a controlled domain before any distribution. Cheap to change now, disruptive after publishing. |

### Build items resolved at M01

- **Kotlin / KSP / Compose-compiler versions.** Pinned to Kotlin **2.4.20**,
  Compose compiler plugin **2.4.20** (must match Kotlin), and KSP **2.3.11**.
  Kotlin and the Compose plugin are applied now; KSP is recorded in the version
  catalog but is not applied until M05 adds Room. KSP dropped the
  Kotlin-coupled version scheme from 2.3 onward, and KSP 2.3.x targets Kotlin
  2.4.x (the 2.3.10/2.3.11 release notes fix Kotlin 2.4.0 module-name handling
  and AGP 9 built-in Kotlin R-class resolution). Kotlin is raised above AGP
  9.4's bundled 2.2.10 through the documented buildscript classpath override.
- **Formatting/lint plugin.** Spotless **8.10.3** with ktlint **1.8.0** is
  applied at the root project; `spotlessCheck` covers `*.kt` and `*.gradle.kts`.
  A root `.editorconfig` sets
  `ktlint_function_naming_ignore_when_annotated_with = Composable` so PascalCase
  composables pass ktlint.
- **Build JDK.** M01 was built and verified with Android Studio's bundled JBR
  (JDK 25). AGP 9.4 enforces JDK 17 as a *minimum*, and the app compiles to Java
  17 bytecode (`compileOptions` source/target 17). No JDK 17 is installed on the
  verification machine; CI uses Temurin 17.

### Check commands (verified at M01)

These run today with the checked-in Gradle wrapper (Gradle 9.6.0, pinned with a
verified `distributionSha256Sum`), using the Windows wrapper as required by the
workspace environment:

```
# Set the Android SDK for the build (Windows PowerShell)
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"

.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat spotlessCheck      # formatting check (Spotless + ktlint)
.\gradlew.bat spotlessApply      # apply formatting
```

## 2. On-device speech decisions

### 2.1 Speech-to-text (STT)

**Decision: ML Kit GenAI Speech Recognition is the initial and only STT engine.**
Advanced mode is preferred where available; Basic mode is the fallback on the
same API. No TFLite/LiteRT or ONNX STT model is allow-listed.

Verified contract (`developers.google.com/ml-kit/genai/speech-recognition/android`,
accessed 2026-09-28):

- Artifact `com.google.mlkit:genai-speech-recognition:1.0.0-alpha1`; **alpha**,
  no SLA or deprecation policy, backward-incompatible changes possible.
- Library requires Android API 26+; **Basic** mode requires API 31+; **Advanced**
  requires Pixel 10 / Pixel 11 specifically.
- API: `speechRecognizerOptions { locale; preferredMode }` →
  `SpeechRecognition.getClient(options)`; `checkStatus()`/`checkFeatureStatus()`
  returns `FeatureStatus` states (`AVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`,
  `UNAVAILABLE`, …); model download via `download` with progress; recognition via
  `speechRecognizerRequest { audioSource = AudioSource.fromMic() }` and
  `startRecognition(request)`, which returns a streaming Kotlin `Flow` of partial
  then final results; `stopRecognition()`/`close()` release resources.
- Custom audio (`AudioSource.fromPfd`) must be raw headerless 16-bit PCM, mono,
  16 kHz, delivered at real time.
- **Not supported on devices with an unlocked bootloader.** Common AICore binding
  and preparation failures must be surfaced via `checkStatus()`, not shown as a
  crash.
- Runtime gating is mandatory: never present STT as ready until the feature
  status is `AVAILABLE`.

Fallback/unavailable behavior (required by `AGENTS.md`):

- If `checkStatus()` is `UNAVAILABLE`/fails, show an explicit unavailable reason
  and keep the manual text composer usable; do not substitute a cloud STT.
- If the Advanced model is downloadable, offer the download with progress and
  cancellation; if the user declines, fall back to Basic on the same API rather
  than to a different provider.

Considered and **not adopted initially**:

- Platform `SpeechRecognizer.createOnDeviceSpeechRecognizer` (API 31+). The
  platform recognizes that the default recognizer may stream to remote servers,
  and `isOnDeviceRecognitionAvailable()` must be checked. ML Kit GenAI exposes
  the traditional on-device model through one API and adds the Advanced mode, so
  the project keeps a single STT engine. Recorded as a documented alternative,
  not a second engine.
- TFLite/LiteRT or ONNX STT models — see §3; none verified.

### 2.2 Text-to-speech (TTS)

**Decision: the Android platform `android.speech.tts.TextToSpeech` API is the
initial TTS engine, restricted at runtime to embedded (non-network) voices.**
There is no ML Kit GenAI / AICore TTS API: the GenAI overview lists
Summarization, Proofreading, Rewriting, Image description, Speech recognition,
and Prompt only (accessed 2026-09-28). TTS therefore cannot be an AICore path.

Verified contract (`developer.android.com/reference/android/speech/tts/*`,
accessed 2026-09-28):

- Discover engines with `getEngines()` (API 14) and voices with `getVoices()`
  (API 21); select with `setVoice(Voice)` (API 21).
- Build an on-device-only decision on `Voice.isNetworkConnectionRequired()`
  (API 21): accept a voice only when it returns `false`. The deprecation notes
  for `KEY_FEATURE_EMBEDDED_SYNTHESIS` confirm this is the supported way to
  choose embedded synthesis.
- `synthesizeToFile()` supports producing audio without immediate playback;
  `getMaxSpeechInputLength()` bounds a single utterance.

Fallback/unavailable behavior:

- If no non-network voice is installed for the requested locale, surface an
  explicit "no on-device voice" state and continue text-only. **Do not silently
  use a network voice**, because `AGENTS.md` requires TTS to stay on-device.
- Voice/engine availability is device- and user-configurable; never claim a
  specific voice exists without listing `getVoices()` at runtime.

### 2.3 Voice activity / turn detection

**Deferred to M09 with an explicit constraint.** No VAD model is allow-listed at
M00:

- The project scopes ONNX Runtime narrowly to Smart Turn v3.2, so an ONNX VAD
  (e.g. Silero) is **out of scope** unless that scope is deliberately changed.
- No TFLite/LiteRT VAD artifact is verified (§3), so M09 must not assume one.
- M09 must therefore select and measure a VAD/onset path from the audio itself
  and keep the bounded maximum-silence endpoint working without Smart Turn, as
  the architecture already requires. Barge-in onset stays on the fast acoustic
  path, never on Smart Turn.

## 3. Model runtime and model allow-list

### 3.1 Runtime allow-list

| Runtime | Decision | Basis (accessed 2026-09-28) |
| --- | --- | --- |
| LiteRT (formerly TFLite) | Allow-listed as the app-managed `.tflite` runtime; no model uses it yet | LiteRT v2.2.0 (2026-08-14), min SDK 23, min NDK r26a; Maven `com.google.ai.edge.litert:litert`; code samples Apache-2.0, docs CC-BY-4.0. |
| LiteRT-LM | Allow-listed only for the optional local-LLM path (M20) | `com.google.ai.edge.litertlm:litertlm-android`; current docs describe v0.16.0; `.litertlm` bundles sourced from the Google AI Edge Hugging Face community. |
| ONNX Runtime for Android | Allow-listed **only** for the pinned Smart Turn v3.2 artifact | `com.microsoft.onnxruntime:onnxruntime-android` 1.29.0 (2026-08-13), MIT license; Android tested at API 28, "may be compatible with API level 21+"; NNAPI EP requires Android 8.1+; add `-keep class ai.onnxruntime.** { *; }` for R8. |
| AICore / ML Kit GenAI | Used only through the ML Kit GenAI Speech Recognition API | AICore models are system-managed; never download, copy, inspect, or delete them (ML Kit GenAI docs). |

### 3.2 Allow-listed speech-task models

**None yet.** No STT, TTS, or VAD `.tflite`/LiteRT artifact with verified
provenance, license, runtime contract, and device fit is allow-listed for M00.
This is a deliberate, evidence-backed emptiness, not an omission:

- There is no first-party Google on-device STT or TTS model published for LiteRT
  that the documentation presents as such. LiteRT-LM supports multimodal **input**
  for compatible LLMs but is not a transcription or synthesis API.
- The LiteRT task library and Model Maker mention an `AudioClassifier` and a
  "speech recognition" model-maker path; these are not established as general
  ASR and are **not** allow-listed. Treat as an evaluation candidate only.

Evaluation backlog (provisional; re-verify identity, license, and contract before
any allow-list entry). Each entry needs a checksum and a stated runtime contract
before adoption:

- A streaming multilingual ASR bundle in LiteRT format referenced by the
  neighboring `speech-android` SDK (`ModelManager.kt` points at a LiteRT
  encoder/decoder/joint bundle for its Nemotron-3.5 recognizer). Identity,
  license, and size must be verified from the publisher before use.
- A TTS model in LiteRT format. The same reference project ships ONNX TTS
  (Kokoro) and a LiteRT TTS ("Supertonic") path, but none of those artifacts are
  verified for this app, and adopting ONNX beyond Smart Turn would require an
  explicit scope change.

Rule carried into M20: an artifact may be executed only after its task, source,
license, format/runtime, operators/delegates, ABI, tensor contract, and
integrity are recorded and it passes device tests. Arbitrary model URLs/files
remain rejected.

### 3.3 Smart Turn v3.2 artifact (verified separately)

There are **two distinct upstreams**; the repository's earlier notes referred to
the second one. Both must not be conflated.

| Property | Pipecat upstream | Soniqo re-export (referenced by speech-android) |
| --- | --- | --- |
| Repository | `huggingface.co/pipecat-ai/smart-turn-v3` | `huggingface.co/soniqo/Smart-Turn-v3.2-ONNX` |
| Revision | `f766f81d3cfdf7737ac64aad813d91bbfd56bf93` ("Smart Turn v3.2") | `b48fdbe20772bcec1fef02f4a1a355236ef6359e` (published 2026-09-02) |
| License | BSD-2-Clause (model card) | BSD-2-Clause (card + `LICENSE`, 1,406 bytes); re-export of `pipecat-ai/smart-turn-v3` |
| Files | `smart-turn-v3.2-cpu.onnx` (8.68 MB, mel input), `smart-turn-v3.2-gpu.onnx` (32.4 MB) | `smart-turn-v3.2.onnx` (33,035,324 B), `smart-turn-v3.2-int8.onnx` (11,123,370 B), `config.json` (1,079 B), `LICENSE` (1,406 B) |
| Front-end | **External**: caller computes Whisper log-mel features | **Embedded in the graph** (log-mel + zero-mean/unit-variance normalization) |
| Input | Mel features (see upstream code) | `audio` float32 `[1, 128000]`; 16 kHz mono; most recent audio last, zeros at the front |
| Output | Endpoint probability | `probability` float32 `[1, 1]`; turn complete if > 0.5 |
| Opset | Not stated in the upstream model card read | 18 (per re-export `config.json`) |

**Decision: pin `smart-turn-v3.2-int8.onnx` from
`soniqo/Smart-Turn-v3.2-ONNX` at revision
`b48fdbe20772bcec1fef02f4a1a355236ef6359e`, expecting size 11,123,370 bytes and
SHA-256 `00cd131551e8d1e9011f31345edb632a26116dd83e159782c4ddff610718ea31`.**

Rationale and caveats:

- The embedded front-end means the Android app feeds raw 16 kHz PCM and reads one
  probability, instead of implementing Whisper log-mel feature extraction in
  Kotlin. That materially reduces code and a source of contract drift.
- The exact byte count and SHA-256 above come from the Hugging Face model API
  (`/api/models/soniqo/Smart-Turn-v3.2-ONNX?blobs=true`); they confirm the
  `11,123,370`-byte figure already recorded in
  [model-runtime](./model-runtime.md) and
  [device notes](./android-device-notes.md).
- It is a **third-party re-export** (publisher `soniqo`/`aufklarer`), not the
  upstream Pipecat publisher. If the upstream artifact with an embedded front-end
  becomes available, prefer it. The project's contract is the turn-detector
  interface, so swapping the artifact must not change orchestration.
- The re-export README reports ~93% accuracy / promising latency on Apple
  Silicon; that is **not** a Pixel 10 measurement and must not be quoted as this
  app's performance. M10 must measure load time, inference time, memory, false
  commits, and false holds on device.
- Acquisition strategy: app-private download with integrity check (size +
  SHA-256), atomic install, cancellation, and removal. It must not be bundled in
  the APK. If the model is missing/corrupt, M09's VAD-only bounded endpoint
  policy applies and the UI reports semantic detection as inactive.
- Keep Smart Turn **opt-in** (default off) until M25 evidence supports otherwise.

## 4. Provider capability matrix

Legend: **API key** = user-supplied bearer token; **PKCE** = documented
browser OAuth authorization-code + PKCE flow; **unknown** = not stated in the
provider's official docs read at the access date.

| Provider | Base URL / endpoints | Auth documented | Model discovery | Streaming | Reasoning control |
| --- | --- | --- | --- | --- | --- |
| **OpenAI** | `https://api.openai.com/v1` → `/responses`, `/chat/completions`, `/models` | API key (`Authorization: Bearer`) | `GET /v1/models`; docs model catalog | SSE with `stream: true`; Responses uses typed events (`response.output_text.delta`, `response.completed`, `error`) | `reasoning.effort` = `none`/`low`/`medium`/`high`/`xhigh` (some models add `max`); GPT-5.6 also `reasoning.mode` = `standard`\|`pro` |
| **OpenRouter** | `https://openrouter.ai/api/v1` → `/chat/completions`, `/models`, `/generation`; auth at `https://openrouter.ai/auth` | API key; **PKCE** (`/auth` → `POST /api/v1/auth/keys` mints a user-controlled key) | `GET /api/v1/models` | SSE with `stream: true`; ignore comment payloads; usage in final chunk | `reasoning` map; `reasoning_effort` = `max`/`xhigh`/`high`/`medium`/`low`/`minimal`/`none`; per-model `reasoning.supported_efforts` is returned by `/models`; `include_reasoning` deprecated |
| **OpenCode Go** | `https://opencode.ai/zen/go/v1/responses`, `/chat/completions`, `/messages`; models at `…/zen/go/v1/models` | API key (subscription; sign in to Console, copy key) | `GET …/zen/go/v1/models` | Not spelled out for Go; treat as the family's behavior and verify at M17 | unknown |
| **OpenCode Zen** (Console pay-as-you-go gateway) | `https://opencode.ai/zen/v1/responses`, `/chat/completions`, `/messages`, `/models/<model>`, `/systemone`; models at `…/zen/v1/models` | API key (`Authorization: Bearer`) | `GET …/zen/v1/models` | Not spelled out; verify at M18 | unknown |
| **DeepSeek** | OpenAI: `https://api.deepseek.com` → `/chat/completions`, `/responses`, `/models`; Anthropic: `https://api.deepseek.com/anthropic` | API key (`Authorization: Bearer`) | `GET /models` (returns context window, max output, modalities, effort levels, per-protocol capabilities) | `stream: true` (Chat Completions and Responses) | `thinking: {type: enabled}` plus `reasoning_effort`; per-model `effort.supported_levels` from `/models` |
| **Hermes Agent API Server** | User/admin-configured host (default bind `127.0.0.1:8642`) → `/v1/chat/completions`, `/v1/responses`, `/v1/models`, `/v1/capabilities`, `/health` | Bearer `API_SERVER_KEY` (capabilities endpoint reports bearer auth required) | `GET /v1/models` (virtual `hermes-agent` alias) + `GET /v1/capabilities` | Documented streaming with inline tool progress; exact SSE shape unknown | unknown (agent-side routing, not an OpenAI effort surface) |

### Provider notes and decisions

- **Treat the three OpenCode-family integrations as distinct.** Endpoints differ
  (`/zen/go/v1`, `/zen/v1`, and the Console inference paths
  `/inference/{openai,anthropic,google}/…`). Go and Zen each mix three API
  families per model, so a single client must dispatch by the model's family, not
  by provider. `AGENTS.md`'s "do not assume OpenAI-compatible parity" is
  satisfied by this per-model family map. The Console inference API further
  states that paid models require the bearer key while free chat models can be
  called without one; treat that as specific to those endpoints and verify per
  surface.
- **OpenCode Zen's `/systemone`** (Jev) is a structured decision model, not a
  chat completion; it must never be surfaced as a conversational model.
- **QR pairing: not supported by any provider.** No provider's official docs at
  the access date document a QR pairing flow. Per `AGENTS.md`, the app must not
  invent one or treat an arbitrary QR URL as a trusted endpoint. Re-check each
  provider before M22; keep the capability false until documented.
- **Browser/device authorization** is documented only for OpenRouter (PKCE).
  Other providers are API-key only in their official docs. Do not show a
  sign-in option that is not documented.
- **OpenRouter silent fallback is a hazard.** OpenRouter may fall back to other
  providers/GPUs on 5xx or rate-limit, and `route: 'fallback'` / `models[]` add
  more routing. Because `AGENTS.md` forbids silently switching providers/models,
  the OpenRouter adapter must constrain provider routing and record the model
  actually used (`usage`/`model`), so the trace shows the true provider.
- **OpenAI stores Responses by default.** Set `store: false` so conversation
  content is not retained by default, consistent with the privacy requirements.
- **Hermes executes tools on the server host.** It is an agent runtime, not a
  pure proxy; a remote Hermes server means `pwd`, file, browser, and MCP tools run
  remotely. The destination must be disclosed, TLS required for non-local hosts,
  and no public endpoint is hard-coded.
- **OpenCode Go is aimed at coding-agent traffic** and recommends a client user
  agent plus a stable `x-opencode-session` header. Whether a voice agent's traffic
  fits Go's terms is a product/legal question to confirm before M17/M23; the
  repository should not assume it does.
- **M13 encodes this matrix** as the typed `ProviderCapabilityRegistry`
  (`providers/`), with auth kept separate from transport and model access; see
  [credentials.md](./credentials.md#provider-capability-registry). Re-verify a
  row before the adapter that consumes it ships (R-0072).

## 5. Deferred and explicitly unsupported items

| Item | Status | Reason |
| --- | --- | --- |
| TFLite/LiteRT STT model | Deferred | No artifact with verified provenance, license, and runtime contract. |
| TFLite/LiteRT TTS model | Deferred | Same; no first-party LiteRT speech-synthesis model identified. |
| VAD model (ONNX or TFLite) | Deferred to M09 | ONNX scope is Smart Turn only; no TFLite VAD allow-listed. M09 must work from measured audio. |
| On-device LLM (AICore Prompt API, LiteRT-LM) | Deferred to M20 | Optional; needs runtime availability discovery and its own model-license record. |
| ML Kit GenAI / AICore TTS | Unsupported | No such API exists in the GenAI API list. |
| QR pairing for any provider | Unsupported (not documented) | No provider documents a QR flow at the access date; `AGENTS.md` forbids inventing one. |
| OAuth/device authorization beyond OpenRouter PKCE | Unsupported (not documented) | Only OpenRouter documents a browser PKCE flow in its official docs. |
| Platform `SpeechRecognizer` as a second STT engine | Not adopted | Keeps a single on-device STT engine; the wrapper API can stream to remote servers unless the on-device factory is forced. |
| Bundling models in the APK | Unsupported as a default | `AGENTS.md` and model-lifecycle rules; Smart Turn is app-private download only. |
| Silent provider/model fallback | Unsupported | `AGENTS.md`; fallback must be explicit and visible. |
| Network-required TTS voices | Unsupported | Would break the on-device TTS requirement. |
| General ONNX Runtime support | Unsupported | Scope is the pinned Smart Turn artifact only. |
| "Fully offline" claim | Unsupported | The default LLM path is remote. |
| Long-term memory, configurable prompts | Future release | Out of scope per product requirements. |
| minSdk below 31 | Not chosen | No on-device STT path below API 31. |

## 6. Unknowns carried forward

Labeled, not resolved. Each is owned by a later milestone.

- *Resolved at M01:* Kotlin 2.4.20 / KSP 2.3.11 / Compose compiler 2.4.20 under
  AGP 9 built-in Kotlin, plus Spotless/ktlint (see §1).
- OpenCode Go and OpenCode Zen streaming event shapes and reasoning controls
  (owners: M17, M18).
- Hermes Agent API Server streaming event shape and auth/session semantics
  (owner: M19).
- Smart Turn inference cost and endpoint accuracy on Pixel 10 (owner: M10/M25).
- Which TFLite/LiteRT speech artifacts, if any, meet the allow-list bar
  (owner: M20).
- Whether OpenCode Go's terms accommodate a non-coding voice client
  (owner: M17/M23, product).

## 7. Sources (all accessed 2026-09-28)

Android platform and build:

- Android 16 SDK setup and Play target API requirement —
  https://developer.android.com/about/versions/16/setup-sdk ,
  https://developer.android.com/google/play/requirements/target-sdk
- Android Gradle plugin 9.4 release notes and About AGP (Gradle/JDK/API-level
  minimums) — https://developer.android.com/build/releases/gradle-plugin ,
  https://developer.android.com/build/releases/about-agp
- Kotlin releases (2.4.20) and Gradle compatibility —
  https://kotlinlang.org/docs/whatsnew2420.html ,
  https://kotlinlang.org/docs/gradle-configure-project.html
- Compose compiler plugin, Compose BOM mapping, Compose↔Kotlin map —
  https://developer.android.com/develop/ui/compose/compiler ,
  https://developer.android.com/develop/ui/compose/bom/bom-mapping ,
  https://developer.android.com/jetpack/androidx/releases/compose-kotlin
- Room release notes — https://developer.android.com/jetpack/androidx/releases/room
- `androidx.security.crypto` deprecations —
  https://developer.android.com/reference/androidx/security/crypto/package-summary

On-device speech and runtimes:

- ML Kit GenAI overview and Speech Recognition API —
  https://developers.google.com/ml-kit/genai ,
  https://developers.google.com/ml-kit/genai/speech-recognition/android
- Gemini Nano / AICore — https://developer.android.com/ai/gemini-nano
- Android `SpeechRecognizer` and `RecognizerIntent` —
  https://developer.android.com/reference/android/speech/SpeechRecognizer ,
  https://developer.android.com/reference/android/speech/RecognizerIntent
- Android `TextToSpeech`, `Voice`, `TextToSpeech.Engine` —
  https://developer.android.com/reference/android/speech/tts/TextToSpeech ,
  https://developer.android.com/reference/android/speech/tts/Voice ,
  https://developer.android.com/reference/android/speech/tts/TextToSpeech.Engine
- LiteRT for Android (versions, min SDK) —
  https://ai.google.dev/edge/litert/android
- LiteRT-LM Android guide and overview —
  https://developers.google.com/edge/litert-lm/android ,
  https://developers.google.com/edge/litert-lm/overview
- ONNX Runtime Android build, NNAPI EP, compatibility, Maven —
  https://onnxruntime.ai/docs/build/android.html ,
  https://onnxruntime.ai/docs/reference/execution-providers/NNAPI-ExecutionProvider.html ,
  https://onnxruntime.ai/docs/reference/compatibility ,
  https://mvnrepository.com/artifact/com.microsoft.onnxruntime/onnxruntime-android

Smart Turn:

- Upstream model — https://huggingface.co/pipecat-ai/smart-turn-v3 ,
  https://github.com/pipecat-ai/smart-turn
- Pinned re-export, I/O contract, sizes, SHA-256 —
  https://huggingface.co/soniqo/Smart-Turn-v3.2-ONNX ,
  https://huggingface.co/api/models/soniqo/Smart-Turn-v3.2-ONNX?blobs=true
- Reference integration (pins the artifact revision) —
  https://github.com/soniqo/speech-android/blob/main/sdk/src/main/kotlin/audio/soniqo/speech/ModelManager.kt

Providers:

- OpenAI API reference, models, reasoning, streaming —
  https://platform.openai.com/docs/api-reference ,
  https://platform.openai.com/docs/models ,
  https://developers.openai.com/api/docs/guides/reasoning ,
  https://developers.openai.com/api/docs/guides/streaming-responses
- OpenRouter API overview, parameters, auth, OAuth PKCE —
  https://openrouter.ai/docs/api-reference/overview ,
  https://openrouter.ai/docs/api-reference/parameters ,
  https://openrouter.ai/docs/api/reference/authentication ,
  https://openrouter.ai/docs/use-cases/oauth-pkce
- OpenCode Go —
  https://opencode.ai/v2/docs/console/go/
- OpenCode Zen / Console models and inference —
  https://opencode.ai/v2/docs/console/models/ ,
  https://opencode.ai/v2/docs/console/inference/
- DeepSeek quick start and model list —
  https://api-docs.deepseek.com/ ,
  https://api-docs.deepseek.com/api/list-models
- Hermes Agent API Server —
  https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server
