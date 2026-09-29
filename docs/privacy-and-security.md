# Privacy and Security

## Data flow

STT and TTS are intended to execute on-device. The default LLM uses an external
API, so user-approved transcript text and any context included in the request
leave the device. Do not describe this product as fully offline or state that
conversation data never leaves the device while the remote provider is active.

Before enabling remote requests, the UI and product documentation must make
the transfer clear. Send only the text and context needed for the request; do
not send raw microphone audio unless the product requirements are deliberately
changed and users are informed.

## Credentials and transport

- Never embed a long-lived API secret in the APK, source code, resources,
  Gradle files, tests, logs, or sample configuration. Support API keys entered
  by the user, plus browser/device authorization or QR pairing only when the
  selected provider officially supports that flow.
- Use TLS and the provider's supported authentication flow. For production
  credentials requiring confidentiality, use a trusted backend or a suitable
  user-provided credential flow; obfuscation is not secret storage.
- Protect user-provided API keys at rest with an Android Keystore-backed
  approach, keep them out of backups where appropriate, and provide clear
  replace/remove controls. Do not persist provider passwords. Do not use the
  deprecated `androidx.security:security-crypto` helpers
  (`EncryptedSharedPreferences`, `MasterKey`, `EncryptedFile`); the API docs
  direct callers to AndroidKeyStore via `javax.crypto.KeyGenerator` (see the
  [decision record](./decisions.md)).
- A QR code is only a transport for a provider-authorized pairing challenge or
  URL; never encode a reusable API key, access token, or password in a QR code.
  Prefer short-lived, single-use challenges and verify the completed pairing
  with the provider. Do not scan arbitrary QR data as a trusted endpoint or
  credential.
- Keep credentials out of URLs, analytics, crash reports, and exception text.
- Limit request size and duration, handle rate limits and authentication
  errors, and allow an in-flight request to be cancelled.

## Local data and logging

- Do not log raw audio, complete transcripts, prompts, credentials, or
  unredacted provider responses by default.
- Make transcript persistence an explicit product choice. If conversation data
  is stored, use app-private storage and provide a clear deletion path.
- Keep temporary audio and incomplete model downloads short-lived; clean them
  up on completion, failure, or cancellation.
- Request microphone permission only when needed, explain its use, and stop
  capture when the session ends or permission is revoked.

## Optional on-device providers

Use AICore / ML Kit GenAI only through supported APIs and document what data
the specific API processes locally or otherwise. Do not infer privacy behavior
from the product name alone. For downloaded TFLite / LiteRT models, verify
provenance, license, and artifact integrity before use.

Any change that sends additional data off-device, adds persistence, or expands
permissions requires updating this document and the user-facing disclosure.
