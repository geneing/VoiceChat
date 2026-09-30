# Release Checklist and Operational Notes (M26)

This is the operational counterpart to the [implementation plan](./implementation-plan.md)
M26 handoff and the [risks and open decisions](./risks-and-decisions.md) tracker.
It records first-run setup, provider and model configuration, supported devices
and limitations, the developer commands, and the release-gate/audit status.

**No feature is described here as supported unless its implementation and device
or API validation both exist.** The device matrix in [Tests.md](../Tests.md) is
authoritative for device rows; a row that is not filled in is not passed.

## Verified on this machine (2026-09-30)

| Check | Command | Result |
| --- | --- | --- |
| JVM unit tests | `.\gradlew.bat :app:testDebugUnitTest` | **passed** |
| Debug build | `.\gradlew.bat :app:assembleDebug` | **passed** |
| Android lint | `.\gradlew.bat :app:lintDebug` | **passed** |
| Formatting | `.\gradlew.bat spotlessCheck` | **passed** |

Not run here: `:app:connectedDebugAndroidTest`, the Pixel 10 matrix, any live
provider smoke test, and the backup/restore or device-to-device transfer checks.
Those remain open (R-0044, R-0071, R-0104, R-0161).

## First-run setup

1. Build and install the debug APK (`.\gradlew.bat :app:installDebug` on a
   connected Pixel 10).
2. The app declares `INTERNET` (install-time) and `RECORD_AUDIO`, which is
   requested at the point of first capture, not at launch.
3. Nothing is configured by default. Open **Settings** and choose an LLM
   provider and one of the models that provider offers; the app never
   pre-selects a provider, model, or credential.
4. Enter the provider API key in Settings. It is stored only in the
   AndroidKeyStore-backed `CredentialStore` and is never a settings field, a log
   line, or a bundled value.
5. Choose an on-device STT mode and (where the device offers one) a TTS voice.

For developer/device runs a key can be pre-loaded without retyping it into the
UI: `scripts/push-credentials.sh` copies a gitignored `secrets/` file to
app-private storage and the debug-only application imports it (see
[credentials.md](./credentials.md)).

## Provider configuration

- Settings show only the provider's documented auth methods and the
  registry-offered models and reasoning levels; an unsupported option is absent
  rather than shown and rejected (`docs/settings.md`).
- The dialog and Settings both show the validated destination, the remote
  text/context-transfer notice, and the provider retention/training note before a
  request is sent (R-0097, R-0139, R-0163).
- The selected provider/model is never silently switched or substituted; a
  provider-reported model that differs from the selection is recorded, not
  accepted (R-0023).
- Hermes requires an explicit, TLS-validated server URL and discloses that its
  tools run on the server host (R-0019, R-0048).
- **Known gap:** the live per-provider `/models` catalog is not wired, so the
  selectable model list is the documented static Go table (R-0102, R-0160). A
  model whose terms or availability changed is not reflected until the registry
  is re-verified.

## On-device model availability

- **STT** — ML Kit GenAI Speech Recognition, gated at runtime by
  `checkStatus()`. `ADVANCED` is Pixel 10/11; `BASIC` needs API 31+. An
  `UNAVAILABLE`/`DOWNLOADABLE` status is reported, never treated as ready, and a
  persisted mode that is not ready is not silently replaced (R-0001–R-0003,
  R-0181).
- **TTS** — platform `TextToSpeech` restricted to embedded
  (`isNetworkConnectionRequired == false`) voices. A device with no usable
  embedded voice surfaces a typed text-only state instead of silently dropping
  the voice stage (R-0004, R-0180).
- **Smart Turn v3.2** — opt-in and **default off**. It needs the pinned,
  SHA-256-verified ONNX artifact in app-private storage, which is side-loaded for
  now; there is no in-app download UI (R-0190–R-0193).
- **Local LLM** — the allow-listed `.litertlm` catalog is intentionally empty;
  the external provider is the primary LLM path (R-0213, R-0215).

## Supported devices and limitations

- **Primary device:** Pixel 10 (Android 17). `minSdk 31`, `targetSdk 36`,
  `compileSdk 37`.
- **Not supported / not implemented:** offline LLM use (R-0024); a second STT
  engine or general ONNX Runtime support (R-0010, R-0011); network TTS voices
  (R-0009); QR pairing (R-0022); on-device TFLite/LiteRT STT, TTS, or VAD models
  (R-0007, R-0005); long-term memory and configurable prompts (R-0034).
- **Device-unvalidated** (source/tests only): the whole `androidTest` matrix,
  real provider network runs, Smart Turn inference numbers, TTS voice
  enumeration/stop latency, STT runtime gating, credential KeyStore
  invalidation, and backup exclusion. See [Tests.md](../Tests.md) and
  [evaluation.md](./evaluation.md).

## Developer commands

```powershell
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat :app:assembleDebug        # build the debug APK
.\gradlew.bat :app:installDebug         # install on the connected device
.\gradlew.bat :app:testDebugUnitTest    # JVM unit tests
.\gradlew.bat :app:lintDebug            # Android lint
.\gradlew.bat spotlessApply             # apply formatting
```

Model/credential helpers are POSIX scripts run from WSL:
`scripts/fetch-models.sh`, `scripts/push-models.sh`,
`scripts/push-credentials.sh` (see `models/README.md`, `secrets/README.md`).

## Privacy and security audit

| Requirement | Status | Evidence / open item |
| --- | --- | --- |
| No credential in source, resources, build files, logs, or docs | Met | `security.RepositorySecretScanTest`; re-run against the packaged release artifact is R-0049. |
| Credentials only in Keystore-backed storage, replace/remove | Met (device unrun) | M13 `CredentialStore`; device round-trip is R-0075. |
| Credential blob excluded from backup/transfer | Configured, unverified | `allowBackup="false"` + `data_extraction_rules.xml`; R-0071. |
| No API key, prompt, transcript, raw audio, or provider body in logs/traces | Met | M04 redaction + `TraceRedactionTest`; per-turn trace content-freedom is R-0068. |
| Network transfer disclosed before send; retention shown | Met | M23 `ProviderDisclosure`; a per-send consent gate was deliberately not added (R-0165). |
| TLS via the platform trust store; redirects disabled | Met, no pinning | R-0099; DNS-rebinding defense is R-0154. |
| Destination validated (scheme/host/TLS) | Met | M13 `ServerDestination`; DNS resolution is not performed (R-0074/R-0154). |
| Permissions minimized (RECORD_AUDIO point-of-use, INTERNET) | Met | Manifest; no storage/location/contacts. |
| App-local session hint leaves the device | Open review | R-0162. |
| Bounded transport concurrency | Addressed (M26) | `RemoteTransport` semaphore; R-0094. |

## Known issues and next steps

- The Pixel 10 M25 evaluation rows are empty; no latency or accuracy budget is
  set ([evaluation.md](./evaluation.md), R-0043, R-0047).
- Smart Turn stays default-off until a device sweep meets the acceptance criteria
  (R-0006, R-0190–R-0192).
- The voice loop is assembled and JVM-tested but not run on hardware (R-0082).
- A per-send consent gate, per-conversation provider pinning, and a live model
  catalog are open product decisions (R-0165, R-0166, R-0102/R-0160).
