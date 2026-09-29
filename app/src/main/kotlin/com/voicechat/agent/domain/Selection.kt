package com.voicechat.agent.domain

/**
 * A user-selected provider and model pair.
 *
 * Provider identity stays separate from model identity, so the app can show the
 * true origin of a request and never silently swaps either (see
 * `docs/llm-providers.md`).
 */
data class ProviderModelSelection(
    val providerId: ProviderId,
    val modelId: ModelId,
)

/**
 * Reasoning/thinking effort for a provider that exposes one.
 *
 * The set is the union of the levels verified across the planned providers
 * (see `docs/decisions.md` §4). A given provider/model supports only a subset;
 * callers must show a level only when the selected capability metadata lists it.
 */
enum class ReasoningLevel {
    NONE,
    MINIMAL,
    LOW,
    MEDIUM,
    HIGH,
    XHIGH,
    MAX,
}

/** Why a provider or on-device capability is unavailable. */
enum class UnavailableReason {
    NOT_CONFIGURED,
    CREDENTIALS_REQUIRED,
    FEATURE_UNSUPPORTED,
    MODEL_NOT_PROVISIONED,
    NETWORK_UNAVAILABLE,
    DEVICE_UNSUPPORTED,
    UNKNOWN,
}

/**
 * Connection status for a provider or runtime, shown to the user.
 *
 * A failure is always represented as a failure; there is no state that implies
 * a silent fallback to another provider or model.
 */
sealed interface ConnectionState {
    /** No connection is configured or initiated. */
    data object Disconnected : ConnectionState

    /** A connection attempt or credential check is in progress. */
    data object Connecting : ConnectionState

    /** Ready to serve requests. */
    data object Connected : ConnectionState

    /** Known unavailable for a user-explainable reason. */
    data class Unavailable(
        val reason: UnavailableReason,
        val detail: String? = null,
    ) : ConnectionState

    /** The last attempt failed with a typed error. */
    data class Failed(
        val error: VoiceAgentError,
    ) : ConnectionState
}
