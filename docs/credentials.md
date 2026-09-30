# Credential Storage and Provider Capability Registry (M13)

This document records how the app stores a user's provider credential, how it
exposes per-provider and per-model capabilities, and how it validates a
configurable server destination. It implements the credential requirements in
[privacy-and-security.md](./privacy-and-security.md) and
[llm-providers.md](./llm-providers.md), and the M00 decisions in
[decisions.md](./decisions.md) §1 (credential storage) and §4 (provider matrix).
It is deliberately narrower than a provider integration: the real HTTP adapters
are M14–M19, and the settings UI is M22.

No provider is wired by M13: the app still runs `NotConfiguredLanguageModel`
(risk R-0012). M13 adds the storage and the capability metadata those milestones
consume.

## Package layout

```
app/src/main/kotlin/com/voicechat/agent/credentials/
  Credential.kt                     CredentialKind, Credential, CredentialStatus,
                                    CredentialStoreOutcome, CredentialStore contract
  CredentialCipher.kt               CredentialCipher + CredentialCipherException
  CredentialBlobStore.kt            keyed byte store for ciphertext
  EncryptedCredentialStore.kt       platform-free store logic
  InMemoryCredentialStore.kt        JVM/process-local implementation (tests, tooling)
  AndroidKeystoreCredentialStore.kt the only platform-bound file: AndroidKeyStore
                                    cipher + SharedPreferences blob store + factory
app/src/main/kotlin/com/voicechat/agent/providers/
  ProviderCapabilityTypes.kt        AuthMethod, CredentialValidationSupport,
                                    ModelDiscovery, UnverifiedCapability
  ProviderCapabilities.kt           ProviderAuth / ProviderTransport /
                                    ProviderModelAccess / ProviderCapabilities,
                                    KnownProviders
  ProviderCapabilityRegistry.kt     the verified M00 matrix
  ModelCapabilities.kt              per-model catalog + LlmCapabilityReconciler
  ServerDestination.kt              destination + validation + provider policy
  PairingQrPolicy.kt                QR payload inspection
  CredentialValidation.kt           optional minimal-check policy + coordinator
  ProviderConnectionState.kt        capability + credential -> ConnectionState
```

`CredentialSourcePurityTest` fails the build if any file except
`AndroidKeystoreCredentialStore.kt` imports `android.*` / `androidx.*`, so the
whole registry and the store logic run as ordinary JVM tests.

## The credential contract

`CredentialStore` stores at most one credential per provider:

```kotlin
suspend fun status(providerId): CredentialStatus      // redacted; never the secret
suspend fun store(credential): CredentialStoreOutcome // stores or replaces
suspend fun remove(providerId): CredentialStoreOutcome
suspend fun load(providerId): Credential?             // adapter-only; returns the secret
```

- `CredentialKind` is `API_KEY` or `OAUTH_ACCESS_TOKEN`. **There is no password
  kind**: the app never collects or persists a provider account password
  (`AGENTS.md`, `privacy-and-security.md`).
- `Credential.toString()` is overridden to render
  `secret=[redacted]`, so an accidental interpolation cannot leak the value.
- `CredentialStatus` (the only value UI/diagnostics see) never carries the
  secret or anything derived from it. `NotStored` is the honest state for "no
  credential" **and** for a stored blob whose KeyStore key is gone.
- `CredentialStoreOutcome` distinguishes `Success`, `NotStored` (a remove that
  found nothing), and a typed `Failed(CREDENTIAL_STORAGE_FAILED)`. There is no
  success-shaped fallback.
- `load` is the only method that returns the secret; only a provider adapter
  (about to set an auth header) may call it.

### Store, replace, remove

Replacing is `store` on the same provider: the new ciphertext overwrites the
old blob. `remove` deletes it and returns `NotStored` when there was nothing to
delete. All methods are `suspend` and perform their blocking work on
`Dispatchers.IO`, so nothing runs on the main thread.

## On-device protection (AndroidKeyStore)

`AndroidKeystoreCredentialStore.create(context)` wires:

- **`AndroidKeystoreCipher`** — an AES-256 key generated in the AndroidKeyStore
  (alias `voicechat.credentials.v1`) and never exportable, used with
  `AES/GCM/NoPadding`. The IV is random per encryption and stored with the
  ciphertext, so equal secrets produce different blobs. This is exactly the
  AndroidKeyStore-via-`javax.crypto` route the M00 decision mandates; the
  deprecated `androidx.security:security-crypto` helpers
  (`EncryptedSharedPreferences`, `MasterKey`, `EncryptedFile`) are **not** used.
- **`SharedPreferencesCredentialBlobStore`** — an app-private
  `SharedPreferences` file (`voicechat-credentials`) holding Base64 of the
  **ciphertext only**. Writes use `commit()` so the value is durable before
  `store` returns.

The plaintext secret is therefore never written to disk, preferences, logs, or
backup. `status`/`load` decrypt the blob; if the KeyStore key is missing or
invalidated (for example after a device restore), the blob cannot be decrypted
and the store honestly reports `NotStored`/`null` — the user re-enters the key.
The undecryptable blob is left in place rather than silently deleted.

### Backup policy

Credentials must not leave the device. The app already sets
`android:allowBackup="false"` and `res/xml/data_extraction_rules.xml` excludes
the `sharedpref`, `database`, `file`, `root`, and `external` domains for both
cloud backup and device transfer. AndroidKeyStore keys are not backed up at all,
so even a copied preferences file is useless without the origin device's key —
which is what makes the "re-enter after restore" state correct rather than a
recovery gap.

## Provider capability registry

`ProviderCapabilityRegistry.verifiedDefaults()` encodes the M00 matrix
([decisions.md §4](./decisions.md#4-provider-capability-matrix)) as typed data,
split so that **auth is separate from transport**:

- `ProviderAuth` — the documented `AuthMethod` set, the `CredentialKind` a
  provider uses, and whether it documents a minimal credential check.
- `ProviderTransport` — the public base URL (or `null` for a configurable
  server), whether the destination is configurable, the pinned host, and whether
  it streams.
- `ProviderModelAccess` — model discovery, the provider-level reasoning union,
  and usage reporting.
- `toolExecutionOnServer` — true only for Hermes, which runs tools on the server
  host.

`AuthMethod` is a closed enum (`API_KEY`, `OAUTH_PKCE`); it has **no QR value**,
so an undocumented method cannot be shown by accident. Only OpenRouter is listed
with `OAUTH_PKCE`, matching its documented PKCE flow; every other provider is
API-key only. Where the M00 docs were silent the field holds the
"claim nothing" value and the capability is listed in `unverified` (for example
OpenCode Go/Zen reasoning plus Go/Zen usage, Hermes reasoning), so settings never
present an unverified option as fact. M18 verified OpenCode Zen API-key bearer
auth, so that capability is no longer marked unverified (see
[opencode-zen-adapter.md](./opencode-zen-adapter.md)).

`ProviderCapabilities.toLlmCapabilities()` maps the provider entry onto the M12
[`LlmCapabilities`](./llm-contract.md) seam, so registry data and the adapter's
declared capabilities share one vocabulary.

### Per-model capability source (resolves R-0065)

`LlmCapabilities` is per adapter; which reasoning levels a specific model exposes
comes from the provider's `/models` surface. `ModelCapabilities` /
`ModelCapabilityCatalog` model that source, and `LlmCapabilityReconciler` takes
the **intersection** of the provider union and the model entry:

```kotlin
val effective = LlmCapabilityReconciler.effective(provider, model)
val validation = LlmCapabilityReconciler.validate(request, provider, model)
```

When the model is unknown, no reasoning level is claimed (a request may still ask
for `ReasoningLevel.NONE`, which every provider accepts), so an unsupported
option is hidden rather than sent. `StaticModelCapabilityCatalog` is the
deterministic placeholder; the live `/models` parsing belongs to the provider
adapters (M14+) and the settings UI (M22).

## Destination validation and secure transport

`ServerDestinationValidator.validate(raw, source, requireTlsForRemote)` enforces:

- only `http` / `https`;
- a URL with embedded credentials (`user:pass@host`) is refused;
- **TLS is required for any non-local host**; plain `http` is accepted only for
  a loopback address (`localhost`, `127.0.0.0/8`, `::1`);
- a QR-sourced value (`EndpointSource.QR_PAYLOAD`) is never accepted as an
  endpoint.

`ProviderEndpointPolicy` applies those rules per provider:

- fixed providers must match their documented host — a substituted host is
  refused, so a request cannot be redirected with a valid-looking URL;
- the configurable Hermes server is validated by the rules above.

Every accepted destination returns a `ServerDestination` whose `disclosure()`
(e.g. `https://hermes.example.com/v1`) is what the UI shows before text leaves
the device.

## QR payloads

`PairingQrPolicy.inspect` rejects every QR payload: no provider documents a QR
pairing flow ([decisions.md §4](./decisions.md#4-provider-capability-matrix)), and

- a payload that embeds an API key / access token / refresh token / password /
  bearer token is rejected as `CARRIES_CREDENTIAL`;
- any other payload is rejected as `NOT_A_PAIRING_CHALLENGE`;
- a QR URL is never trusted as an endpoint (see above).

A QR code is only ever a transport for a provider-authorized, short-lived
challenge. `PairingQrPolicy` never accepts a reusable credential.

## Optional minimal credential validation

`CredentialValidationCoordinator` refuses to validate a provider whose registry
entry declares `CredentialValidationSupport.NONE`, returning
`CredentialValidationResult.Unsupported` without calling a validator. Where a
provider documents a `GET /models` check, a provider adapter (M14+) implements
`CredentialValidator`; an authentication failure must return
`CredentialValidationResult.authenticationRejected()`
(`LLM_AUTHENTICATION_FAILED`), and a transient failure keeps its typed code.
No validator logs or echoes the credential.

## Redaction and privacy

- The store never logs a secret; it logs the provider id, the kind, and the
  exception **type** only.
- `Credential.toString()` redacts; `Redaction.redactHeaders` redacts
  credential-bearing header names for crash metadata.
- `CredentialRedactionTest` proves a secret never reaches the developer log, a
  `toString`, a store error, or a crash-metadata header map.
- `RepositorySecretScanTest` scans the shipped source, `res`, build files, docs,
  and CI config for credential shapes and finds none; test sources are excluded
  because tests use obviously-fake keys. It also asserts no packaged
  `.jks`/`.keystore`/`google-services.json`.

## Tests

| Concern | Test |
| --- | --- |
| store / replace / remove / status | `credentials.CredentialStoreTest` |
| process restart (fresh instance over the same disk) | `CredentialStoreTest.storedValueSurvivesAProcessRestartOnDisk`, `aFreshStoreInstanceOverTheSameBlobsReadsTheStoredValue` |
| plaintext never at rest | `CredentialStoreTest.plaintextIsNeverWrittenToTheBlobStore` |
| store failure and lost KeyStore key | `CredentialStoreTest.anEncryptFailureIsATypedStoreFailure`, `aLostKeystoreKeyIsReportedAsNotStoredAndLoadReturnsNull` |
| log / crash / toString redaction | `credentials.CredentialRedactionTest` |
| platform-free core | `credentials.CredentialSourcePurityTest` |
| unsupported-method hiding | `providers.ProviderCapabilityRegistryTest.onlyDocumentedAuthMethodsAreExposed` |
| per-model capability reconciliation | `providers.ModelCapabilityReconciliationTest` |
| endpoint validation and TLS rules | `providers.EndpointValidationTest` |
| QR payload rules | `providers.PairingQrPolicyTest` |
| optional credential validation + auth error | `providers.CredentialValidationTest` |
| no bundled secret | `security.RepositorySecretScanTest` |
| real AndroidKeyStore store/replace/remove + restart instance | `credentials.AndroidKeystoreCredentialStoreInstrumentedTest` (compiled; device run in [Tests.md](../Tests.md)) |

The instrumented test is **compile-only** in CI and was not run on a device for
this milestone; see [Tests.md](../Tests.md) § M13.
