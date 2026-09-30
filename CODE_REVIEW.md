# Code Review

Date: 2026-09-30  
Scope: static review of the current implementation against `AGENTS.md`, `README.md`, and the product/architecture documents. No build, test, lint, or device run is claimed; this review is based on source and documentation inspection only.

## Executive assessment

The implementation has a strong architectural foundation. The pure-Kotlin seams around audio input, STT, VAD/endpointing, orchestration, LLM providers, TTS, persistence, credentials, diagnostics, and local runtimes are unusually clear for a single-module Android app. The deterministic fakes and fixture-driven provider tests should make the next phase tractable.

The main risk is no longer missing separation of concerns; it is that several independently careful components are joined through mutable, lifecycle-sensitive state. Before claiming a production-ready voice loop, the project should make state ownership explicit, make settings authoritative for every selected engine, and remove silent degradation paths. The priorities below are ordered by user-visible failure or privacy impact.

## Findings and recommendations

### P1 — Concurrency is not fully confined in the voice coordinator and ViewModel

`VoiceSessionCoordinator` launches capture, detection, STT, generation, TTS stop, and observer callbacks concurrently, but several state variables are ordinary mutable fields: `conversation`, `generationJob`, `generationTurnId`, `activeOrchestrator`, and `stopped` (`app/src/main/kotlin/com/voicechat/agent/voice/VoiceSessionCoordinator.kt:104-118`). `activeListening` and `detectInput` are volatile, but volatility does not make compound transitions atomic. For example, an onset can race with endpoint handling, or a late generation callback can race with a new turn's provider lookup.

The same pattern exists in `ConversationViewModel`: `latestSettings`, `currentDisclosure`, `currentConversation`, `generationJob`, and voice-session references are mutated from multiple coroutine callbacks (`app/src/main/kotlin/com/voicechat/agent/ui/ConversationViewModel.kt:171-219`, `:237-325`). The `isActive` checks prevent some duplicate work but do not serialize check-and-start operations.

Recommendation:

- Confine each session's reducer state to one coroutine/actor, or protect all cross-coroutine transitions with a `Mutex`.
- Represent generation/session identity, conversation snapshot, provider selection, and lifecycle status in one immutable state object.
- Make every callback carry a generation/session token and reject it in the reducer before mutating state.
- Add stress tests later for simultaneous onset, endpoint, stop, settings change, activity recreation, and late provider/TTS events.

This is the highest-value implementation hardening because a race here can produce duplicate turns, an assistant response attached to the wrong conversation, or a stale provider/model being used after settings changed.

### P1 — The production voice assembly does not honor all persisted STT/TTS settings

The app passes only `applicationContext`, repository, fallback model, and Smart Turn provider into `VoiceSessionAssembly.platformFactory` (`app/src/main/kotlin/com/voicechat/agent/MainActivity.kt:95-102`). The assembly chooses the first runtime-ready STT engine for the locale (`app/src/main/kotlin/com/voicechat/agent/voice/VoiceSessionAssembly.kt:187-188`) and constructs TTS from the locale (`:110-115`). It does not receive or resolve the persisted STT mode, STT locale, or TTS voice selection.

This conflicts with the settings requirement that the selected engine/model/voice drive the actual pipeline. A user can appear to select an option while the voice session continues using the first available engine and default platform voice.

Recommendation:

- Resolve a validated immutable `VoiceRuntimeSelection` at session start from the same settings snapshot used for provider disclosure.
- Pass the selected STT mode/locale and TTS voice identifier into the platform assembly.
- If a selected option is unavailable, expose a typed unavailable state and an actionable settings notice; do not silently substitute another engine or voice.
- Add a test that changes the persisted STT/TTS selection and verifies the next session receives that exact selection.

### P1 — TTS initialization failure is silently converted into a text-only voice session

`PlatformVoiceSession.run` catches every TTS construction failure and sets `textToSpeech` to `null` (`app/src/main/kotlin/com/voicechat/agent/voice/VoiceSessionAssembly.kt:112-117`). The coordinator then continues as if TTS were optional. That can make the UI report a working voice loop even though the product's voice response stage is unavailable, and it removes the opportunity to explain or recover from the failure.

Recommendation:

- Return a typed `TtsAvailability` result from construction instead of catching into `null`.
- Distinguish “TTS intentionally disabled” from “selected TTS unavailable” and “initialization failed.”
- Surface the failure in `VoiceSessionState` and the dialog/settings screen, while preserving any assistant text already generated.
- Keep fallback behavior explicit and user-visible rather than implicit.

### P1 — Remote-transfer disclosure is not an explicit consent gate

The code and risk register intentionally provide a disclosure banner, but the project also documents that no explicit per-send consent gate was added (`docs/risks-and-decisions.md:R-0097`). The product requirements describe sending user-approved text/context to a remote provider, and the settings/provider disclosure is not equivalent to recording that the user approved the current destination and retention policy.

Recommendation:

- Add a first-use and change-of-destination consent state for remote providers.
- Bind consent to provider, model, destination, and data-retention policy; invalidate it when any of those change.
- Require the text and voice paths to use the same gate before the first remote request, with local-model turns exempt.
- Persist only the minimum consent metadata needed to avoid repeatedly prompting, and provide a reset/revoke action.

### P1 — Endpoint handling can wait indefinitely for STT completion

On endpoint, the coordinator closes the STT input channel and then joins the STT job without a timeout (`app/src/main/kotlin/com/voicechat/agent/voice/VoiceSessionCoordinator.kt:259-266`). The ML Kit adapter has its own stop-completion bound, but the coordinator's contract depends on every future `SpeechToText` implementation responding to channel closure. A stalled engine can therefore keep the session in listening state indefinitely and prevent cleanup.

Recommendation:

- Put a bounded timeout around the STT job join.
- On timeout, cancel/close the recognizer, emit a typed STT completion failure, and return the session to a recoverable state.
- Keep the timeout separate from the VAD maximum-silence bound and record it as a diagnostic outcome.
- Add a fake STT implementation that never completes to prove session teardown.

### P2 — Resource ownership is implicit at the Compose composition root

`MainActivity` creates the Room repository, DataStore store, OkHttp engine/transport, provider factory, Smart Turn provider, and voice factory inside `setContent` using `remember` (`app/src/main/kotlin/com/voicechat/agent/MainActivity.kt:53-113`). This is convenient, but the long-lived resources have no explicit activity/application shutdown owner. `RemoteTransport` exposes `close()` and the OkHttp engine shuts down its executor (`app/src/main/kotlin/com/voicechat/agent/remote/RemoteTransport.kt:217-218`; `OkHttpStreamingEngine.kt:106-109`), yet `MainActivity` never closes it.

Recommendation:

- Move app-scoped dependencies into an application-owned component or an explicit composition-root container.
- Give that owner a clear lifecycle and close the HTTP engine when the app container is disposed.
- Keep activity recreation from creating multiple independent clients, databases, or Smart Turn factories.
- Add lifecycle tests for recreation and shutdown, especially while a stream or voice session is active.

### P2 — Provider/model availability is still too permissive for a shipped settings path

The risk register records that the live model catalog is not wired and that the Android settings capability provider reports an empty model list (`docs/risks-and-decisions.md:R-0102`). The factory correctly refuses unknown providers/models, but the user-facing flow still needs a clear “no selectable model” state and a path to refresh or validate models before sending.

Recommendation:

- Make model availability a first-class state: loading, available, unavailable, stale, and failed.
- Do not allow a provider to appear configured when no validated model is selectable.
- Separate cached model metadata from the selected model and revalidate on provider/destination changes.
- Display the reason and recovery action when the catalog is empty; keep the no-silent-fallback rule.

### P2 — Remote transport needs a bounded concurrency policy before release

The shared OkHttp engine starts one blocking `Call.execute()` per stream on `Dispatchers.IO` (`app/src/main/kotlin/com/voicechat/agent/remote/OkHttpStreamingEngine.kt:72-104`). The existing risk register identifies that there is no explicit concurrency cap (`docs/risks-and-decisions.md:R-0094`). A single-user app can still accumulate streams during rapid cancellation, activity recreation, or provider errors.

Recommendation:

- Bound active provider calls per app and per conversation.
- Ensure cancellation removes the call from the accounting before a replacement begins.
- Expose a typed “busy/backpressure” result rather than allowing unbounded IO growth.
- Measure the limit on the target device and document the chosen policy.

### P2 — The current persistence API is correct but inefficient for larger histories

Every save replaces the entire turn list: `replaceConversation` upserts the conversation, deletes all turns, and reinserts the complete list (`app/src/main/kotlin/com/voicechat/agent/persistence/ConversationDao.kt:56-72`). This gives excellent atomicity and simple recovery, but streamed assistant updates or long conversations will repeatedly rewrite all historical turns and increase database work.

Recommendation:

- Keep the current replacement path for small conversations and recovery, but add an append/update transaction for the active turn.
- Store a stable turn ordinal or ID and update only the active assistant turn while streaming/settling.
- Retain a bounded-context query so the request path never loads or serializes the entire transcript unnecessarily.
- Add a migration/performance decision before enabling long-running conversations or background sync.

### P2 — Documentation is internally inconsistent with the implementation

The opening README says voice, model, provider, and persistence features are not implemented (`README.md:7`, `:40-47`), while later sections describe M07–M24 as implemented (`README.md:164-222`). The README also states “The 813-test JVM suite” (`README.md:201`), which is a volatile count and should not be maintained as a hand-written claim.

Recommendation:

- Rewrite the opening status paragraph to describe the implemented M07–M24 scope and the remaining M25/M26 validation work.
- Replace the hard-coded test count with a capability/status statement or generate the count in CI.
- Keep “implemented in source,” “host-tested,” “compiled for device,” and “executed on Pixel 10” as separate statuses.
- Update the README whenever a milestone changes, since it is currently the first misleading description a maintainer will see.

### P2 — Release hardening remains a product risk, not just a test gap

The repository documents that M25 speech/latency evaluation and M26 release hardening are not implemented (`AGENTS.md:20-30`, `README.md:219-222`). The architecture has useful timing hooks, but there are no verified Pixel 10 measurements for time-to-first-text, time-to-first-audible-audio, barge-in stop latency, memory, thermal behavior, STT model availability, or TTS voice availability.

Recommendation:

- Define a repeatable Pixel 10 test matrix before tuning thresholds: route, locale, noise condition, provider/model, reasoning level, cold/warm state, and network condition.
- Record p50/p95 stage timings and failure rates, not only aggregate completion time.
- Exercise process death, permission revocation, audio-route changes, unavailable AICore/ML Kit models, no embedded TTS voice, network loss, and provider cancellation.
- Treat the current implementation as functionally testable but not performance- or release-validated until those runs are recorded.

## What is working well

- Clear platform-free contracts and domain types keep provider, audio, and UI code replaceable.
- The voice path separates speech onset/barge-in from endpointing, matching the product requirement.
- Turn IDs, delivered-text accounting, and cancellation-aware orchestration address the most difficult streaming correctness cases.
- Credentials are behind a Keystore-backed store and the transport avoids logging request bodies and header values.
- Room writes are transactional and the context builder is intentionally separate from durable transcript storage.
- Provider adapters share a small transport without leaking OkHttp/JSON types into the app-facing contracts.
- The codebase has strong purity/source-scan tests and deterministic fakes, which should make the concurrency and lifecycle recommendations above straightforward to verify.

## Suggested implementation order

1. Introduce a serialized reducer/actor for `VoiceSessionCoordinator` and the ViewModel's session/generation state.
2. Make persisted STT/TTS selections authoritative and make unavailable selected engines visible.
3. Add bounded STT endpoint teardown and explicit remote-transfer consent.
4. Establish app-scoped resource ownership and close the HTTP engine cleanly.
5. Finish model catalog/availability UX, then perform the Pixel 10 M25/M26 validation matrix.
6. Refresh `README.md` and keep implementation/device-validation status separate.

