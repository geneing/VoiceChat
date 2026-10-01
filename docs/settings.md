# Settings and Capability-Aware Selection (M22)

This document records how the app lets a user configure on-device speech-to-text
(STT), the remote language-model provider, on-device text-to-speech (TTS), and
Smart Turn availability **without ever showing an option the device or the
provider does not support**. It implements the M22 handoff in
[implementation-plan.md](./implementation-plan.md#m22--implement-settings-and-capability-aware-selection),
the [DataStore (Preferences) persistence decision](./decisions.md#1-toolchain-project-identity-and-platforms),
the [provider capability matrix](./decisions.md#4-provider-capability-matrix),
the credential rules in [credentials.md](./credentials.md) (M13), and the
disclosure rules in [privacy-and-security.md](./privacy-and-security.md) and
[llm-providers.md](./llm-providers.md).

M22 is a **settings and selection** milestone. It does not implement a provider
adapter (M14–M19) and it does not report a connection it did not make.

## What is and is not implemented

Implemented:

- A DataStore-backed settings store for the validated selections.
- A capability snapshot read from the live runtime (ML Kit feature status for
  STT modes, platform `getVoices()` for TTS) and the provider capability
  registry for providers/auth/reasoning.
- Capability-aware option building and a validator that drops (and re-saves) any
  stored selection the registry/runtime no longer supports.
- The settings Compose surface and its ViewModel, including credential
  replace/remove through the M13 `CredentialStore`.
- A **dropdown-per-section** surface: the recognizer model, provider, model,
  reasoning level, and TTS voice are single-choice dropdowns, not radio lists.
  Sub-sections that are not applicable (no Authentication choice; no Reasoning
  section when the provider offers none) are absent rather than empty.
- The credential box is a single **API key** entry that disappears once a value
  is stored, replaced by a "Stored for <provider>." line and a Remove action.
- A started, user-approved **STT model download** with a determinate progress bar
  (`SttModelDownloader`, M08), replacing the previous static "Model download
  required" text.
- A **first-run default**: the app opens on OpenCode Go with its free model
  (`longcat-2.5-preview-free`), and Smart Turn enabled, so a fresh install has a
  working configuration the user can change. Nothing is pre-selected that the
  device reports unavailable.
- A transport-free, short-lived, single-use provider authorization session for
  the one provider that documents a browser flow (OpenRouter PKCE).

Not implemented (owned elsewhere):

- The **live** `/models` catalog and per-model reasoning capability (M14–M19).
  The M23 slice feeds the picker a **documented static** list (OpenCode Go's
  dated model table), so a Go model is selectable and validated; other providers
  show no models, and nothing is fabricated (R-0102, R-0160).
- **Catalog state is first-class (M27, R-0102).** The picker no longer shows a
  bare "no models" line: `ModelCatalogState` distinguishes `Loading`,
  `Available`, `Empty`, `Unavailable`, `Stale`, and `Failed`, carries the reason,
  and sets `needsAttention` whenever nothing is selectable. `SettingsScreen`
  renders the reason plus a **Recheck capabilities** action in every
  non-selectable state, so a provider with no wired catalog is never presented as
  configured (`SettingsViewModelTest.anEmptyModelCatalogIsAFirstClassEmptyStateNotAConfiguredProvider`,
  `SettingsScreenUiTest.anEmptyModelCatalogShowsAnExplicitReasonAndARefreshAction`).
  Because there is still no live provider `/models` fetch (R-0102/R-0160), the
  action is labeled as a **device capability recheck**, not a catalog refresh, and
  a one-line note says the provider model list is not connected yet.
- The OpenRouter browser redirect and token exchange. The app refuses honestly
  (`AuthorizationRejection.NOT_IMPLEMENTED`) rather than faking a pairing; the
  session/security invariants are already enforced and tested.
- Smart Turn itself (M10); the toggle is always disabled with a reason.

The persisted selection **is** now consumed by the conversation turn path (M23):
`ConversationViewModel` resolves the adapter and identity from the latest
settings at send time (`ProviderTurnResolver`, `RegisteredProviderLanguageModelFactory`),
and the dialog shows the destination/transfer/retention disclosure before a send.
See [text-first-slice.md](./text-first-slice.md).

## Dependency

DataStore (Preferences) is the M00 settings-persistence decision
([decisions.md §1](./decisions.md#1-toolchain-project-identity-and-platforms)):
settings are typed key/value, which DataStore covers. The artifact is pinned in
`gradle/libs.versions.toml`:

| Artifact | Version | Basis (checked 2026-09-29) |
| --- | --- | --- |
| `androidx.datastore:datastore-preferences` | `1.2.1` | Latest stable on Google Maven; minSdk 21. No alpha/beta is used. |

The settings record **never contains a credential**. `CredentialStore` (M13)
remains the only place a secret is written, and `PreferencesSettingsStore` has no
API that accepts one.

**First-run defaults.** A *pristine* DataStore record (no key of any kind ever
written) is read as `VoiceSettings.firstRunDefaults()`: OpenCode Go with
`longcat-2.5-preview-free`, the default STT locale, and Smart Turn enabled.
Clearing a selection still writes the smart-turn flag, so an explicitly cleared
provider is never re-seeded.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/settings/
  VoiceSettings.kt             the flat, non-secret settings record + locale helper
  SettingsStore.kt             SettingsStore contract + InMemorySettingsStore
  SettingsCapabilities.kt      runtime snapshot, SmartTurnState, capability provider
  SettingsOptions.kt           capability-aware option building (SelectableOption)
  SettingsValidator.kt         sanitizes a stored selection; InvalidSelection reasons
  ProviderAuthorization.kt     short-lived, single-use pairing session + honest default flow
  SettingsState.kt             SettingsUiState, sections, notices, SettingsActions
  PreferencesSettingsStore.kt  the only platform file: DataStore implementation
app/src/main/kotlin/com/voicechat/agent/ui/
  SettingsViewModel.kt         state holder: observe + validate + persist + credential + STT download
  SettingsScreen.kt            the Compose surface
  SttModelDownloader.kt        M08 download seam (MlKitSttModelDownloader / NoOp default)
  AndroidSettingsCapabilityProvider.kt  app-boundary runtime snapshot reader
```

`SettingsSourcePurityTest` enforces that every settings file except
`PreferencesSettingsStore.kt` is platform-free, so the model, option building,
validation, and authorization lifecycle run as ordinary JVM unit tests.

## Capability-aware rules

Two rules are mechanical, not stylistic:

1. **An unsupported capability is never produced.** The auth-method list is
   exactly `ProviderCapabilities.availableAuthMethods`; `AuthMethod` has no QR
   value, so a QR option cannot be shown. Reasoning levels come from the M13
   reconciliation (`LlmCapabilityReconciler.effective` = provider union ∩
   model's `/models` report), so a level a model does not expose is absent.
   Network-required TTS voices are excluded entirely, and so is any voice outside
   the app's supported locales (`OnDeviceVoiceSelector.SUPPORTED_LANGUAGE_TAGS`:
   `en-US`, `en-GB`, `en-AU`, `es-US`, `es-ES`) — the app only offers a voice it
   can also recognize speech for.
2. **A known-but-unavailable entry is shown disabled with a reason.** An
   unprovisioned STT mode, a downloadable/unavailable model, and a Smart Turn
   model that is not installed are rendered with `OptionState.Unavailable` and a
   safe explanation, so the user sees *why*, instead of a silently missing entry
   they needed.

`SettingsValidator` re-checks every stored value against the current registry and
runtime snapshot and clears (and re-saves) anything invalid, recording an
`InvalidSelection` reason. This is the mechanical form of "persist only validated
selections": an unknown provider, a model that is not ready, an undocumented auth
method, an unsupported reasoning level, a non-ready STT mode, or a
missing/network TTS voice is dropped rather than trusted.

**An unread snapshot is not a rejection.** `SettingsCapabilities.isUnread` is
true until the runtime reports once. Validation does not drop a stored selection
against an unread snapshot, so a cold start cannot clear a valid choice before
the STT/TTS/model status has been read.

## Authentication and credentials

The auth methods shown come from the registry (`ProviderCapabilityRegistry`):

- every provider documents API key; OpenRouter additionally documents a browser
  PKCE flow;
- **no provider documents a QR pairing flow**, and `AuthMethod` has no QR value;
- Go/Zen/Hermes auth/reasoning remain marked `unverified` in the registry and are
  not presented as confirmed.

API keys are stored, replaced, and removed through the M13
`CredentialStore` (AndroidKeyStore-backed on device). The settings screen shows
only the redacted `CredentialStatus`; the credential value never enters
`SettingsUiState`, a log line, or DataStore. There is no separate Authentication
picker: the credential kind is resolved from the provider's own registry entry,
so the single API-key field is the whole surface.

### Provider authorization (short-lived, single-use)

`AuthorizationSessionCoordinator` is the transport-free implementation of the
pairing lifecycle for a provider that documents a browser flow. It enforces:

- the method must be documented by the provider;
- the authorization URL must be on the provider's own host
  (`ProviderEndpointPolicy`), so an arbitrary destination is refused;
- a URL that embeds a reusable credential is refused (`PairingQrPolicy`);
- the challenge is short-lived (TTL) and single-use (a second completion is
  `REUSED`, and an expired one is `EXPIRED`).

The network/browser exchange is a provider-adapter concern (M14+). Until it is
wired, `UnimplementedProviderAuthFlow` returns
`AuthorizationRejection.NOT_IMPLEMENTED` — the app does not fabricate a
successful pairing.

### QR

QR pairing is **not implemented for any provider**, because none documents it
(`decisions.md §4`). `PairingQrPolicy` continues to reject every payload: one that
embeds an API key/token/password as `CARRIES_CREDENTIAL`, and any other as
`NOT_A_PAIRING_CHALLENGE`. A QR-sourced URL is never trusted as a destination
(`EndpointSource.QR_PAYLOAD` is refused by `ServerDestinationValidator`).

## Destination and remote-transfer disclosure

**Removed.** The settings surface and the conversation dialog no longer render a
destination/remote-transfer/retention notice card. Configuring a provider and
storing its credential is the app's consent step. The registry still records each
provider's destination, `dataRetentionNote`, and `toolExecutionOnServer` as
provider facts, and the validated destination for a configurable provider
(Hermes) is still enforced before persistence — it is simply not shown as a
banner. See `docs/risks-and-decisions.md` for the recorded decision.

## Smart Turn

Smart Turn is **enabled by default** when the pinned detector is installed.
Because M10's artifact must be downloaded into app-private storage, a device
without it reports `SmartTurnState.DownloadRequired` and the toggle is disabled
with that reason. Clearing the default on such a device is a normal fallback and
is **not** reported as an invalid selection; the validator only records an
`InvalidSelection` when a selection the user actually made can no longer be
honoured.

## Tests

| Concern | Test |
| --- | --- |
| unsupported auth/reasoning/network voice hidden | `settings.SettingsOptionsTest` |
| unavailable model/STT disabled with a reason | `settings.SettingsOptionsTest` |
| invalid stored selection dropped | `settings.SettingsValidatorTest` |
| unread snapshot does not clear a stored selection | `settings.SettingsValidatorTest`, `ui.SettingsViewModelTest` |
| short-lived, single-use pairing; expiry/reuse; arbitrary destination; reusable credential | `settings.ProviderAuthorizationTest` |
| QR is unsupported and unsafe | `settings.ProviderAuthorizationTest`, `providers.PairingQrPolicyTest` |
| DataStore round-trip, restart proxy, first-run defaults, no credential at rest | `settings.PreferencesSettingsStoreTest` |
| platform-free settings core | `settings.SettingsSourcePurityTest` |
| state holder: options, defaults, validation, STT download, credential replace/remove, persistence | `ui.SettingsViewModelTest` |
| Compose: dropdowns, download progress, credential controls, hidden/disabled options | `ui.SettingsScreenUiTest` |
| real AndroidKeyStore + DataStore persistence | `settings.PreferencesSettingsStoreInstrumentedTest` (compiled; device run in [Tests.md](../Tests.md)) |

Verified commands (Windows host, wrapper; results in [Tests.md](../Tests.md)):

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:assembleDebug :app:lintDebug spotlessCheck
.\gradlew.bat :app:assembleDebugAndroidTest
```

## Unresolved

- Device behavior of the runtime capability snapshot (STT status check, TTS
  engine construction) is unmeasured on Pixel 10 (R-0100).
- The OpenRouter browser redirect/token exchange is not implemented; the flow
  refuses honestly (R-0101).
- The live `/models` catalog is not wired, so only the documented OpenCode Go
  models are selectable in the shipped build (R-0102, R-0160).
- DataStore settings backup/transfer behavior is unverified on device (R-0104).
