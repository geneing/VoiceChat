package com.voicechat.agent.providers

/**
 * An authentication method the app can actually perform for a provider.
 *
 * Only methods a provider's **official documentation** describes are listed
 * (`docs/decisions.md` §4). There is deliberately no QR-pairing value: no
 * provider documents a QR flow, and a QR payload must never carry a reusable
 * credential (`docs/privacy-and-security.md`, [PairingQrPolicy]). Keeping the
 * enum closed is how "show only valid options" is enforced—there is no value to
 * accidentally display.
 */
enum class AuthMethod {
    /** The user pastes a provider-issued key/token. */
    API_KEY,

    /** Documented browser OAuth authorization-code + PKCE (OpenRouter only). */
    OAUTH_PKCE,
}

/**
 * Whether a provider documents a minimal way to check a credential.
 *
 * This is only a *capability*; the request itself lives in the provider adapter
 * (M14+). A provider whose semantics do not support a cheap check must report
 * [NONE] so the app never claims a key is valid when it could not verify it.
 */
enum class CredentialValidationSupport {
    /** No minimal, documented check; the app must not claim the key is valid. */
    NONE,

    /** A documented `GET /models` (or equivalent) that both lists and validates. */
    LIST_MODELS,
}

/** How a provider's selectable models are discovered. */
enum class ModelDiscovery {
    /** No model listing; only an explicitly configured model is usable. */
    NONE,

    /** Live discovery from the provider's `/models` (or equivalent) surface. */
    ENDPOINT,

    /** A fixed catalog shipped with the app (none for the planned providers). */
    STATIC_CATALOG,
}

/**
 * A capability whose provider behavior is not yet verified in official docs.
 *
 * The value is still carried at its best current setting, but callers (settings,
 * the registry test) can see that it is an assumption to confirm, so nothing
 * unverified is presented as fact.
 */
enum class UnverifiedCapability {
    STREAMING,
    USAGE,
    REASONING,
    MODEL_DISCOVERY,
    AUTH,
}
