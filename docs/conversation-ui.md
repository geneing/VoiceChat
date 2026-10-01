# Conversation UI and Manual Text Path (M06)

This document records the Jetpack Compose conversation surface and the
manual-text turn path delivered in M06. It implements the dialog, history,
manual-text, and correction requirements in
[product-requirements.md](./product-requirements.md) and the state/interaction
model in [architecture.md](./architecture.md), on top of the M05 persistence
layer ([persistence.md](./persistence.md)). It is deliberately not the full
voice loop: M07–M11 add audio, and M21 replaces the focused state holder below
with the complete turn state machine.

## Package boundaries

The UI lives in `com.voicechat.agent.ui` and depends only on the M02 contracts
(`ConversationRepository`, `LanguageModel`, `DiagnosticsSink`) and the platform-free
domain types. It does not import Room, a speech SDK, or a provider SDK, so it
cannot be coupled to a specific implementation:

```
ConversationApp (stateless Compose)
  <- VoiceAgentRoot (collects state with the owner lifecycle)
      <- ConversationViewModel (state holder; StateFlow<ConversationUiState>)
          <- ConversationRepository  (M02 contract, Room-backed in the app)
          <- LanguageModel           (M02 contract; resolved per turn from the M22 selection since M23)
          <- DiagnosticsSink          (M04 tracing seam)
```

`MainActivity` builds the app-private repository with
`ConversationPersistence.create(context)`. Since M23 the turn path resolves a
real adapter from the persisted selection through the registry-driven
`RegisteredProviderLanguageModelFactory`; the app runs `NotConfiguredLanguageModel`
only when no provider/model is selected, which fails with `LLM_NOT_CONFIGURED`
as an explicit, recoverable error — there is no fake reply and no silent
fallback. See [text-first-slice.md](./text-first-slice.md).

## Screens

The chat window is the primary surface; history and Settings live in a modal
navigation drawer behind the hamburger (`ModalNavigationDrawer`), the
ChatGPT/Gemini pattern, instead of on a separate list screen. Both are still
driven only by `ConversationUiState`/`ConversationActions`: the drawer is
rendering, not a new navigation state.

- **Home** (`ConversationHomeScreen`): the empty chat shown on launch and after
  deleting the open conversation — the wordmark, the voice orb when the app
  attached a voice factory, the "start a conversation" control, and the hint
  that text and speech are both available.
- **Dialog** (`ConversationDialogScreen`): the transcript plus the
  always-available manual composer and a delete action; the orb sits in the
  composer and reports the live voice state.
- **Drawer** (`ConversationDrawer`): "New chat", the persisted summaries
  (newest first) with a reopen target and a per-row delete control, and the
  Settings entry pinned at the bottom. Selecting an entry closes the drawer.
- **Delete confirmation** (`DeleteConversationDialog`): shared by the drawer and
  the dialog.

## Visual language

The theme follows the neighbouring speech-android (VoxLLM) app: a warm
orange-on-warm-surface palette in place of the earlier green one (platform
dynamic colour is off by default; `VoiceAgentTheme(dynamicColor = true)` opts
back in), sans for conversation text, and the monospace face for machine-ish
metadata (drawer counts, bubble state labels, notice lines). Assistant
bubbles are warm surfaces with a hairline border, user bubbles a muted surface,
both with an asymmetric corner; the voice orb reads its state through intensity
and colour (idle orange, listening red, working amber, speaking green,
unavailable grey). The launcher icon and the pre-Compose window theme use the
same palette.

Opening a conversation loads it, runs
`Conversation.reconcileAfterProcessDeath()` (M05), writes the reconciled state
back when it changed, and only then renders — so a process that died mid-turn
never reports a reply it could not finish.

## State model

`ConversationViewModel` exposes one immutable `StateFlow<ConversationUiState>`.
Rendering state is separate from domain state so the dialog can show content that
is not yet persisted:

- `turns` — committed, persisted `Turn`s.
- `provisionalUserText` — live STT shown as *provisional*; never persisted or
  sent until committed.
- `liveAssistantText` — the in-flight streamed delta text, rendered
  incrementally and persisted only at a terminal state.
- `phase` (`TurnPhase`), `notice`, `composerText`, `composerSource`,
  `isLoading` — explicit loading/error/cancel/interrupted affordances.

The voice-only actions `setProvisionalTranscript` / `commitProvisionalTranscript`
are the M06 seam for M21; the manual composer calls the same private
`submitTurn`.

## One turn path for text and voice

`submitTurn(text, source)` is the single entry point. It appends a finalized
`UserTurn` (recording `UserTurnSource.TEXT` or `VOICE`), persists it through
`ConversationRepository.save`, builds the bounded request with the M05
`ModelContextBuilder`, and streams `LanguageModel.stream`. A test drives a
`VOICE`-sourced and a `TEXT`-sourced turn through this same path and asserts both
persist into the same conversation.

## Stream consumption (M12)

M12 refined the LLM contract the state holder consumes but kept this path's
behavior. The holder no longer folds the event stream itself: it calls
`LanguageModel.consume(request, trace)`, which owns ordering, partial text,
usage, and the provider-reported model, then branches on the terminal event. A
private `UiStreamTrace` forwards delta text to the dialog, records only counts
and stable names to the M04 trace, and the terminal event maps to the persisted
turn state exactly as before (`COMPLETED`, `CANCELLED`/`INTERRUPTED`,
`FAILED`/`FAILED`). One behavior was added: a stream that ends **without** a
terminal event — an adapter that cannot say whether the text was whole — is
persisted as `FAILED` with `LLM_MALFORMED_RESPONSE` rather than being treated as
a completed reply. See [llm-contract.md](./llm-contract.md).

## Correction without rewriting

The composer is editable and is persisted exactly as submitted (outer
whitespace is trimmed). Recognized speech is only ever shown as
`provisionalUserText`; committing it uses the user's text, so an STT hypothesis
is never silently rewritten. Assistant deltas are shown as they arrive but are
persisted only when generation ends.

## Truthful streaming and cancellation

- Deltas update `liveAssistantText`; the transcript shows each one immediately.
- On `Completed`, one `AssistantTurn` is persisted with generation/delivery
  `COMPLETED`.
- On `Cancelled`/`Failed`, or when the user cancels or navigates away, the turn
  is persisted with generation `CANCELLED`/`FAILED` and delivery `INTERRUPTED`,
  and only the delivered prefix is stored as delivered. A cancel before any
  output leaves no phantom assistant turn.
- `ModelContextBuilder` therefore only ever sends delivered assistant text.

A "Retry" control re-runs the most recent user turn after removing the failed or
cancelled reply, without duplicating the user message.

## Accessibility

- Every control has a text or content-description label; interactive controls and
  list rows carry stable `ConversationTestTags` for tests.
- Loading rows expose a `contentDescription`; error notices use an assertive
  `liveRegion` and the `error` semantics property.
- The composer receives focus when a conversation opens.
- Deletion is always confirmed before data is removed.

## Tracing

Stage events go through the M04 `TurnTraceFactory`/`TurnTraceRecorder` seam
(persistence, LLM request, deltas, completion, cancellation, failure). Only
counts and stable codes are recorded; transcript, prompt, and credential content
never are.

## Testing

M06 adds JVM tests under `:app:testDebugUnitTest` (no device, network, or
credentials):

- `ConversationViewModelTest` covers text send, correction, incremental
  streaming, cancellation/interruption, failure and retry, conversation
  switching, deletion, process-restored history, the shared text/voice path,
  trace routing, and blank-input rejection.
- `ConversationAppUiTest` runs Compose under **Robolectric** and covers text
  send, correction, streaming render, conversation switching and deletion
  through the history drawer, process-restored history across a state-holder
  restart, accessible loading, the unconfigured-model error state, and composer
  enablement. The drawer is composed while it is closed, so it deliberately
  carries no test tag the main surface also uses.
- `ConversationViewModelTest` additionally covers the M12 stream-consumer
  behavior: a terminal-less stream persists as a failure, a failed partial keeps
  its delivered prefix and typed reason, and usage/end-reason reach the trace
  without content.

## Test dependencies added

- `androidx.compose.ui:ui-test-junit4` (test) — Compose test APIs on the JVM.
- `androidx.compose.ui:ui-test-manifest` (debug) — the ComponentActivity the test
  rule launches.
- `androidx.lifecycle:lifecycle-runtime-compose` — `collectAsStateWithLifecycle`.

Versions come from the Compose BOM and the existing lifecycle version; all three
are recorded in `gradle/libs.versions.toml`.

## Limitations and next steps

- The turn path resolves a real adapter from the persisted M22 selection (M23);
  the honest `LLM_NOT_CONFIGURED` state remains only when nothing is selected.
  The live `/models` catalog is still unwired, so only the documented OpenCode Go
  models are selectable (R-0102, R-0160). See
  [text-first-slice.md](./text-first-slice.md).
- The state holder no longer owns the generation pipeline: M21 moved stream
  consumption, late-event rejection, delivery accounting, and persistence into
  `orchestration.TurnOrchestrator`/`TurnStateMachine`, and this holder only maps
  the terminal result to UI state. See [orchestration.md](./orchestration.md).
- **Session/generation state is serialized (M27, CODE_REVIEW P1/R-0224).** The
  voice-session lifecycle fields and the one-generation slot are written from the
  coordinator's, the orchestrator's, and the UI's coroutines, so they are guarded
  by a single lock and claimed/checked/released together (`claimGeneration`,
  `discardActiveGeneration`, the voice-session token). Each voice callback carries
  its session token through `SessionVoiceListener`, so a late callback from a
  stopped session never updates the dialog for a newer one. A long-lived actor
  would remove the lock entirely; the lock is the smaller, testable step that
  closes the check-and-start and stale-callback races now
  (`VoiceLoopUiIntegrationTest.aStoppedSessionsLateCallbackIsIgnored`,
  `aSecondVoiceStartIsRefusedWhileOneIsActive`).
- No voice: STT/TTS and barge-in arrive in M07–M11 and M21–M24. The provisional
  transcript seam and the single turn path are in place for them.
