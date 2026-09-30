# Risks and Open Decisions

This is a **living** tracking file for everything still unresolved across the
milestones: risks, undecided questions, deferred work, and deliberate
limitations. It is not a decision record. Resolved choices live in
[decisions.md](./decisions.md) (the M00 record) or in the milestone document
that made them; this file records what is still not settled and who is expected
to settle it.

It currently covers M00-M21. Later milestone agents **append** items here as
they find them and **close** items they resolve.

## Convention

Every item has a stable `R-####` ID, an area, and a status:

- `open` — unresolved; needs a decision, measurement, or implementation.
- `accepted` — a deliberate, tracked limitation or unsupported item that stays
  unless an explicit scope change says otherwise.
- `resolved (Mxx)` — the named milestone supplied the evidence; keep the row and
  write the evidence in place of the open question.

To add an item, take the next free ID in the right area section. To close one,
do not delete the row; change its status to `resolved (Mxx)` and record the
one-line evidence. Item fields are: ID, status, area, item/open question,
source, expected milestone, and resolution criteria/evidence. If a field is
genuinely unknown, write "unknown" rather than guessing.

## Contents

- [Speech: on-device STT, TTS, VAD, and turn completion](#speech-on-device-stt-tts-vad-and-turn-completion) — R-0001-R-0011
- [Language models, providers, and credentials](#language-models-providers-and-credentials) — R-0012-R-0024
- [Persistence](#persistence) — R-0025-R-0027
- [Tracing and diagnostics](#tracing-and-diagnostics) — R-0028-R-0031
- [UI and turn orchestration](#ui-and-turn-orchestration) — R-0032-R-0034
- [Replay and test corpus](#replay-and-test-corpus) — R-0035-R-0037
- [Build, toolchain, and project identity](#build-toolchain-and-project-identity) — R-0038-R-0042
- [Device validation and performance](#device-validation-and-performance) — R-0043-R-0047
- [Privacy and security](#privacy-and-security) — R-0048-R-0049
- [Capture and STT follow-ups (M07-M08)](#capture-and-stt-follow-ups-m07-m08) — R-0050-R-0056
- [TTS follow-ups (M11)](#tts-follow-ups-m11) — R-0057-R-0060
- [VAD, onset, and endpointing (M09)](#vad-onset-and-endpointing-m09) — R-0061-R-0064
- [LLM contract follow-ups (M12)](#llm-contract-follow-ups-m12) — R-0065-R-0069
- [Credentials and provider registry (M13)](#credentials-and-provider-registry-m13) — R-0070-R-0075
- [Turn orchestration follow-ups (M21)](#turn-orchestration-follow-ups-m21) — R-0080-R-0083
- [OpenAI adapter and shared transport (M14)](#openai-adapter-and-shared-transport-m14) — R-0090-R-0099

## Speech: on-device STT, TTS, VAD, and turn completion

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0001 | open | speech | The only STT engine, ML Kit GenAI Speech Recognition, is pinned at `1.0.0-alpha1`: alpha, no SLA or deprecation policy, backward-incompatible changes possible. | decisions.md §2.1 | M08 | Runtime `checkStatus()` gating; re-verify artifact and docs before M08; never present STT as ready unless `AVAILABLE`. |
| R-0002 | open | speech | Advanced STT mode requires Pixel 10/11 specifically; Basic needs API 31+. Availability is device/config dependent and not yet tested here. | decisions.md §2.1 | M08 | Pixel 10 run recording engine/mode and proof of on-device processing; explicit unavailable path if status is not `AVAILABLE`. |
| R-0003 | open | speech | STT is not supported on devices with an unlocked bootloader; AICore binding/preparation can fail. | decisions.md §2.1 | unspecified | Failures surfaced through `checkStatus()` as an explicit unavailable reason, not a crash. |
| R-0004 | resolved (M11) | speech | No on-device TTS voice is guaranteed; voices are device- and user-configurable, so a network voice must never be used silently. | decisions.md §2.2 | M11 | Resolved: `AndroidTtsEngine.installedVoices()` reads `getVoices()` with `isNetworkConnectionRequired()`, and `OnDeviceVoiceSelector` keeps only embedded voices and returns `null` otherwise, surfaced as the explicit `NoOnDeviceVoice`/`TTS_NO_ON_DEVICE_VOICE` state. Unit tests (`TtsVoiceSelectionTest`, `EngineTextToSpeechTest`) prove network voices are excluded and never spoken. On-device enumeration is still unrun (see R-0057). |
| R-0005 | resolved (M09) | speech | No VAD/onset path or model was selected; ONNX scope is Smart Turn only, so an ONNX VAD (for example Silero) is out of scope; no TFLite VAD is allow-listed. | decisions.md §2.3 | — | M09 implements a measured-audio VAD (normalized frame RMS plus zero-crossing rate) behind `VoiceActivityDetector` and a validated, route-aware bounded maximum-silence endpoint behind `BoundedTurnEndpointPolicy`. No model, runtime, dependency, or permission was added, and an ONNX VAD remains out of scope (see docs/vad-endpointing.md). |
| R-0006 | open | speech | Smart Turn v3.2 accuracy/latency is unmeasured on Pixel 10 and the feature is opt-in (default off) until M25 evidence. | decisions.md §3.3 | M10 (integration), M25 (default decision) | On-device load time, inference time, memory, false-commit/false-hold tradeoffs meeting agreed acceptance; stays default-off until then. |
| R-0007 | open | speech | TFLite/LiteRT STT and TTS models are deferred; the evaluation backlog entries are unverified for identity, license, and contract. | decisions.md §3.2, §5 | M20 | Recorded task, source, license, runtime, operators/delegates, ABI, tensor contract, and integrity plus device tests before allow-list. |
| R-0008 | open | speech | The pinned Smart Turn artifact is a third-party re-export (not the Pipecat publisher) and its download/install lifecycle is not implemented. | decisions.md §3.3, §5 | M10 | Re-verify revision, size, and SHA-256 at integration; app-private download with integrity check, atomic install, cancellation, and removal; prefer an upstream embedded-front-end artifact if it appears. |
| R-0009 | accepted | speech | TTS alternatives are out of scope: there is no ML Kit GenAI/AICore TTS API, and network-required TTS voices are unsupported. | decisions.md §2.2, §5 | — | No action; revisit only if GenAI adds a TTS API or the on-device TTS requirement changes. |
| R-0010 | accepted | speech | Platform `SpeechRecognizer` is deliberately not adopted as a second STT engine. | decisions.md §2.1, §5 | — | No action; revisit only on an explicit single-engine scope change. |
| R-0011 | accepted | speech | General ONNX Runtime support is out of scope; scope is the pinned Smart Turn artifact only. | decisions.md §5 | — | No action; revisit only on an explicit scope change. |

## Language models, providers, and credentials

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0012 | open | llm-provider | No provider is wired: the app runs `NotConfiguredLanguageModel`, which fails every request with `LLM_NOT_CONFIGURED`. | conversation-ui.md, ConversationDefaults.kt | M13-M19, M23 | A real adapter behind the shared contract and the text-first vertical slice, with the UI and turn path unchanged. |
| R-0013 | resolved (M12) | llm-provider | `LanguageModel`/`LlmRequest` was explicitly the M02 seam; M12 refined the request, bounded context, and stream metadata. | LanguageModel.kt | M12 | Refined contract in `contracts/LanguageModel.kt` + `contracts/LlmStreamConsumer.kt`: typed `LlmFailureReason` (delta/completion/timeout/rate-limit/auth/network/malformed/cancellation distinct), `LlmUsage`, capability-declared reasoning (`LlmCapabilities`/`LlmRequestValidator`), legal stream transitions, and the partial-response text on `Cancelled`/`Failed`. Deterministic fake `DeterministicLanguageModel` plus JVM tests cover ordering, backpressure, cancellation, timeout, malformed/empty streams, and partial state; `LlmContractPurityTest` proves no vendor/platform import reaches the contract. Documented in [llm-contract.md](./llm-contract.md). |
| R-0014 | open | llm-provider | OpenCode Go's streaming event shape and reasoning control are unknown. | decisions.md §4, §6 | M17 | Verified fixtures or a controlled smoke test covering stream events and reasoning; no assumed OpenAI parity. |
| R-0015 | open | llm-provider | OpenCode Zen's streaming shape and reasoning controls are unknown; its `/systemone` model is not a conversational chat completion. | decisions.md §4, §6 | M18 | Verified fixtures; `/systemone` never surfaced as a chat model. |
| R-0016 | open | llm-provider | Hermes Agent API Server's exact streaming event shape and auth/session semantics are unknown. | decisions.md §4, §6 | M19 | Behavior established from authoritative docs or a controlled test server. |
| R-0017 | open (M12/M21 partial) | llm-provider | OpenRouter may silently fall back to other providers/GPUs on errors or rate limits, which conflicts with the no-silent-fallback rule. | decisions.md §4 | M15 | M12 gives the seam to detect it: `LlmStreamEvent.Completed.model`/`reasoning` echo what the provider reported, and `LlmStreamResult.model` exposes it. M21 records a provider-reported model that differs from the selection as `DiagnosticAttribute.REPORTED_MODEL_ID` on the `LLM_REQUEST` trace and a test proves the request is never rerouted (`TurnOrchestratorTest.aProviderReportingADifferentModelIsRecordedInsteadOfSilentlyAccepted`). M15 must still constrain OpenRouter routing (`route`/`models[]`) so the mismatch cannot happen in the first place. |
| R-0018 | resolved (M14) | llm-provider | OpenAI Responses stores conversation content by default, which conflicts with the privacy requirements. | decisions.md §4 | M14 | Verified 2026-09-29: the Responses `store` parameter "Defaults to true when omitted", and `store: true` stores response data "for at least 30 days" ([create reference](https://platform.openai.com/docs/api-reference/responses/create), [reasoning guide](https://developers.openai.com/api/docs/guides/reasoning)). `OpenAiResponses.encodeRequest` always sends `"store": false`, and `OpenAiLanguageModelTest.theRequestGoesToTheDocumentedEndpointWithBearerAuthAndStoreFalse` asserts it on the encoded payload. Documented in [openai-adapter.md](./openai-adapter.md). |
| R-0019 | open | llm-provider | Hermes executes tools (`pwd`, file, browser, MCP) on the server host, not as a pure proxy. | decisions.md §4 | M19, M22 | Destination disclosed, TLS required for non-local hosts, no hard-coded public endpoint. |
| R-0020 | open | product | Whether OpenCode Go's terms accommodate a non-coding voice client is a product/legal question. | decisions.md §4, §6 | M17, M23 | Terms confirmation before the app relies on Go. |
| R-0021 | resolved (M13) | credentials | Credential storage is decided (Keystore-backed; `security-crypto` is deprecated) but not implemented. | decisions.md §1 | — | M13 implements `CredentialStore` with a platform-free `EncryptedCredentialStore`, an `AndroidKeyStore` AES-256/GCM cipher plus an app-private preferences blob store (no `security-crypto`), and an in-memory JVM implementation. `CredentialStoreTest` covers store/replace/remove, a fresh-instance restart read, at-rest encryption, and typed failures; `CredentialRedactionTest` proves no secret reaches logs or crash metadata; `RepositorySecretScanTest` finds no bundled secret. Details in [credentials.md](./credentials.md). On-device run pending (R-0075). |
| R-0022 | accepted | llm-provider | QR pairing and OAuth/device authorization beyond OpenRouter's PKCE are not documented by any provider and must not be invented. | decisions.md §4, §5 | M22 (only if documented) | No action; keep the capability false until a provider documents a short-lived, single-use flow. |
| R-0023 | resolved (M21) | llm-provider | Silent provider/model fallback is unsupported; fallback must be explicit and visible. | decisions.md §5 | — | M12 lets a fallback be *detected*: `Completed.model`/`reasoning` carry the provider-reported identity and `LlmStreamResult` surfaces it. M21 enforces the rule in `TurnOrchestrator`: it always requests the user-selected `ProviderModelSelection`, records a provider-reported model that differs as `DiagnosticAttribute.REPORTED_MODEL_ID` (never silently accepting it), and never switches provider/model on failure. `TurnOrchestratorTest.aProviderReportingADifferentModelIsRecordedInsteadOfSilentlyAccepted` proves no rerouting. OpenRouter-specific routing constraints remain with M15 (R-0017). |
| R-0024 | accepted | product | A "fully offline" claim is unsupported because the default LLM path is remote. | decisions.md §5 | — | No action; revisit only if an on-device LLM becomes the default. |

## Persistence

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0025 | open | persistence | Room schema version 1 has no migrations yet. | persistence.md | first schema change, otherwise unspecified | Add a `Migration`, bump `ConversationDatabase.VERSION`, keep the previous exported schema, and add a migration test before the change ships. |
| R-0026 | open | build/toolchain | Room tests are pinned to Robolectric API 35 because API 36 `android-all` needs JDK 21 while CI uses JDK 17. | persistence.md | M26, otherwise unspecified | Revisit when CI moves to JDK 21 or the pinned toolchain changes. |
| R-0027 | accepted | persistence | Conversation data is kept until the user deletes it: no automatic expiry and no remote sync. | persistence.md | — | No action; revisit if a retention policy is added. |

## Tracing and diagnostics

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0028 | resolved (M21) | tracing | M04 provides the tracing seam only; M21 owns the request-state vocabulary and calls the recorder from real orchestration. | turn-tracing.md | — | M21 finalizes the vocabulary as `orchestration.LlmRequestState` (`selected`/`streaming`/`completed`/`ended`/`cancelled`/`failed`) and `TurnOrchestrator` drives the per-turn `TurnTraceRecorder` (`requestSelected`, `markStreamStarted`, `requestState`, `requestEndReason`, `requestUsage`, `llmDelta`, `playbackStarted`, `playbackDelivered`). `TurnOrchestratorTest` and `ConversationViewModelTest` assert correlated, content-free events; documented in [orchestration.md](./orchestration.md). |
| R-0029 | open (M21 partial) | tracing | `TurnTraceRecorder.playbackStarted`/`playbackStopped`/`playbackDelivered` still have no real caller. | turn-tracing.md | M24 | M11 emits `TTS_PLAYBACK` first-audible/interrupted diagnostics (counts and route kind, no turn id) from `EngineTextToSpeech`. M21 now owns the recorder: `TurnOrchestrator` calls `playbackStarted` on the first `TtsEvent.Started` and `playbackDelivered` at the terminal with delivered-vs-generated counts. Barge-in `playbackStopped` onset->stop timing is still wired by M24, which owns live interruption (see R-0081/R-0083). |
| R-0030 | open | tracing | A developer-visible in-app trace viewer is deferred, and buffer drop/eviction counts are not surfaced in the UI. | turn-tracing.md | unspecified | A developer surface exists and exposes drop/eviction counts. |
| R-0031 | open (M13 partial) | privacy | Redaction covers imperative paths (HTTP headers, debug logs, crash metadata) via helpers, not by construction; those paths are not wired yet. | turn-tracing.md | M13, M26 | M13 applies `Redaction`/`AppLog.secret` on the credential path and `CredentialRedactionTest` proves a secret never reaches a log line, a `toString`, a store error, or a crash-metadata header map. No crash reporter is wired yet, so the remaining provider/crash paths are still M26. |

## UI and turn orchestration

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0032 | resolved (M21) | orchestration | The M06 `ConversationViewModel` is not the M21 turn state machine: it serializes one request and leaves out-of-order late events and TTS delivery accounting to M21; `TurnPhase` is a deliberate seam M21 may refine. | conversation-ui.md, TurnPhase.kt | — | M21 adds the pure `orchestration.TurnStateMachine` (revisions, stale-event dropping by turn ID, cancellation races, generated/queued/delivered accounting, typed outcomes) and `TurnOrchestrator`; `ConversationViewModel` now delegates generation to the orchestrator and only maps its terminal `TurnRecord` to UI state. `TurnStateMachineTest`/`TurnOrchestratorTest` cover the acceptance cases, `TurnPhase` is finalized with the manual-text and streaming edges, and the M06 UI/viewmodel tests still pass. See [orchestration.md](./orchestration.md). |
| R-0033 | open | ui | No voice path: STT/TTS and barge-in are not implemented; only a provisional-transcript seam and the shared turn path exist. | conversation-ui.md | M07-M11, M21-M24 | Live provisional transcript, streamed assistant speech, and responsive interruption on device. |
| R-0034 | accepted | product | Long-term personalized memory and user-configurable prompt profiles are future-release scope, with consent/retention/editing/deletion still undefined. | decisions.md §5, persistence.md | unspecified | No action now; needs its own product spec before implementation. |

## Replay and test corpus

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0035 | accepted | replay/test | Synthetic fixtures are deliberately not intelligible speech, and generated variants keep the base labels, so they cannot measure recognition quality. | audio-replay-harness.md | — | No action; accuracy claims must come from the human corpus, and synthetic/human results are never merged. |
| R-0036 | open | replay/test | The permissioned human-speech fixture intake is not satisfied: no human corpus is committed, and the local `Walking *.m4a` files have unverified license/consent. | audio-replay-harness.md | M08, M25 | Documented consent and license, labels produced by the real on-device engine/model, and a committed manifest under `replay/human/`. |
| R-0037 | open | replay/test | The local `test_data/smart-turn-v3.2-eng/` set's license/consent has not been verified for re-use; its `README.md` was not present in the docs worktree, and only two local `.m4a` recordings exist there. | AGENTS.md, audio-replay-harness.md | unspecified | Verify license/consent before relying on any recording; do not commit until it passes the intake process. |

## Build, toolchain, and project identity

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0038 | open | identity | The `applicationId`/`namespace` `com.voicechat.agent` is provisional; no owner domain is established. | decisions.md §1 | unspecified (before distribution) | Finalize to a controlled domain; cheap now, disruptive after publishing. |
| R-0039 | open | build/toolchain | M00's provider and platform sources were all read on one access date (2026-09-28) and change over time. | decisions.md intro | each dependent milestone | Re-verify a decision against current official docs before implementing the milestone that depends on it. |
| R-0040 | open | build/toolchain | The local verification machine builds with Android Studio's JBR (JDK 25) while CI uses Temurin 17 and no JDK 17 is installed locally. | decisions.md §1 | M26, otherwise unspecified | Decide whether to install JDK 17 locally or move CI to a newer JDK. |
| R-0041 | accepted | build/toolchain | `minSdk` below 31 is not supported because there is no on-device STT path below API 31. | decisions.md §1, §5 | — | No action; revisit only if an on-device STT path appears below 31. |
| R-0042 | accepted | build/toolchain | Large models must not be bundled in the APK by default; model files are local test input, not app assets. | decisions.md §3.3, §5, AGENTS.md | M10 (Smart Turn download) | No action on the rule; R-0008 tracks the download lifecycle that satisfies it. |

## Device validation and performance

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0043 | open | device-validation | No Pixel 10 baselines exist, so no numeric latency budget has been set. | implementation-plan.md (delivery rules), AGENTS.md | M25, M26 | Recorded device, model, runtime, conditions, and measurement method before any numeric budget. |
| R-0044 | open | device-validation | Device-level checks (microphone, AICore/ML Kit, TFLite/LiteRT, real TTS, audio routing) have no automation. | AGENTS.md | M26 | Manual Pixel 10 checklist results reported; never claimed as automated. |
| R-0045 | open | device-validation | GVP device measurements and speech-android Smart Turn settings are hypotheses and test cases, not defaults for this app. | implementation-plan.md, AGENTS.md | M25 | Re-measure on Pixel 10; do not copy thresholds, software gain, or RMS values. |
| R-0046 | open | device-validation | Barge-in responsiveness and echo/noise false-interruption defense are unmeasured, and a long fixed interruption grace period is disallowed. | AGENTS.md | M24, M25 | Echo, road noise, music, double-talk, first-word, and route-change tests with recorded stop/cancel timing. |
| R-0047 | open | device-validation | Time-to-first-audio/perceived latency is a primary requirement but unmeasured. | AGENTS.md | M25 | Per-stage p50/tail timings from replay fixtures and Pixel 10 runs, with all configuration metadata. |

## Privacy and security

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0048 | open (M13 partial) | privacy | External LLM use is a network feature: the transfer must be disclosed, and any change that sends audio, transcript, prompt, or model data off-device must be documented. | AGENTS.md | M13, M22, M26 | M13 adds destination disclosure: a validated `ServerDestination.disclosure()` names the scheme and host before text is sent, and [credentials.md](./credentials.md) documents the transport rules. The in-request transfer notice and the full disclosure UI are M22/M26. |
| R-0049 | resolved (M13) | privacy | No static or packaged-resource secret checks exist yet. | implementation-plan.md (M13), AGENTS.md | — | M13 adds `security.RepositorySecretScanTest`, which scans shipped source, `res`, build files, docs, and CI config for credential shapes (provider keys, AWS/Google/GitHub/Slack tokens, private keys, JWTs, `key = "..."` pairs) and asserts no packaged `.jks`/`.keystore`/`google-services.json`. It finds none; user credentials live only in the AndroidKeyStore. Re-run at M26 against the packaged release artifact. |

## Capture and STT follow-ups (M07-M08)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0050 | open | capture | Audio-focus policy is provisional: capture takes transient `USAGE_ASSISTANT` focus and records focus loss but does not stop on it. M11's TTS engine now also holds the same transient focus through `AudioFocusController`, so simultaneous capture/TTS operation is unmodeled (R-0060). | audio-capture.md, tts.md | M24 | Focus policy finalized against real TTS playback and barge-in, with route/focus tests. |
| R-0051 | open | capture | Capture session events carry no trace/turn ID (a session can span turns), so orchestrator correlation is deferred. | audio-capture.md | M21 | M09 added optional trace/turn IDs to TURN_DETECTION events, but capture events remain uncorrelated until M21 associates both with the active turn. |
| R-0052 | open | capture | The bounded capture buffer drops frames when a consumer lags; drops are counted and surfaced, but the sustained-backpressure policy (drop vs block vs error) is unvalidated in a live pipeline. | audio-capture.md | M21 | M09's detector/policy consume the capture flow as a non-blocking transform, but the combined live STT/VAD loop is not assembled until M21, so sustained-backpressure behavior and the impact of counted drops stay unmeasured. |
| R-0053 | open | speech | The M08 ML Kit adapter compiles and is unit-tested, but runtime status gating, `fromPfd` streaming, and final-segment merging are unverified on hardware. | stt.md | M08 (manual run), M26 | Pixel 10 manual run records `checkStatus()`/provisioning and proves on-device transcription. |
| R-0054 | open | speech | Final-segment merge assumes `curText += response.text`; if the engine returns cumulative text per final, the merge would duplicate text. A debug probe observed **only one** `FinalTextResponse` per session (so multi-segment merge is unverified) and **cumulative partials** (already handled by replacement). | stt.md | M08 (manual run) | Verified against real `FinalTextResponse` behavior; merge adjusted if cumulative; only one final has been observed so far. |
| R-0055 | open | speech | The engine reports no confidence, so low-confidence handling is a `null` pass-through with no threshold policy. | stt.md | M09, M21 | Defined behavior for absent/low confidence that still surfaces usable text. |
| R-0056 | open | speech | `fromPfd` requires audio at a real-time rate (about 32 KB/s) and does not support file-backed descriptors that read at full speed, so the adapter is capture-coupled and not feed-forward, and replay must keep using the M03 adapter. The response flow also does not complete on capture EOF; only `stopRecognition()` completes it, so the adapter stops on input end and bounds the wait for the terminal completion (logic-only, not device verified). | stt.md, audio-replay-harness.md, ML Kit speech-recognition docs | M24 | Live loop uses real-time capture or an explicitly paced feeder; any feed-forward requirement is documented and tested. |

## TTS follow-ups (M11)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0057 | open | device-validation | The M11 platform TTS adapter compiles and is unit-tested, but voice enumeration, first-audible latency, speaker/headset/Bluetooth routing, focus changes, and immediate-stop latency are unverified on hardware. | tts.md | M11 (manual run), M26 | Pixel 10 manual run recording installed voices, selected voice, route, first-audible and stop latencies, and the empty/failed/no-voice paths (Tests.md). |
| R-0058 | open | speech | The interrupted-utterance delivered prefix comes from `UtteranceProgressListener.onRangeStart`, which not every engine calls; without it an interrupted chunk reports an empty delivered prefix (conservative, never over-reports). | tts.md | M11 (manual run), M24 | Observed `onRangeStart` behavior on the Pixel 10 engine and the recorded audible prefix per interrupted utterance; adjust if the engine reports progress differently. |
| R-0059 | open | tracing | TTS diagnostics are emitted without a trace/turn id, and the adapter does not call the per-turn `TurnTraceRecorder` playback helpers, so per-turn first-audible and barge-in stop timing are not yet correlated. | tts.md, turn-tracing.md | M21, M24 | Orchestration binds TTS events to the active turn/trace and records first-audible and stop/cancel timing. |
| R-0060 | open | capture | Capture (M07) and TTS (M11) both hold transient `USAGE_ASSISTANT` audio focus through the same `AudioFocusController` boundary; simultaneous operation and the duck/pause policy are unmodeled. | tts.md, audio-capture.md | M24 | A single cross-stage focus policy with measured behavior when capture and playback overlap. |

## VAD, onset, and endpointing (M09)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0061 | open | speech | The measured-audio VAD thresholds (onset/hangover RMS, zero-crossing rate, onset/pause frames) and the 2000 ms maximum-silence cap are provisional, and the route-aware Bluetooth/wired values are placeholders, not Pixel 10 measurements. | docs/vad-endpointing.md, decisions.md §2.3 | M25 | Recorded per-route onset latency and false endpoint/hold rates from labeled replay and Pixel 10 runs; replace the placeholders with measured values. |
| R-0062 | open | speech | The energy/ZCR detector cannot distinguish loud music or continuous non-speech noise from speech, so it reports activity for them. | docs/vad-endpointing.md, voice-quality-and-latency.md | M24, M25 | Barge-in decisions layer duration and echo evidence; measured false-interrupt behavior for music/noise/echo without a long fixed grace period. |
| R-0063 | open | speech | The pure VAD/endpoint path is replay-tested but not yet wired into the live audio loop; end-to-end onset/endpoint latency, CPU, and memory are unmeasured. | docs/vad-endpointing.md | M24, M25 | M21 added the orchestration seam that consumes finalized/interim transcripts (`TurnStateMachine.beginListening`/`Provisional`/`TranscriptRejected`, tested for revision ordering and the empty/no-speech paths), but the live capture -> VAD -> STT -> orchestration assembly itself is still not wired (see R-0082). Device runs must record end-to-end onset latency, endpoint delay, and resource use. |
| R-0064 | open | speech | `TurnCompletionDetector.evaluate(window: AudioFrame)` receives one concatenated recent-audio frame (default 8 s) from M09; Smart Turn v3.2's exact input/rate/shape is not yet confirmed against that contract. | docs/vad-endpointing.md, decisions.md §3.3 | M10 | M10 confirms the pinned artifact's input contract and refines the semantic window or the contract if they differ. |

## LLM contract follow-ups (M12)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0065 | open (M13 partial) | llm-provider | `LlmCapabilities` is declared per adapter but there is no per-*model* capability source, so `LlmRequestValidator` can only enforce what an adapter has `LlmCapabilities`-declared, not which reasoning levels a specific model exposes. | llm-contract.md | M13, M22 | M13 adds the per-model source (`ModelCapabilities`/`ModelCapabilityCatalog` + `LlmCapabilityReconciler`), which intersects the provider union with the model's `/models` report and feeds `LlmRequestValidator`, so an unsupported option is refused/hidden (`ModelCapabilityReconciliationTest`). The live `/models` parsing (provider adapters, M14+) and the settings UI that consumes it (M22) are still pending. |
| R-0066 | open (M14 partial) | llm-provider | The contract has no reasoning/thinking-channel representation: an adapter that receives a separate reasoning stream must exclude it from `Delta.text` and currently has nowhere to surface it. | llm-contract.md | first adapter with a reasoning channel (M14/M16) | M14 resolves the exclusion for OpenAI: `OpenAiResponses.parse` returns a distinct `OpenAiStreamFrame.Reasoning` for `response.reasoning_summary_text.delta`/`response.reasoning_text.delta`, the adapter never emits it as a `Delta`, and `OpenAiLanguageModelTest.theReasoningChannelIsExcludedFromAssistantText` proves the reasoning text is absent from `LlmStreamResult.text` while the reported `reasoning.effort` is surfaced on `Completed`. A typed side channel for the reasoning text itself is still open (R-0095). |
| R-0067 | open (M14 partial) | llm-provider | A flow that ends without a terminal event is mapped to `LLM_MALFORMED_RESPONSE`, but no adapter exists yet to confirm that real providers always terminate cleanly (or to map an early socket close to `NETWORK` instead). | llm-contract.md, ConversationViewModel.kt | M14-M19 | M21 moves the mapping into `TurnOrchestrator.applyTerminal` (a terminal-less stream reduces `ProviderFailed(LLM_MALFORMED_RESPONSE)` and is persisted `FAILED`). M14 proves the OpenAI early-close behavior with fixtures: an unterminated stream (`terminal_less.sse`) maps to `LLM_MALFORMED_RESPONSE` (`aTerminalLessStreamIsNotACompletion`), while a mid-stream connection drop maps to `LLM_NETWORK_FAILED` (`aNetworkLossKeepsThePartialTextAndReportsNetwork`) — the `MALFORMED_RESPONSE` fallback stays only for a genuinely unterminated stream. |
| R-0068 | open | llm-provider | `TurnStreamTrace` passes delta text to a tracing hook for live rendering; nothing structurally prevents a future trace implementation from retaining it, so content-freedom still depends on review plus the existing redaction tests. | llm-contract.md, docs/logging.md | M26 | M21 records deltas through `TurnTraceRecorder` (index + character count only) in `TurnOrchestrator`; the delta text is forwarded to the UI and the TTS chunker but never to the trace, and `TurnOrchestratorTest.historyAndTraceStayContentFree` asserts neither prompt nor assistant text appears in any trace attribute. M26 re-checks that no trace implementation retains delta or prompt text. |
| R-0069 | open | device-validation | Provider latency, cancellation-acknowledgement timing, and streaming-parse behavior of the contract are unmeasured because no adapter is wired; the fake proves semantics, not a provider. | llm-contract.md | M14-M20, M25 | Per-provider fixture tests plus opt-in smoke runs with recorded device/model/reasoning/network conditions. |

## Credentials and provider registry (M13)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0070 | open | credentials | The AndroidKeyStore key can be permanently invalidated or lost (device restore, lockscreen change, KeyStore reset), leaving an undecryptable credential blob. M13 reports `NotStored`/`null` and requires re-entry, but the invalidation scenarios are not device-tested. | docs/credentials.md | M13 (manual run), M26 | A Pixel 10 run that reproduces a key invalidation and shows the honest re-entry state with no crash and no wrong value. |
| R-0071 | open | privacy | Encrypted credential blobs live in app-private `SharedPreferences`; exclusion from backup/transfer relies on `allowBackup="false"` and `data_extraction_rules.xml` and is unverified against a real backup/restore or device-to-device transfer. | docs/credentials.md, res/xml/data_extraction_rules.xml | M26 | Device backup/restore and D2D transfer show no credential blob is exported. |
| R-0072 | open (M14 partial) | llm-provider | The provider capability registry encodes the M00 matrix (accessed 2026-09-28); Go/Zen/Hermes entries are explicitly marked unverified and none has been re-checked against live provider docs. | docs/credentials.md, decisions.md §4 | M14-M19, M22 | The **OpenAI** row was re-verified 2026-09-29: the endpoint/auth/streaming/usage/reasoning facts and sources are in [openai-adapter.md](./openai-adapter.md), and the documented reasoning union now includes `minimal`, so `ProviderCapabilityRegistry.openAi()` and `ProviderCapabilityRegistryTest` were updated (M14). Go/Zen/Hermes rows are still unverified and must be re-checked before their adapters ship (M17-M19). |
| R-0073 | open (M14 partial) | credentials | Optional minimal credential validation is a policy + interface only; no real network validator exists, so no provider credential is actually validated and the auth-error path is proven with a fake, not a provider. | docs/credentials.md | M14-M19 | M14 implements the OpenAI validator (`OpenAiCredentialValidator`, `GET /v1/models`) and proves with fixtures that 401/403 map to `LLM_AUTHENTICATION_FAILED` and that the key is never echoed (`OpenAiCredentialValidatorTest`); a transient failure keeps its typed code. It has not been run against the live service (R-0096), and the other providers' validators remain with M15-M19. |
| R-0074 | open | credentials | Destination validation checks scheme/host/TLS but does not resolve DNS, follow redirects, or defend against DNS rebinding; a hostname resolving to a private address would still pass as `https`. | docs/credentials.md, decisions.md §4 | M19 | The Hermes adapter constrains redirects/DNS and proves that a request cannot be redirected to an unintended host. |
| R-0075 | open | device-validation | The `AndroidKeystoreCredentialStoreInstrumentedTest` is compiled only; store/replace/remove and the restart read have not been run against a real device KeyStore, and a true `force-stop` relaunch remains a manual check. | docs/credentials.md, Tests.md | M13 (manual run), M26 | Pixel 10 run of the instrumented test plus a manual force-stop relaunch showing the credential persists and preferences hold only ciphertext. |

## Turn orchestration follow-ups (M21)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0080 | open | orchestration | TTS delivery launches one child coroutine per complete chunk (bounded only by the chunker's size and the response length) rather than a bounded external queue, and chunk overlap/first-audible latency is unmeasured. | docs/orchestration.md | M24, M25 | A bounded chunk pipeline with a measured backpressure policy on Pixel 10, plus first-audible and inter-chunk gap timings under a real provider stream. |
| R-0081 | open | orchestration | The delivery wait is bounded by a 60 s timeout that degrades a wedged TTS engine to an `Interrupted` turn; the real engine's stall/hang behavior and the timeout value are device-unverified. | docs/orchestration.md | M24 (M25 for timing) | Observed engine stop/hang behavior on Pixel 10; the timeout and its fallback classification validated against a real engine. |
| R-0082 | open | orchestration | M21 consumes a finalized transcript and exposes the listening/revision seam, but the live capture -> VAD -> STT -> orchestration loop is not assembled, so real-time revision, endpoint, and barge-in behavior are unmeasured. | docs/orchestration.md, docs/vad-endpointing.md | M24 | The voice loop wired end to end with device runs covering revisions, endpointing, and interruption timing (refines R-0063). |
| R-0083 | open | orchestration | The UI still allows one active turn at a time; orchestrator-level supersede-by-turn-ID is tested, but the app does not yet let a new submission interrupt an active turn, and the barge-in onset path is not driven by live audio. | docs/orchestration.md | M24 | A new utterance can supersede/interrupt an active turn in the running app, with no stale event applied and correct delivered-only history. |

## OpenAI adapter and shared transport (M14)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0090 | open | llm-provider | The shared transport has no retry policy and does not honor a `Retry-After` header; retryability is signalled only by the typed reason, and a caller must implement its own bounded retry. | docs/llm-transport.md | M26 | A bounded retry/backoff policy (with `Retry-After`) that never replays a partially streamed response, plus a test for a retryable failure. |
| R-0091 | open | device-validation | The OpenAI adapter is proven only by recorded fixtures; it has never been run against the live service, so real latency, auth, streaming-parse quirks, and cancellation acknowledgement are unmeasured. | docs/openai-adapter.md, Tests.md | M25 | An opt-in `OpenAiSmokeTest` run with recorded device/model/reasoning/network conditions and time-to-first-text. |
| R-0092 | open | llm-provider | OpenAI per-model reasoning levels are not discovered: `GET /models` lists identities only, so the adapter validates against the provider union and a model-specific rejection surfaces as `LLM_INVALID_REQUEST` rather than being hidden up front. | docs/openai-adapter.md | M22 | A per-model capability catalog (from model docs or a curated map) feeding `LlmCapabilityReconciler` so an unsupported level is hidden before a request. |
| R-0093 | accepted | llm-provider | Only the OpenAI Responses API is wired; Chat Completions and the Responses `reasoning.mode` (`standard`/`pro`) and `reasoning.summary` controls are not surfaced. | docs/openai-adapter.md | — | No action until a model/UX needs them; revisit with the settings UI (M22). |
| R-0094 | open | llm-provider | The OkHttp engine runs one blocking call per stream on `Dispatchers.IO` with no explicit concurrency cap; many concurrent streams could exhaust the IO pool. | docs/llm-transport.md | M26 | A bounded dispatcher/connection policy with a test or measured limit under concurrent streams. |
| R-0095 | open | llm-provider | The reasoning channel is dropped, not surfaced: `OpenAiStreamFrame.Reasoning` has nowhere to go, so a model's reasoning summary cannot be shown even when `reasoning.summary` is requested. | docs/openai-adapter.md, llm-contract.md | M22/M26 | A typed reasoning side channel (or an explicit decision to keep it dropped) with a test that it never concatenates into assistant text. |
| R-0096 | open | credentials | `OpenAiCredentialValidator` (`GET /models`) is fixture-tested only and has never validated a real key; a valid key without model access could still report `Valid`. | docs/openai-adapter.md | M25 | An opt-in live validation run showing the documented auth mapping against a real key. |
| R-0097 | open | privacy | The app now declares `android.permission.INTERNET` and an adapter can send text off-device, but the app still runs `NotConfiguredLanguageModel`, so no user-facing destination disclosure or consent flow is wired. | AndroidManifest.xml, docs/privacy-and-security.md | M23 | The M23 vertical slice wires the adapter with a visible destination and disclosure before any request. |
| R-0098 | accepted | llm-provider | The transport caps SSE frames at 4 MiB and buffered bodies at 1 MiB; a legitimate frame larger than the cap would be reported as malformed rather than handled. | docs/llm-transport.md | — | No action for chat text; revisit only if a provider sends larger single frames. |
| R-0099 | open | privacy | TLS relies on the platform trust store (OkHttp default); no certificate pinning, proxy policy, or DNS-rebinding defense is configured (the latter is M19's Hermes concern, R-0074). | docs/llm-transport.md | M26 | A reviewed transport security policy and, where warranted, pinning or proxy handling with a device test. |
