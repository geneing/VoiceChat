# Local Model Runtimes (M20)

This document records the verified state of the app's **on-device** language-model
support, the curated model catalog, the discovery/availability rules, the
app-managed lifecycle, and the explicit local-versus-remote selection. It is the
companion to [model-runtime.md](./model-runtime.md) (policy) and
[llm-contract.md](./llm-contract.md) (the streaming contract every local adapter
implements).

The M20 implementation is deliberately honest about what is **not** verified: the
catalog is empty, the AICore Prompt API is beta, and neither runtime has been run
on a Pixel 10 in this milestone. Those facts are recorded below and in
[risks-and-decisions.md](./risks-and-decisions.md) (R-0210–R-0219).

## Scope

- Discover eligible **system-managed** AICore / ML Kit GenAI language capability
  through the API's own status surface.
- Provide an **app-managed** LiteRT-LM `.litertlm` path behind the same
  `LanguageModel` contract as the remote adapters.
- Curate an **allow-listed** catalog with explicit metadata; an empty catalog is
  a valid result.
- Report typed availability for ready, download-required, unprovisioned,
  missing/corrupt, and insufficient-resource states.
- Keep local and remote selection **explicit** and never silently fall back.

## Runtime discovery

### AICore / Gemini Nano — ML Kit GenAI Prompt API

Verified on **2026-09-29**:

- Artifact `com.google.mlkit:genai-prompt:1.0.0-beta4` — the current
  `latest`/`release` on Google Maven (`maven-metadata.xml` last updated
  2026-07-21). It resolves for this toolchain and is added to `app/build.gradle.kts`.
  Source: `https://dl.google.com/dl/android/maven2/com/google/mlkit/genai-prompt/maven-metadata.xml`.
- API: `Generation.getClient()` returns a `GenerativeModel`; `checkStatus()`
  returns an ML Kit `FeatureStatus` (`UNAVAILABLE`, `DOWNLOADABLE`, `DOWNLOADING`,
  `AVAILABLE`); `generateContentStream(prompt)` streams response chunks. The API
  requires API 26+ (app `minSdk` is 31).
  Sources: [Get started](https://developers.google.com/ml-kit/genai/prompt/android/get-started)
  (page updated 2026-09-08), [Prompt API overview](https://developers.google.com/ml-kit/genai/prompt/android)
  (updated 2026-07-15), [reference](https://developers.google.com/android/reference/kotlin/com/google/mlkit/genai/prompt/package-summary).
- The API is **beta**, with no SLA or deprecation policy and possible
  backward-incompatible changes (R-0210). The registry entry claims no reasoning
  support for this path.

`MlKitGenAiPromptProbe` reads `checkStatus()` and maps it to a `LocalProbeResult`
(`Ready` / `DownloadRequired` / `Provisioning` / `Unavailable` / `Failed`).
`MlKitPromptGenerator` streams text through the same API.

**AICore is system-managed.** The app never downloads, copies, inspects, or
deletes its model files, and this milestone does **not** call the API's
`download()`. `DOWNLOADABLE` is therefore reported as an explicit *unprovisioned*
state (provisioning happens through the device/AICore, outside the app), not as
an app action (R-0211).

### LiteRT-LM — app-managed `.litertlm`

Verified on **2026-09-29**:

- Artifact `com.google.ai.edge.litertlm:litertlm-android:0.17.1` — the current
  `latest`/`release` on Google Maven (`maven-metadata.xml` last updated
  2026-09-16). The M00 decision note described v0.16.0 from the guide; the
  published release is now 0.17.1 and is what is pinned and compiled against
  (R-0212). Source: `https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml`.
- The AAR is **self-contained**: its POM depends only on `com.google.code.gson:gson:2.14.0`,
  `org.jetbrains.kotlin:kotlin-reflect:2.4.0`, and
  `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0`. It does not depend on
  the base `litert` artifact.
- API (compiled against): `Engine(EngineConfig(modelPath = …))`, `engine.initialize()`
  (documented as up to ~10 s; run off the main thread), `engine.createConversation()`,
  `conversation.sendMessageAsync(prompt): Flow<Message>`, and
  `message.contents.contents.filterIsInstance<Content.Text>()`. Engine and
  conversation are closed together.
  Source: [LiteRT-LM on Android](https://ai.google.dev/edge/litert-lm/android)
  (page updated 2026-09-04).

`EngineLiteRtLmSessionFactory` is the only file that imports the LiteRT-LM
package; `LiteRtLmLanguageModel` is tested against a fake `LiteRtLmSessionFactory`
on the JVM.

### Base LiteRT — declared but not wired

`com.google.ai.edge.litert:litert:2.2.0` is resolvable (Google Maven `latest`
2.2.0, published 2026-08-13) and its coordinate is recorded in
`gradle/libs.versions.toml`. It is **not** added to `app/build.gradle.kts`:
`litert:2.2.0` depends on `litert-api:2.2.0`, and both AARs declare the same
manifest namespace (`com.google.ai.edge.litert`), which fails AGP 9 manifest
merging. Nothing in M20 uses the LiteRT Java API directly, so the dependency is
omitted rather than shipped with a broken transitive (R-0216).

## The curated catalog (currently empty)

`LocalModelCatalog.allowListed` is **empty**, and
`LocalModelCatalog.status` reports `NoAllowListedModel` with the reason:

> No `.litertlm` bundle has a verified publisher, license, pinned runtime
> version, resource figures, and SHA-256 for this toolchain.

This is a deliberate, valid outcome: [model-runtime.md](./model-runtime.md)
requires every one of those fields before an artifact may be exposed, and no
`.litertlm` bundle has been verified to that bar. Adding a model later is a
reviewed catalog entry plus a record here — never a user-supplied URL or file
path.

`LocalModelCatalogValidator` is the gate. An entry is admitted only when all of
these are present and plausible; otherwise it is `Rejected` with the failing
field names:

| Field | Requirement |
| --- | --- |
| Identity | non-blank display name; task `LANGUAGE_MODEL`; runtime `LITERT_LM` |
| Provenance | publisher and a stable source URL |
| License | license identifier and a license URL |
| Runtime | the exact LiteRT-LM runtime version validated |
| Resources | `downloadBytes`, `storageBytes`, `peakMemoryBytes` all `> 0` |
| Device fit | `minAndroidApi` in a sane range; optional tested device/notes |
| Integrity | lowercase-hex SHA-256 (64 chars) |

`entries()` returns only admitted artifacts, and an id not on the allow-list
resolves to nothing (never an adapter).

## Availability states

`LocalAvailabilityMapper` is the only place a state becomes availability. Exactly
one state is usable: `Ready`. Every other state is an explicit typed
`LocalModelAvailability.Unavailable` (with `LocalModelState`, `UnavailableReason`,
and a `VoiceAgentError`).

| Runtime signal | `LocalModelState` | Contract result |
| --- | --- | --- |
| Probe `Ready`, install `Installed` | `READY` | `ModelAvailability.Ready` |
| Probe `DownloadRequired`, app-managed | `DOWNLOAD_REQUIRED` | `ModelAvailability.DownloadRequired` |
| Probe `DownloadRequired`, system-managed (AICore) | `UNPROVISIONED` | `Unavailable` |
| Probe `Provisioning`, install `Downloading` | `PROVISIONING` | `Unavailable` |
| install `IntegrityFailed` | `CORRUPT` | `Unavailable` (`MODEL_CORRUPT`) |
| Missing files | `MISSING` | `Unavailable` |
| Insufficient device resources | `INSUFFICIENT_RESOURCES` | `Unavailable` |
| Device/feature unsupported | `DEVICE_UNSUPPORTED` | `Unavailable` |
| Not on the allow-list | `NOT_ALLOW_LISTED` | `Unavailable` |
| Status check failed | `UNKNOWN` | `Unavailable` |

A missing, corrupt, unprovisioned, or failed model is **never** reported ready.
`LocalModelAvailabilityProvider` exposes AICore plus each allow-listed entry
through the M02 `ModelAvailabilityProvider` contract.

## App-managed lifecycle

`.litertlm` bundles are never packaged in the APK and are never read from shared
storage:

1. **App-private storage.** `AndroidLocalModelFileStore` uses
   `filesDir/local-models`; it needs no storage permission and is removed with the
   app. File names are derived from the allow-listed id, and path traversal is
   refused.
2. **Bounded.** `LocalModelInstaller.install` refuses bytes larger than the
   artifact's declared `downloadBytes`.
3. **Integrity before load.** SHA-256 is computed and compared to the catalog
   checksum; a mismatch deletes the partial file and reports `IntegrityFailed`.
   `LiteRtLmLanguageModel` re-checks the installed artifact before opening a
   session, so a corrupt file is a typed failure, never a successful init.
4. **Atomic install.** Bytes are written to `<id>.litertlm.partial` and renamed
   into place only after verification.
5. **Partial cleanup.** `LocalModelInstaller.cleanupPartial()` removes leftover
   `.partial` files from an interrupted install.
6. **Removable.** `LocalModelInstaller.remove` deletes the installed bundle and
   any partial file.

The network **download** step itself is not implemented in M20: the installer
consumes already-fetched bytes so the bounded/verified/atomic/removable rules are
unit tested without a network. Fetching, progress, and retry are tracked as
R-0214.

## Local versus remote selection

- `VoiceSettings.llmLocalModelId` is a single flat, non-sensitive id (or `null`
  for the remote path). `VoiceSettings.llmSelection` returns `null` whenever a
  local model is selected, so the two backends are mutually exclusive at the read
  site.
- `LocalModelSelection.selectLocal` clears the remote provider/model/auth/reasoning
  fields when a local model is chosen.
- `ProviderTurnResolver.resolve` takes an optional `LocalLanguageModelFactory`.
  A local selection resolves only to a local adapter; if the local adapter cannot
  be built (not allow-listed, not installed, unavailable), the result is the
  honest not-configured state — the remote factory is **not** consulted. A remote
  selection never consults the local factory.
- `LocalModelOptions` labels every local option with `(on-device)` so the choice
  is visible alongside remote providers.

Wiring the local option list and its select/clear actions into the M22 settings
screen is intentionally left to the settings work (a concurrent milestone is
editing those files); the selection path, factory, options builder, and resolver
are complete and tested, and the settings integration is tracked as R-0219.

## Tests

JVM (no device, no network, no native runtime):

- `local.LocalModelCatalogTest` — empty catalog is honest; admitted entries;
  missing metadata and bad checksum rejection.
- `local.LocalAvailabilityMappingTest` — every state maps to the right typed
  result; only `Ready` is ready; AICore `Downloadable` is not an app download.
- `local.LocalModelStoreTest` — bounded install, checksum verification, atomic
  install, partial cleanup, removal, SHA-256 vectors.
- `local.LocalLanguageModelTest` — request→stream mapping and typed errors for
  AICore and LiteRT-LM through fakes; corrupt/missing/uninstalled paths; partial
  text preserved.
- `local.LocalVsRemoteSelectionTest` — explicit selection, no silent crossover in
  either direction, on-device labels.
- `local.LocalSourcePurityTest` — only the three vendor/platform files import
  `android.*` / `com.google.*`.

Device (scaffolded, **not run**): `local.LocalRuntimeInstrumentedTest` reports the
real AICore availability and (when present) attempts a local init, recording load
time and heap delta. It uses early returns, never `Assume`. The manual Pixel 10
measurements are in [Tests.md](../Tests.md).

## Verification status

- JVM unit tests and the debug build pass (see the M20 section of Tests.md for the
  exact command).
- The Prompt API and LiteRT-LM artifacts **compile** against the pinned versions.
- **No device run was performed.** AICore availability/provisioning, LiteRT-LM
  load time, memory, thermal behavior, and contention with STT/TTS are unmeasured
  (R-0215). Nothing here is a performance claim.

## Sources and access date

All coordinates and API details above were verified on **2026-09-29**:

- ML Kit GenAI Prompt API (guide/reference):
  `https://developers.google.com/ml-kit/genai/prompt/android`,
  `https://developers.google.com/ml-kit/genai/prompt/android/get-started`,
  `https://developers.google.com/android/reference/kotlin/com/google/mlkit/genai/prompt/package-summary`.
- LiteRT-LM on Android: `https://ai.google.dev/edge/litert-lm/android`.
- Google Maven metadata:
  `https://dl.google.com/dl/android/maven2/com/google/mlkit/genai-prompt/maven-metadata.xml`,
  `https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/maven-metadata.xml`,
  `https://dl.google.com/dl/android/maven2/com/google/ai/edge/litert/litert/maven-metadata.xml`.
