# Text-First End-to-End Provider Slice (M23)

This document records the M23 vertical slice: the conversation UI reaches
durable history, the bounded M05 context, a **real** external provider adapter,
and a visible streamed result, with the persisted M22 selection and the M13
credential store actually driving the turn. It implements the M23 handoff in
[implementation-plan.md](./implementation-plan.md#m23--complete-text-first-end-to-end-provider-vertical-slice)
over the M05 persistence, M06 UI, M12 contract, M13 credentials/registry, M17
OpenCode Go adapter, and M21 orchestration.

OpenCode Go is the initial integration path, as the plan directs. The UI never
names a provider: it asks a registry-driven factory for the adapter that matches
the persisted selection.

## What is and is not implemented

Implemented:

- A registry-driven `ProviderLanguageModelFactory` that builds every known
  provider's adapter from the persisted `ProviderModelSelection` and the M13
  `CredentialStore`, with a conversation-scoped session hint.
- The M22 selection drives the turn: the `ConversationViewModel` resolves the
  adapter, identity, and reasoning from the latest persisted settings at send
  time instead of using a fixed provider (R-0103).
- The honest `NotConfiguredLanguageModel`/`LLM_NOT_CONFIGURED` state remains
  only when nothing is selected (or the selected provider has no adapter),
  never as a fake success (R-0012).
- The dialog discloses the selected provider/model, the validated destination,
  the remote text/context-transfer notice, and the provider's retention/training
  note where the registry records one — before/at send (R-0097, R-0139).
- A documented, static model list for OpenCode Go so a selection is real without
  claiming the live `/models` surface is wired (R-0102 stays open).
- Automated tests proving the full lifecycle, cancellation, retry, a typed
  provider failure, selection-driven adapter switching, the bounded context, and
  a content-free trace — all credential-free and network-free.
- An opt-in, credential-free-in-CI smoke test and a documented Pixel 10 manual
  procedure (the device run was **not** performed for M23).

Not implemented (owned elsewhere):

- The live per-provider `/models` catalog and per-model reasoning capability
  (R-0102). The picker uses the documented static list; other providers show no
  models yet.
- The Anthropic Messages `max_tokens` bound (R-0133) is unchanged: a fixed 4096
  default, still not a Go-verified figure.
- Voice. This is the text-first slice; STT/TTS/barge-in are M24.

## Wiring

```
MainActivity
  -> PreferencesSettingsStore (M22) ─┬─ settingsViewModelFactory (M22 screen)
  -> ProviderCapabilityRegistry (M13)│
  -> AndroidKeystoreCredentialStore ─┘
  -> RemoteTransport(OkHttpStreamingEngine) (M14)
  -> RegisteredProviderLanguageModelFactory (M23)
        │
        ├─ VoiceAgentRoot(settingsFlow, providerRegistry, providerFactory)
        │      └─ ConversationViewModel
        │             ├─ observes settingsFlow -> ProviderDisclosure (dialog banner)
        │             └─ at send: ProviderTurnResolver.resolve(settings, conversationId, registry, factory)
        │                      -> ActiveProviderTurn(selection, reasoning, adapter)
        │                      -> TurnOrchestrator.run(request, observer, languageModel = adapter)
        └─ SettingsScreen (M22)
```

The settings store, registry, credential store, and transport are built **once**
in `MainActivity` and shared, so the settings screen and the turn path cannot
diverge.

### Files

```
app/src/main/kotlin/com/voicechat/agent/providers/
  ProviderLanguageModelFactory.kt   registry-driven factory (all six adapters)
  ProviderDisclosure.kt             display value: provider/model/destination/retention
  DocumentedModelCatalog.kt         documented static model ids (OpenCode Go)
app/src/main/kotlin/com/voicechat/agent/ui/
  ProviderTurnContext.kt            ActiveProviderTurn + ProviderTurnResolver
app/src/test/kotlin/com/voicechat/agent/
  providers/ProviderLanguageModelFactoryTest.kt
  ui/TextFirstSliceTest.kt          full lifecycle, cancel, retry, failure, bounds, trace
  ui/TextFirstSliceUiTest.kt        Compose disclosure + visible result (Robolectric)
  ui/TextFirstSliceSmokeTest.kt     opt-in real-provider smoke (not run)
```

Modified: `providers/ProviderCapabilities.kt` (+`dataRetentionNote`),
`providers/ProviderCapabilityRegistry.kt` (retention notes),
`orchestration/TurnOrchestrator.kt` (`run(..., languageModel = …)` override),
`settings/SettingsState.kt` (+`retentionNotice`), `ui/ConversationViewModel.kt`,
`ui/ConversationUiState.kt`, `ui/ConversationApp.kt`, `ui/SettingsScreen.kt`,
`ui/SettingsViewModel.kt`, `ui/VoiceAgentRoot.kt`,
`ui/AndroidSettingsCapabilityProvider.kt`,
`MainActivity.kt`, `res/values/strings.xml`.

## Provider factory and selection

`RegisteredProviderLanguageModelFactory` resolves the registry row for the
selected provider, validates its destination (`ProviderEndpointPolicy`), and
constructs that provider's adapter. A provider with no registry row, or a
configurable provider with no valid destination, returns `null`; the resolver
then keeps the honest not-configured state instead of guessing.

`ProviderTurnResolver.resolve` is a pure function of the persisted settings and
the conversation id:

- no provider/model selected, or no adapter -> `NotConfiguredLanguageModel`
  (`LLM_NOT_CONFIGURED`, not retryable);
- otherwise the real adapter and the exact `ProviderModelSelection` +
  reasoning the turn must use.

`TurnOrchestrator.run` gained an optional `languageModel` parameter (defaulting
to the constructor model), so the runtime uses the per-turn adapter while the
M21 state machine, persistence, TTS, and tracing are unchanged. The existing M06
and M21 tests keep the fixed path and still pass.

### Conversation-scoped session hint (R-0132)

The OpenCode Go request carries `x-opencode-session: voicechat-<conversationId>`
(the app-local conversation id), so Go's documented routing/prompt-caching hint
is conversation-scoped rather than per-adapter-instance. Hermes receives the
conversation id as its optional session id. The id is random and app-local and
carries no user content.

### Anthropic Messages `max_tokens` (R-0133)

The M12 request contract still carries no `max_tokens`; the Anthropic Messages
family sends a bounded fixed `4096` default. The limit stays a documented
implementation default, not a Go-verified figure, and the risk remains tracked.

## Disclosure before send

`ProviderDisclosure.from(settings, registry)` derives:

- the selected provider's display name and model id;
- the validated destination (`ServerDestination.disclosure()`), the same value
  the M22 settings screen shows;
- the remote text/context-transfer notice (reused verbatim from the shared
  string resource);
- the provider's `dataRetentionNote` where the registry records one
  (OpenCode Go/Zen/OpenRouter/OpenAI), and the Hermes server-side-tools notice.

The conversation dialog renders this banner above the transcript before any
send, and the M22 settings screen renders the same strings and retention note,
so the wording has one source of truth.

## Bounded context

The request is built by the M05 `ModelContextBuilder` inside `TurnOrchestrator`,
unchanged. Only final user transcripts and actually delivered assistant text are
eligible, at most 20 messages / 4000 characters. The full stored conversation is
never sent; `TextFirstSliceTest.theRequestUsesTheBoundedContextNotTheWholeStoredHistory`
proves it with a 30-turn history.

## Privacy-safe tracing

The M04 recorder is driven by the orchestrator exactly as in M21: provider/model
identity, message/character counts, delta indices and lengths, usage counts, and
stable state/reason names. Prompt, transcript, delta, and credential content
never reach it.
`TextFirstSliceTest.theWholeTurnTraceIsPrivacySafe` asserts a single trace with
no prompt or reply content, over a real adapter run.

## Testing

Credential-free and network-free JVM tests under `:app:testDebugUnitTest`:

- `providers.ProviderLanguageModelFactoryTest` — OpenCode Go adapter + the
  conversation-scoped session header; every known provider resolves; Hermes
  needs a validated destination; an unknown provider has no adapter; the
  disclosure names provider/model/destination/retention and is `NONE` until
  both a provider and a model are selected.
- `ui.TextFirstSliceTest` — full lifecycle over the real OpenCode Go adapter and
  a recorded SSE fixture; incremental deltas; cancellation as an interrupted
  turn; retry after a failure; a typed `LLM_RATE_LIMITED` failure that persists
  no phantom turn; the persisted selection changes the adapter and endpoint;
  nothing selected stays `LLM_NOT_CONFIGURED`; the bounded context; a
  content-free trace.
- `ui.TextFirstSliceUiTest` (Robolectric) — the dialog shows the provider/model,
  the remote-transfer notice, and the retention note before send; the honest
  not-configured hint; a fixture-backed send renders and persists the reply.

The opt-in real-provider smoke, and the Pixel 10 manual procedure, are documented
in [Tests.md](../Tests.md) § M23 and marked **not run**.

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck
.\gradlew.bat :app:assembleDebugAndroidTest
```

## Unresolved

- The live `/models` catalog and per-model reasoning capability (R-0102).
- The Pixel 10 run with a real credential/network/Keystore store (R-0161).
- Sending the app-local conversation id to the provider as a session hint
  (R-0162).
- The retention/training disclosure is a dated, provider-level summary, not a
  live per-model policy (R-0163).
- Per-turn adapter construction and transport lifecycle (R-0164).
- No explicit per-send consent gate: the disclosure is informational (R-0165).
