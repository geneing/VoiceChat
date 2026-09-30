# Implementation Plan

This plan turns the product and architecture documents into small,
independently reviewable milestones that can be assigned to implementation
agents. It describes future work: M00 (decisions), M01 (buildable Android
scaffold), M02 (core domain types and replaceable contracts), M03 (deterministic
audio replay and speech-test foundations), M04 (privacy-safe turn tracing and
timing), M05 (conversation persistence and bounded context), M06 (Compose
conversation UI and manual text path), M07 (microphone capture and audio
lifecycle), M08 (on-device ML Kit GenAI speech-to-text), M09 (VAD, fast onset,
and bounded VAD-only endpointing), M11 (on-device TTS), M12 (LLM streaming
contract and deterministic fake), M13 (credential storage and provider
capability registry), M14 (OpenAI adapter and shared remote transport), M15
(OpenRouter adapter), M16 (DeepSeek adapter), M17 (OpenCode Go adapter), M18
(OpenCode Zen adapter), M19 (Hermes adapter), M21 (turn orchestration and
cancellation), and M22 (settings and capability-aware selection) are
implemented, so no later milestone should be reported as complete until its code
and validation exist.

## Delivery rules

- Begin with **M00**. Record decisions and verified constraints before
  introducing SDKs, model artifacts, or provider assumptions.
- Each milestone is one bounded handoff. Its agent should read the linked
  requirements, inspect current code, implement only the stated scope, add the
  named tests, and report changed files, commands/results, unresolved risks,
  and any follow-up decision.
- For every handoff, include the milestone section and prerequisite acceptance
  reports. Require the agent to read [AGENTS.md](../AGENTS.md) and the
  applicable [architecture](./architecture.md),
  [product requirements](./product-requirements.md),
  [validation plan](./validation.md),
  [provider requirements](./llm-providers.md),
  [model/runtime requirements](./model-runtime.md),
  [privacy requirements](./privacy-and-security.md), and
  [voice-quality requirements](./voice-quality-and-latency.md) before changing
  behavior.
- A milestone is complete only when its acceptance checks pass and directly
  related documentation is updated. If a dependency or product decision is
  blocked, return the evidence and stop rather than inventing behavior.
- Follow the Git workflow in [AGENTS.md](../AGENTS.md): create a descriptive
  branch from the latest local `master` for significant work, commit the
  validated milestone, merge locally after integration checks, and never
  push. Combine only tightly coupled milestones on one branch; keep provider
  adapters and other independently reviewable work isolated.
- Keep provider calls, audio, inference, and persistence off the main thread.
  Use structured concurrency, cancellation, bounded queues, and explicit
  error states throughout.
- Do not log credentials, raw audio, full prompts, or full transcripts by
  default. Do not silently switch providers, models, or local/remote data
  paths.
- Record Pixel 10 baselines before setting numeric latency budgets. The GVP
  device measurements and speech-android Smart Turn settings are hypotheses
  and test cases, not defaults for this app.

## Dependency map

The default order is M00 → M01 → M02. M03 and M04 can follow M02 in parallel.
M05 and M06 build the text-first conversation experience; M07–M11 establish
the on-device audio and speech capabilities; M12–M20 establish model/provider
execution; M21 integrates those paths. M22 can begin after M02 and the
relevant capability catalogs are stable. M23–M26 are integration and release
gates.

Within M14–M19, provider adapters can be assigned independently once M12 and
M13 are accepted. They must each implement the same app-facing contract and
must not edit shared orchestration or credential code without coordination.
M10 (Smart Turn) is optional for the first usable voice loop; the VAD-only
bounded endpoint policy from M09 must work without it.

## Milestones

### M00 — Verify implementation choices and constraints

**Goal:** Replace open-ended assumptions with a short, evidence-backed
decision record.

**Agent handoff**

1. Inspect this plan and the existing architecture, model/runtime, provider,
   privacy, validation, and device-notes documents.
2. Check current official Android, ML Kit/AICore, LiteRT, ONNX Runtime, and
   provider documentation for applicable APIs, licensing, lifecycle, auth,
   streaming, and device support. Record links and access date.
3. Evaluate viable on-device STT and TTS paths for Pixel 10 and define a
   fallback/unavailable behavior. Do not infer that a named runtime supports a
   given speech task.
4. Define the initial allow-listed TFLite/LiteRT model candidates and their
   provenance, license, task, runtime contract, and resource expectations.
   Verify Smart Turn v3.2 artifact identity, license, input contract, and
   acquisition strategy separately.
5. Verify each LLM service's endpoint, model discovery/selection, streaming,
   supported reasoning controls, and documented authentication methods.
   Treat OpenCode Go, OpenCode Zen, and Hermes as distinct integrations.
6. Recommend project SDK levels, Kotlin/Gradle/dependency versions, package
   identity, Compose configuration, persistence choice, and CI/check commands
   based on verified requirements. Jetpack Compose is the required UI toolkit;
   do not evaluate alternate native UI toolkits. Avoid copying versions or
   device constants from the reference projects.

**Deliverables:** A compact decision record in the relevant docs; a provider
capability matrix; explicit deferred/unsupported items and the reason for
each. Do not add application dependencies in this milestone. Delivered in
[docs/decisions.md](./decisions.md).

**Validation / acceptance:** Every chosen API and artifact has an authoritative
source and a stated compatibility basis. Any unknown remains labeled unknown;
no QR, model, or device capability is claimed without evidence.

### M01 — Scaffold the Kotlin Android application

**Goal:** Establish the smallest buildable Android application and repeatable
developer workflow.

**Agent handoff**

1. Create the Gradle project and Kotlin app module using M00 decisions, with
   Jetpack Compose enabled.
2. Add the project package structure, minimal launch activity/application
   entry point, and a simple Compose root screen/theme; avoid speculative
   feature modules.
3. Add the Gradle wrapper and documented local build, test, lint, and format
   commands. Add CI for the same fast checks if the repository's hosting setup
   is available.
4. Set manifest permissions only when required by an implemented capability;
   do not request microphone permission at install/startup without context.
5. Update README status and AGENTS commands to reflect the actual scaffold.

**Validation / acceptance:** A clean checkout can build and run the minimal
Compose app on an emulator or Pixel 10. CI runs the same compile/unit/lint
checks. No unnecessary permission, secret, model binary, or unverified SDK
assumption is introduced.

### M02 — Define core domain types and replaceable contracts

**Goal:** Give UI, platform adapters, tests, and providers stable typed seams.

**Agent handoff**

1. Define conversation, user/assistant turn, transcript revision, turn ID,
   provider/model selection, connection state, and typed error/state models.
2. Define narrow contracts for audio input, STT, VAD/turn completion, LLM
   streaming, TTS synthesis/playback, model availability, persistence, and
   diagnostics.
3. Distinguish interim from final STT; distinguish generated assistant text
   from text/audio actually delivered; represent cancellation and partial
   completion explicitly.
4. Use coroutine/Flow or the M00-selected equivalent for bounded event streams.
   Document ownership and lifecycle for each stream.
5. Add fake implementations for contract-level tests.

**Validation / acceptance:** Unit tests cover event ordering, invalid state
transitions, cancellation, and serialization where applicable. Platform and
vendor types do not leak into the conversation domain. Interfaces stay small
and do not introduce a generic plugin framework.

**Delivered:** pure-Kotlin domain models in
`app/src/main/kotlin/com/voicechat/agent/domain`, replaceable contract
interfaces in `.../contracts`, and deterministic fakes in
`app/src/test/kotlin/com/voicechat/agent/fake`. Focused JVM tests cover event
ordering, invalid state transitions, cancellation, serialized error/ID tokens,
and a source-level guard that the domain and contract packages contain no
`android.`/`androidx.` imports. No platform, network, model, or persistence
implementation is included; those arrive in later milestones.

### M03 — Build deterministic audio replay and speech-test foundations

**Goal:** Make the main speech regressions repeatable without a live
microphone.

**Agent handoff**

1. Implement a PCM/frame replay source that drives the same capture/STT/VAD
   boundaries used by the app.
2. Define a fixture manifest for source identity, expected transcript/labels,
   sample rate, engine/model, transformation parameters, and random seed.
3. Add a small permissioned human-speech fixture set or a documented fixture
   intake process; do not commit unlicensed or unconsented recordings.
4. Generate and freeze deterministic harness-TTS fixtures for broad coverage,
   then add repeatable variants for street/car noise, competing speech, echo,
   reverberation, gain, clipping, compression, and codec artifacts. Do not
   require a live cloud TTS service to replay regression fixtures.
5. Keep synthetic and human-speech evaluation results separate.

**Validation / acceptance:** Replaying one fixture twice yields the same
frames, labels, and transformation metadata. Tests exercise revised partial
hypotheses and pause/resume input. No test needs live credentials, network, or
microphone hardware.

### M04 — Add privacy-safe turn tracing and timing

**Goal:** Make voice and LLM behavior diagnosable from the first vertical
slice.

**Agent handoff**

1. Add a trace ID per conversation turn and structured events for stage
   start/end, selected provider/model, request state, cancellation, errors,
   and delivered playback progress.
2. Capture stage durations, LLM time-to-first-text/inter-delta gaps, TTS
   synthesis and first-audible timing, and barge-in stop/cancel timing.
3. Add a developer-visible local trace viewer/export suitable for test runs;
   keep any transcript/prompt capture behind explicit opt-in and bounded local
   retention.
4. Redact credentials, audio, transcript, prompt, and provider response
   content from default logs and crash metadata.

**Validation / acceptance:** Tests assert a trace remains correlated across a
fake streamed turn, cancellation, and error. Redaction tests prove secret and
content fields are absent by default. Timing uses monotonic clocks and cannot
block the audio/UI path.

### M05 — Persist conversations and bound model context

**Goal:** Support durable dialog history without resending all stored history
to a provider.

**Agent handoff**

1. Implement the conversation repository and schema/migrations using M00's
   persistence decision.
2. Support create, list, open/reopen, rename if selected, and delete
   conversations and turns.
3. Persist provisional/final transcript and assistant delivery/interruption
   state consistently; define recovery for process death mid-turn.
4. Implement a separate bounded context builder that selects the content sent
   to the LLM and preserves the full local history independently.
5. Define local retention, backup exclusion where appropriate, and deletion
   semantics.

**Validation / acceptance:** Repository tests cover CRUD, ordering, migration,
   process-restart restoration, deletion, interrupted turns, and context
   bounds. A test proves an older conversation is not silently added to a new
   request.

### M06 — Create the Jetpack Compose conversation UI and manual text path

**Goal:** Deliver the first usable dialog surface before integrating audio.

**Agent handoff**

1. Implement Compose screens for the conversation list, new conversation,
   opening older conversations, dialog transcript, and manual text composer.
2. Show interim STT as provisional, final user text as committed, assistant
   deltas incrementally, and explicit loading/error/cancel/interrupted states.
3. Allow a user to edit/correct recognized text before submission and to
   retry/cancel requests; do not silently rewrite recognition.
4. Add deletion controls and accessible loading/focus/error behavior.
5. Bind Compose UI to lifecycle-aware, fake/repository-backed state; do not
   couple screens to a specific provider or speech SDK.

**Validation / acceptance:** UI tests cover text send, correction, streaming
render, conversation switching, deletion, and process-restored history. Manual
text uses the same turn/conversation path as voice.

### M07 — Implement microphone capture and audio lifecycle

**Goal:** Capture well-defined PCM safely, with user-visible permission and
route handling.

**Agent handoff**

1. Implement permission request at point of use, capture start/stop, audio
   format negotiation, route changes, focus/lifecycle cleanup, and explicit
   error states.
2. Keep capture on a dedicated non-UI execution path and expose bounded PCM
   frames to the replay-compatible pipeline.
3. Add privacy-safe capture diagnostics (format, route, levels, clipping and
   drop counters) without retaining raw audio by default.
4. Evaluate source, AEC/NS/AGC, gain, and route behavior on Pixel 10; do not
   copy GVP's software gain or RMS values.

**Validation / acceptance:** Fake-source tests cover start/stop and failure.
Android/device tests cover permission denial/revocation, lifecycle cleanup,
route changes, no main-thread I/O, and no leaked recorder. Replay and physical
capture use compatible frame contracts.

### M08 — Select and integrate the first on-device STT engine

**Goal:** Deliver revisable on-device transcription through the STT contract.

**Agent handoff**

1. Implement the M00-selected supported engine behind the STT adapter. Check
   runtime/model availability and permissions at runtime.
2. Deliver partial revisions and a final result with language/engine metadata
   where available; handle empty, low-confidence, and failed results
   explicitly.
3. Preserve the raw recognized text for user review. Add vocabulary hints or
   adaptation only if the selected engine supports it.
4. Add engine/model selection or a clearly reported single-engine state; never
   claim a missing or unprovisioned model is ready.

**Validation / acceptance:** Replay tests cover partial revisions, finalization,
empty input, corrections, numbers, names, negation, disfluency, accents, and
low-SNR fixtures. Pixel 10 tests confirm actual on-device processing and
record engine/model, audio path, and preprocessing with results.

### M09 — Add VAD, fast speech onset, and bounded VAD-only endpointing

**Goal:** Separate continuous speech activity/onset from semantic turn
completion.

**Agent handoff**

1. Implement the selected VAD/onset path using measured audio characteristics
   and route-aware configuration.
2. Emit candidate pauses and speech-onset events independently from final
   turn completion.
3. Add a configurable, validated maximum-silence endpoint that always
   terminates a trailing turn even without Smart Turn.
4. Preserve one logical user turn across a pause and resumed speech; define
   empty/no-speech behavior.
5. Instrument threshold/event reasons for test and development builds.

**Validation / acceptance:** Replay tests cover complete/incomplete phrases,
natural pauses, resumed speech, prolonged silence, echo, music, road noise, and
short acknowledgements. Exactly one final endpoint occurs per turn. Onset
latency and false endpoint/hold behavior are measured on Pixel 10.

### M10 — Integrate optional Smart Turn v3.2 with ONNX Runtime

**Goal:** Add replaceable semantic completion only after a VAD pause.

**Agent handoff**

1. Implement the turn-completion adapter and opt-in settings, isolated from
   the barge-in/onset path.
2. Pin the verified Smart Turn artifact and narrowly scoped ONNX Runtime
   dependency; verify license, checksum/source revision, input shape/rate,
   preprocessing, probability output, and model lifecycle.
3. Load/infer off the main thread only at a candidate pause. Keep resumed
   speech in the same turn when completion is vetoed.
4. Provide clear disabled/unavailable/corrupt-model behavior using M09's
   bounded VAD-only policy. Report failures, not success-shaped fallbacks.
5. Keep model fetching/installing app-private, integrity checked, cancellable,
   and removable if the selected acquisition approach requires downloads.

**Validation / acceptance:** Unit tests cover config validation and adapter
contracts. Replay/device tests cover completed and incomplete phrases, pause
then resumed speech, silence cap, model missing/corrupt, and exactly-once
endpoint behavior. Confirm no per-frame inference; measure inference time,
memory, CPU, and false-commit/false-hold tradeoffs. Keep opt-in until evidence
supports another default.

### M11 — Select and integrate on-device TTS

**Goal:** Synthesize and play assistant speech through a replaceable local
adapter.

**Agent handoff**

1. Integrate the M00-selected TTS engine and discover installed voice/model
   availability at runtime.
2. Support queued incremental text chunks where the engine allows it, with
   completion, error, audio-focus, route, and immediate stop/cancel controls.
3. Track generated, queued, started, and audibly completed text/audio so
   interruption state is accurate.
4. Keep TTS entirely on-device and avoid claiming a selected voice/model is
   available when it is not.

**Validation / acceptance:** Fake tests cover chunk ordering, cancellation,
empty input, failure, and playback accounting. Android/device tests measure
first-audible time and exercise speaker/headset paths, focus changes, and
immediate stop during playback.

### M12 — Define the LLM streaming contract and deterministic fake

**Goal:** Establish provider-independent request and streaming semantics.

**Agent handoff**

1. Define request, bounded context, provider/model identity, supported
   reasoning setting, stream events, token/usage metadata, and typed errors.
2. Represent delta, completion, timeout, rate limit, authentication failure,
   network failure, cancellation, and malformed response distinctly.
3. Implement a deterministic fake that can emit delayed deltas, stalls,
   errors, and cancellation races.
4. Ensure the public contract does not expose a vendor SDK or assume all
   providers have identical capabilities.

**Validation / acceptance:** Unit tests cover ordering, backpressure,
cancellation, timeout, malformed/empty streams, and partial-response state.
Fake runs are deterministic and require no network or credentials.

### M13 — Implement credential storage and provider capability registry

**Goal:** Safely support user credentials and accurately expose per-provider
connection options.

**Agent handoff**

1. Implement Keystore-backed storage for user-supplied credentials with
   replacement, removal, backup policy, and redacted status.
2. Define provider capabilities for auth methods, endpoints, model discovery,
   streaming, and reasoning controls; keep auth separate from transport.
3. Validate configurable Hermes server destinations and secure transport
   rules; do not trust arbitrary QR URLs as endpoints.
4. Implement API-key entry and optional minimal credential validation only
   where provider semantics support it.
5. Never persist provider passwords or bundle an app-owned reusable secret.

**Validation / acceptance:** Tests cover store/replace/remove, process restart,
unsupported method hiding, endpoint validation, authentication errors, and
log/crash redaction. Static and packaged-resource checks find no test/live
secrets.

### M14 — Implement the OpenAI adapter

**Goal:** Deliver one complete remote provider through the common contract.

**Agent handoff**

1. Verify current official OpenAI endpoint, auth, model catalog, streaming,
   cancellation, usage, and reasoning controls; document date and sources.
2. Implement request mapping, incremental response parsing, timeout/retry
   rules, typed errors, and model/capability reporting.
3. Integrate user credential retrieval without exposing keys to UI state or
   trace output.
4. Provide fixture-based protocol tests and an opt-in smoke-test path.

**Validation / acceptance:** Fixtures cover normal stream, empty response,
malformed frames, rate limit, auth failure, server error, network loss, and
cancellation mid-stream. Optional real-provider smoke tests never run in
routine CI and use externally supplied credentials.

### M15 — Implement the OpenRouter adapter

**Goal:** Support OpenRouter without assuming OpenAI model/capability parity.

**Agent handoff**

1. Verify official OpenRouter endpoint, key/auth, model catalog/discovery,
   streaming, parameters, and error/usage semantics.
2. Implement provider-specific mapping and only expose verified capabilities
   through the shared LLM contract.
3. Respect provider/model identifiers and user-selected model; do not silently
   route to a different model.
4. Add redacted fixtures and optional smoke-test support.

**Validation / acceptance:** Contract tests cover streamed deltas, provider
model selection, auth/rate-limit/error mapping, cancellation, and unsupported
parameters. Prove model identity in the trace is the selected one.

### M16 — Implement the DeepSeek adapter

**Goal:** Add DeepSeek as an independent provider integration.

**Agent handoff**

1. Verify current official endpoint, auth, model selection, streaming,
   reasoning controls, and response/error schema.
2. Implement the verified feature subset behind the shared contract.
3. Keep model/provider identity and user-visible unsupported settings
   explicit; add fixture and optional smoke tests.

**Validation / acceptance:** Tests cover stream parsing, model selection,
unsupported settings, auth, timeout, cancellation, rate limits, and errors.
No protocol assumptions are copied from another provider without verification.

### M17 — Implement the OpenCode Go adapter

**Goal:** Support OpenCode Go's own service and connection capabilities.

**Agent handoff**

1. Verify current official OpenCode Go endpoint, model access, streaming, auth
   and any account/device/QR authorization flow.
2. Implement the adapter and only provider-documented connection methods.
   Never place a reusable credential in a QR payload.
3. Add protocol fixtures and expose only supported model/reasoning features.

**Validation / acceptance:** Tests cover the verified flow and ordinary
   provider errors; if a capability is not offered, UI/capability metadata
   explicitly omits it. No invented OpenAI-compatible behavior is relied on.

### M18 — Implement the OpenCode Zen adapter

**Goal:** Support OpenCode Zen independently from OpenCode Go.

**Agent handoff**

1. Verify current official OpenCode Zen endpoint, models, streaming, account
   requirements, auth, and supported pairing/sign-in.
2. Implement provider-specific request/response and auth mapping without
   reusing Go-specific assumptions.
3. Add fixture tests and an opt-in smoke test.

**Validation / acceptance:** Tests prove provider/model identity, stream
   behavior, documented auth, cancellation, and typed failure reporting.
   Unsupported features are not exposed.

### M19 — Implement the Hermes Agent API Server adapter

**Goal:** Connect to a user/admin-configured Hermes server safely.

**Agent handoff**

1. Verify the current Hermes Agent API Server protocol and deployment
   configuration; establish model discovery, streaming, and auth behavior
   from authoritative docs or a controlled test server.
2. Implement configurable server address validation, TLS rules for remote
   hosts, visible destination disclosure, auth, and provider-specific mapping.
3. Add local mock-server fixtures; avoid hard-coded public endpoints or
   server-side secrets in the APK.

**Validation / acceptance:** Tests cover valid/invalid destinations, TLS and
   auth failures, streaming, cancellation, and server errors. The user can
   identify the destination before sending text.

### M20 — Add eligible local LLM runtimes and curated model catalog

**Goal:** Offer only verified, available on-device LLM options alongside
remote providers.

**Agent handoff**

1. Implement runtime discovery for eligible AICore/ML Kit GenAI capabilities
   using supported APIs and actual runtime availability.
2. Add the M00-approved TFLite/LiteRT adapter and allow-listed model metadata;
   validate tokenizer, tensor contract, operators, delegate, ABI, and
   model-specific preprocessing/postprocessing.
3. Keep system-managed AICore assets separate from app-managed downloadable
   artifacts. Use integrity checking and explicit lifecycle for app-managed
   assets.
4. Expose performance/resource requirements and unavailable reasons. Keep
   local versus external selection explicit and visible to the user.

**Validation / acceptance:** Tests cover supported, unavailable, unprovisioned,
   corrupt/missing, and insufficient-resource paths. Pixel 10 runs measure
   cold/warm startup, memory, thermal behavior, latency, and contention with
   STT/TTS. Do not infer arbitrary TFLite or ONNX compatibility.

### M21 — Implement turn orchestration and cancellation

**Goal:** Connect typed speech/provider events into one deterministic turn
state machine.

**Agent handoff**

1. Implement conversation orchestration around M02, M05, M08–M12, and the
   selected provider interfaces.
2. Use a finalized/corrected user turn and bounded context to start one
   provider request; stream deltas to UI and complete text chunks to TTS.
3. Define states for no speech, empty/low-confidence transcript, provider
   error, retry, cancellation, TTS failure, and interrupted response.
4. Track which assistant text was generated, queued, and delivered; persist
   only truthful state.
5. Use turn IDs to discard late events from cancelled or superseded work.

**Validation / acceptance:** Deterministic state-machine tests cover voice and
   manual turns, revisions, out-of-order late events, cancellation races,
   provider/TTS errors, and history persistence. No UI or vendor code directly
   controls pipeline internals.

### M22 — Implement settings and capability-aware selection

**Goal:** Let users configure supported STT, LLM, and TTS choices without
showing unsupported options.

**Agent handoff**

1. Build settings for STT engine/model/options, LLM provider/model/auth and
   supported reasoning level, TTS engine/voice, and Smart Turn availability.
2. Show selection, connection/model availability, errors, and actionable
   setup/retry/remove controls.
3. Populate options from runtime/provider capability catalogs and persist
   only validated selections.
4. Make provider destination and remote text/context transfer clear before a
   remote request; keep full conversation history distinct from sent context.
5. Implement browser/device/QR auth only for providers whose verified
   capability metadata and current official docs support it.

**Validation / acceptance:** UI/state tests cover unsupported options hidden,
   unavailable models, credential replacement/removal, invalid selections,
   and persistence. QR tests (when applicable) prove short-lived
   provider-authorized pairing and reject reusable credentials/arbitrary
   destinations.

### M23 — Complete text-first end-to-end provider vertical slice

**Goal:** Prove conversation UI → persistence → streaming LLM → visible result
with one real provider before voice integration.

**Agent handoff**

1. Wire M05, M06, M12–M13, M17, and M21 for a typed request using OpenCode Go
   as the initial integration path.
2. Display selected provider/model, remote transfer notice, live output,
   failure/cancel/retry, and accurate conversation history.
3. Verify bounded request context and privacy-safe trace across the entire
   turn.

**Validation / acceptance:** Automated fake-provider UI/integration test
   exercises the full lifecycle. One opt-in Pixel 10 smoke run may use a
   user-configured, provider-supported connection and must be documented
   without recording secrets or conversation content. The rest of CI stays
   credential-free.

### M24 — Integrate the voice loop and responsive barge-in

**Goal:** Deliver on-device STT → selected LLM → on-device TTS with
interruptible streaming.

**Agent handoff**

1. Connect M07–M11 and M21; show live provisional transcript and streamed
   assistant output in the dialog.
2. Detect likely user speech during playback on the fast onset/VAD path; stop
   TTS audio and queued chunks immediately, cancel provider/synthesis work,
   and continue capturing the new utterance without waiting for all
   cancellation acknowledgements.
3. Reconcile persisted assistant content with text/audio actually delivered.
   Define explicit recovery for false/noise interruptions and short
   acknowledgements.
4. Exercise built-in speaker and headset paths; investigate AEC only from
   measurements, without copying GVP thresholds or adding a long fixed
   playback grace period.

**Validation / acceptance:** Deterministic replay tests interrupt at multiple
   LLM/TTS points and verify next-user capture, no stale events, correct
   history, and stop/cancel timing. Pixel 10 checks include echo, road noise,
   music, double-talk, first-word interruptions, and route changes.

### M25 — Run speech quality, endpoint, and latency evaluation

**Goal:** Establish evidence-based quality and responsiveness baselines and
decide whether to enable optional behavior.

**Agent handoff**

1. Run the labeled human and deterministic synthetic corpus across selected
   STT settings; report WER/CER plus names, numbers, negation, correction, and
   acknowledgement errors by condition.
2. Sweep VAD and Smart Turn thresholds/silence cap; compare false commits,
   false holds, endpoint latency, CPU, memory, and model-load behavior.
3. Measure per-stage and end-to-end p50/tail latency: speech end to first
   assistant text, first audible TTS, completion, and user speech onset to
   playback stop/cancel acknowledgement.
4. Repeat by provider/model/reasoning level and local runtime where supported;
   label device, OS, route, model/runtime, network conditions, and fixture.
5. Set initial measurable performance budgets only from repeatable Pixel 10
   baselines. Tune for both STT quality and interruption responsiveness.

**Validation / acceptance:** Results are reproducible from recorded fixtures
   and carry configuration metadata. Smart Turn stays opt-in unless its
   accuracy/latency/resource results meet agreed acceptance criteria. Any
   tuning that changes behavior has regression tests.

### M26 — Release hardening and operational readiness

**Goal:** Verify the first release candidate is safe, recoverable, and
maintainable.

**Agent handoff**

1. Run full unit, integration, UI, lint, and build checks; run the complete
   Pixel 10 matrix for permissions, network/provider errors, model
   unavailable/corrupt, process death, history, and barge-in.
2. Audit credential, transcript, audio, trace, backup, permission, TLS, and
   remote-data handling against the privacy/security requirements.
3. Verify provider capabilities and auth flows against current official docs;
   confirm no silent provider/model fallback and no test secrets in artifacts.
4. Document first-run setup, provider configuration, model availability,
   supported devices/limitations, developer commands, and known issues.
5. Keep long-term memory and configurable prompt profiles out of scope unless
   separately specified with consent, editing, retention, and deletion rules.

**Validation / acceptance:** Release build and automated checks pass; the
Pixel 10 checklist is complete with results and known limitations recorded.
No feature is described as supported if its implementation or device/API
validation is missing. Merge to local `master` only after the applicable
integration checks; do not push.

## Cross-cutting completion checklist

Every agent handoff should report:

- Scope completed and files changed.
- Build, lint, and focused test commands run, with actual results.
- Device/model/provider conditions for any integration or performance result.
- Privacy/security implications and documentation updates.
- Known limitations, unresolved decisions, and the next unblocked milestone.

Keep future long-term memory and configurable prompt profiles as separate
product work with their own privacy and user-control requirements; they are
not implied by conversation persistence.
