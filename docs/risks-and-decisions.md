# Risks and Open Decisions

This is a **living** tracking file for everything still unresolved across the
milestones: risks, undecided questions, deferred work, and deliberate
limitations. It is not a decision record. Resolved choices live in
[decisions.md](./decisions.md) (the M00 record) or in the milestone document
that made them; this file records what is still not settled and who is expected
to settle it.

It currently covers M00-M08. Later milestone agents **append** items here as
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

## Speech: on-device STT, TTS, VAD, and turn completion

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0001 | open | speech | The only STT engine, ML Kit GenAI Speech Recognition, is pinned at `1.0.0-alpha1`: alpha, no SLA or deprecation policy, backward-incompatible changes possible. | decisions.md §2.1 | M08 | Runtime `checkStatus()` gating; re-verify artifact and docs before M08; never present STT as ready unless `AVAILABLE`. |
| R-0002 | open | speech | Advanced STT mode requires Pixel 10/11 specifically; Basic needs API 31+. Availability is device/config dependent and not yet tested here. | decisions.md §2.1 | M08 | Pixel 10 run recording engine/mode and proof of on-device processing; explicit unavailable path if status is not `AVAILABLE`. |
| R-0003 | open | speech | STT is not supported on devices with an unlocked bootloader; AICore binding/preparation can fail. | decisions.md §2.1 | unspecified | Failures surfaced through `checkStatus()` as an explicit unavailable reason, not a crash. |
| R-0004 | open | speech | No on-device TTS voice is guaranteed; voices are device- and user-configurable and a network voice must never be used silently. | decisions.md §2.2 | M11 | Runtime `getVoices()` restricted to `isNetworkConnectionRequired() == false`; explicit "no on-device voice" state. |
| R-0005 | open | speech | No VAD/onset path or model is selected; ONNX scope is Smart Turn only, so an ONNX VAD (for example Silero) is out of scope; no TFLite VAD is allow-listed. | decisions.md §2.3 | M09 | Measured onset plus a bounded maximum-silence endpoint that works without Smart Turn; scope change needed for an ONNX VAD. |
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
| R-0013 | open | llm-provider | `LanguageModel`/`LlmRequest` is explicitly the M02 seam; M12 is expected to refine the request, bounded context, and stream metadata. | LanguageModel.kt | M12 | M12 contract plus deterministic fake, with no vendor SDK type in the public contract. |
| R-0014 | open | llm-provider | OpenCode Go's streaming event shape and reasoning control are unknown. | decisions.md §4, §6 | M17 | Verified fixtures or a controlled smoke test covering stream events and reasoning; no assumed OpenAI parity. |
| R-0015 | open | llm-provider | OpenCode Zen's streaming shape and reasoning controls are unknown; its `/systemone` model is not a conversational chat completion. | decisions.md §4, §6 | M18 | Verified fixtures; `/systemone` never surfaced as a chat model. |
| R-0016 | open | llm-provider | Hermes Agent API Server's exact streaming event shape and auth/session semantics are unknown. | decisions.md §4, §6 | M19 | Behavior established from authoritative docs or a controlled test server. |
| R-0017 | open | llm-provider | OpenRouter may silently fall back to other providers/GPUs on errors or rate limits, which conflicts with the no-silent-fallback rule. | decisions.md §4 | M15, M21 | Adapter constrains routing and the trace records the model actually used (via `usage`/`model`). |
| R-0018 | open | llm-provider | OpenAI Responses stores conversation content by default, which conflicts with the privacy requirements. | decisions.md §4 | M14 | Set `store: false` and verify it in the adapter. |
| R-0019 | open | llm-provider | Hermes executes tools (`pwd`, file, browser, MCP) on the server host, not as a pure proxy. | decisions.md §4 | M19, M22 | Destination disclosed, TLS required for non-local hosts, no hard-coded public endpoint. |
| R-0020 | open | product | Whether OpenCode Go's terms accommodate a non-coding voice client is a product/legal question. | decisions.md §4, §6 | M17, M23 | Terms confirmation before the app relies on Go. |
| R-0021 | open | credentials | Credential storage is decided (Keystore-backed; `security-crypto` is deprecated) but not implemented. | decisions.md §1 | M13 | Keystore-backed store/replace/remove with redacted status; no bundled reusable secret; store/replace/remove and redaction tests. |
| R-0022 | accepted | llm-provider | QR pairing and OAuth/device authorization beyond OpenRouter's PKCE are not documented by any provider and must not be invented. | decisions.md §4, §5 | M22 (only if documented) | No action; keep the capability false until a provider documents a short-lived, single-use flow. |
| R-0023 | accepted | llm-provider | Silent provider/model fallback is unsupported; fallback must be explicit and visible. | decisions.md §5 | M21 (enforcement) | No action on the rule itself; M21 must add a regression test that no silent switch occurs. |
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
| R-0028 | open | tracing | M04 provides the tracing seam only; M21 owns the request-state vocabulary and calls the recorder from real orchestration. | turn-tracing.md | M21 | M21 orchestrator emits correlated stage events; request-state vocabulary finalized. |
| R-0029 | open | tracing | `playbackStarted`/`playbackStopped` have no real caller yet. | turn-tracing.md | M07, M11 | Capture and TTS adapters call the playback events; first-audible and barge-in timings become real. |
| R-0030 | open | tracing | A developer-visible in-app trace viewer is deferred, and buffer drop/eviction counts are not surfaced in the UI. | turn-tracing.md | unspecified | A developer surface exists and exposes drop/eviction counts. |
| R-0031 | open | privacy | Redaction covers imperative paths (HTTP headers, debug logs, crash metadata) via helpers, not by construction; those paths are not wired yet. | turn-tracing.md | M13, M26 | `Redaction` applied on provider/debug/crash paths; checks prove no credentials or content leak outside the typed event model. |

## UI and turn orchestration

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0032 | open | orchestration | The M06 `ConversationViewModel` is not the M21 turn state machine: it serializes one request and leaves out-of-order late events and TTS delivery accounting to M21; `TurnPhase` is a deliberate seam M21 may refine. | conversation-ui.md, TurnPhase.kt | M21 | M21 state machine covers revisions, out-of-order late events, cancellation races, and delivery accounting; `TurnPhase` finalized. |
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
| R-0048 | open | privacy | External LLM use is a network feature: the transfer must be disclosed, and any change that sends audio, transcript, prompt, or model data off-device must be documented. | AGENTS.md | M13, M22, M26 | Destination and remote text/context transfer shown before a remote request; related docs updated with each such change. |
| R-0049 | open | privacy | No static or packaged-resource secret checks exist yet. | implementation-plan.md (M13), AGENTS.md | M13, M26 | Checks find no secrets in source, resources, build files, logs, or generated docs; credentials stored only in Keystore-backed storage. |

## Capture and STT follow-ups (M07-M08)

| ID | Status | Area | Item and open question | Source | Resolves in | Evidence to close |
| --- | --- | --- | --- | --- | --- | --- |
| R-0050 | open | capture | Audio-focus policy is provisional: capture takes transient `USAGE_ASSISTANT` focus and records focus loss but does not stop on it, because TTS/barge-in do not exist yet. | audio-capture.md | M11, M24 | Focus policy finalized against real TTS playback and barge-in, with route/focus tests. |
| R-0051 | open | capture | Capture session events carry no trace/turn ID (a session can span turns), so orchestrator correlation is deferred. | audio-capture.md | M21 | M21 associates capture events with the correct turn and trace ID. |
| R-0052 | open | capture | The bounded capture buffer drops frames when a consumer lags; drops are counted and surfaced, but the sustained-backpressure policy (drop vs block vs error) is unvalidated in a live pipeline. | audio-capture.md | M09, M21 | Backpressure behavior measured in the live STT/VAD loop; no silent loss beyond the counted drops. |
| R-0053 | open | speech | The M08 ML Kit adapter compiles and is unit-tested, but runtime status gating, `fromPfd` streaming, and final-segment merging are unverified on hardware. | stt.md | M08 (manual run), M26 | Pixel 10 manual run records `checkStatus()`/provisioning and proves on-device transcription. |
| R-0054 | open | speech | Final-segment merge assumes `curText += response.text`; if the engine returns cumulative text per final, the merge would duplicate text. A debug probe observed **only one** `FinalTextResponse` per session (so multi-segment merge is unverified) and **cumulative partials** (already handled by replacement). | stt.md | M08 (manual run) | Verified against real `FinalTextResponse` behavior; merge adjusted if cumulative; only one final has been observed so far. |
| R-0055 | open | speech | The engine reports no confidence, so low-confidence handling is a `null` pass-through with no threshold policy. | stt.md | M09, M21 | Defined behavior for absent/low confidence that still surfaces usable text. |
| R-0056 | open | speech | `fromPfd` requires audio at a real-time rate (about 32 KB/s) and does not support file-backed descriptors that read at full speed, so the adapter is capture-coupled and not feed-forward, and replay must keep using the M03 adapter. The response flow also does not complete on capture EOF; only `stopRecognition()` completes it, so the adapter stops on input end and bounds the wait for the terminal completion (logic-only, not device verified). | stt.md, audio-replay-harness.md, ML Kit speech-recognition docs | M24 | Live loop uses real-time capture or an explicitly paced feeder; any feed-forward requirement is documented and tested. |
