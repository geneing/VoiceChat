package com.voicechat.agent.local

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError

/** Known on-device language-model identities. */
object LocalModels {
    /** Gemini Nano, exposed only through AICore / the ML Kit GenAI Prompt API. */
    val GEMINI_NANO_ID: ModelId = ModelId("aicore.gemini-nano")

    /** The AICore descriptor. System-managed; never downloaded by this app. */
    val geminiNano: ModelDescriptor =
        ModelDescriptor(
            id = GEMINI_NANO_ID,
            task = ModelTask.LANGUAGE_MODEL,
            displayName = "Gemini Nano",
            runtime = ModelRuntime.ML_KIT_GENAI,
            providerId = LocalProviders.AICORE,
        )
}

/**
 * Typed local-model availability state: the lifecycle reasons the milestone
 * requires, kept separate from the M02 [ModelAvailability] contract so the exact
 * reason survives while the contract stays provider-neutral.
 */
enum class LocalModelState {
    READY,
    DOWNLOAD_REQUIRED,
    PROVISIONING,
    UNPROVISIONED,
    MISSING,
    CORRUPT,
    INSUFFICIENT_RESOURCES,
    DEVICE_UNSUPPORTED,
    NOT_ALLOW_LISTED,
    UNKNOWN,
}

/**
 * Availability of one local model, with a typed [state].
 *
 * Only [READY] maps to [ModelAvailability.Ready]. [DOWNLOAD_REQUIRED] is
 * app-managed only; a system-managed AICore model that is merely downloadable is
 * reported [Unavailable] because this app never downloads, copies, inspects, or
 * deletes AICore model files.
 */
sealed interface LocalModelAvailability {
    val model: ModelDescriptor

    /** The model is provisioned and usable now. */
    data class Ready(
        override val model: ModelDescriptor,
    ) : LocalModelAvailability

    /** An app-managed artifact that must be downloaded by the user first. */
    data class DownloadRequired(
        override val model: ModelDescriptor,
    ) : LocalModelAvailability

    /** Not usable, with the typed [state] and [error]. */
    data class Unavailable(
        override val model: ModelDescriptor,
        val state: LocalModelState,
        val reason: UnavailableReason,
        val error: VoiceAgentError,
    ) : LocalModelAvailability

    /** The provider-neutral contract view. */
    fun asModelAvailability(): ModelAvailability =
        when (this) {
            is Ready -> ModelAvailability.Ready(model)
            is DownloadRequired -> ModelAvailability.DownloadRequired(model)
            is Unavailable -> ModelAvailability.Unavailable(model, error)
        }
}

/**
 * Pure mapping from a runtime probe or install state to [LocalModelAvailability].
 *
 * There is exactly one way to reach [LocalModelAvailability.Ready]: the runtime
 * reported `READY` (or the artifact is installed and integrity-checked). Every
 * other state is explicit, so a missing/corrupt/unprovisioned model can never be
 * presented as ready.
 */
object LocalAvailabilityMapper {
    /** Maps a runtime probe result for a model of a given kind. */
    fun fromProbe(
        model: ModelDescriptor,
        probe: LocalProbeResult,
        systemManaged: Boolean,
    ): LocalModelAvailability =
        when (probe) {
            LocalProbeResult.Ready -> {
                LocalModelAvailability.Ready(model)
            }

            LocalProbeResult.DownloadRequired -> {
                if (systemManaged) {
                    // AICore provisioning is system-managed; this app never
                    // downloads it, so "downloadable" is not an app action.
                    unavailable(
                        model = model,
                        state = LocalModelState.UNPROVISIONED,
                        reason = UnavailableReason.MODEL_NOT_PROVISIONED,
                        code = ErrorCode.MODEL_UNAVAILABLE,
                        detail = "the system-managed model is downloadable but not provisioned",
                    )
                } else {
                    LocalModelAvailability.DownloadRequired(model)
                }
            }

            LocalProbeResult.Provisioning -> {
                unavailable(
                    model = model,
                    state = LocalModelState.PROVISIONING,
                    reason = UnavailableReason.MODEL_NOT_PROVISIONED,
                    code = ErrorCode.MODEL_UNAVAILABLE,
                    detail = "the model is still being provisioned",
                )
            }

            is LocalProbeResult.Unavailable -> {
                unavailable(
                    model = model,
                    state = stateFor(probe.reason),
                    reason = probe.reason,
                    code = ErrorCode.MODEL_UNAVAILABLE,
                    detail = probe.detail,
                )
            }

            is LocalProbeResult.Failed -> {
                unavailable(
                    model = model,
                    state = LocalModelState.UNKNOWN,
                    reason = UnavailableReason.UNKNOWN,
                    code = probe.error.code,
                    detail = probe.error.detail,
                )
            }
        }

    /** Maps an install-state result for an app-managed artifact. */
    fun fromInstallState(
        model: ModelDescriptor,
        install: LocalInstallState,
    ): LocalModelAvailability =
        when (install) {
            is LocalInstallState.Installed -> {
                LocalModelAvailability.Ready(model)
            }

            LocalInstallState.NotInstalled -> {
                LocalModelAvailability.DownloadRequired(model)
            }

            is LocalInstallState.Downloading -> {
                unavailable(
                    model = model,
                    state = LocalModelState.PROVISIONING,
                    reason = UnavailableReason.MODEL_NOT_PROVISIONED,
                    code = ErrorCode.MODEL_UNAVAILABLE,
                    detail = "the model is downloading",
                )
            }

            is LocalInstallState.IntegrityFailed -> {
                unavailable(
                    model = model,
                    state = LocalModelState.CORRUPT,
                    reason = UnavailableReason.MODEL_NOT_PROVISIONED,
                    code = ErrorCode.MODEL_CORRUPT,
                    detail = "the installed artifact failed integrity verification",
                )
            }

            is LocalInstallState.SizeMismatch -> {
                unavailable(
                    model = model,
                    state = LocalModelState.CORRUPT,
                    reason = UnavailableReason.MODEL_NOT_PROVISIONED,
                    code = ErrorCode.MODEL_CORRUPT,
                    detail = "the installed artifact size does not match the catalog",
                )
            }

            is LocalInstallState.Failed -> {
                unavailable(
                    model = model,
                    state = LocalModelState.UNKNOWN,
                    reason = UnavailableReason.UNKNOWN,
                    code = install.error.code,
                    detail = install.error.detail,
                )
            }
        }

    /** Maps a bare typed state (for a model absent from the local catalog). */
    fun fromState(
        model: ModelDescriptor,
        state: LocalModelState,
        detail: String? = null,
    ): LocalModelAvailability =
        when (state) {
            LocalModelState.READY -> {
                LocalModelAvailability.Ready(model)
            }

            LocalModelState.DOWNLOAD_REQUIRED -> {
                LocalModelAvailability.DownloadRequired(model)
            }

            else -> {
                unavailable(
                    model = model,
                    state = state,
                    reason = reasonFor(state),
                    code = codeFor(state),
                    detail = detail ?: defaultDetail(state),
                )
            }
        }

    private fun unavailable(
        model: ModelDescriptor,
        state: LocalModelState,
        reason: UnavailableReason,
        code: ErrorCode,
        detail: String?,
    ): LocalModelAvailability.Unavailable =
        LocalModelAvailability.Unavailable(
            model = model,
            state = state,
            reason = reason,
            error = VoiceAgentError(code = code, detail = detail),
        )

    private fun stateFor(reason: UnavailableReason): LocalModelState =
        when (reason) {
            UnavailableReason.DEVICE_UNSUPPORTED, UnavailableReason.FEATURE_UNSUPPORTED -> {
                LocalModelState.DEVICE_UNSUPPORTED
            }

            UnavailableReason.MODEL_NOT_PROVISIONED -> {
                LocalModelState.UNPROVISIONED
            }

            else -> {
                LocalModelState.UNKNOWN
            }
        }

    private fun reasonFor(state: LocalModelState): UnavailableReason =
        when (state) {
            LocalModelState.DEVICE_UNSUPPORTED, LocalModelState.NOT_ALLOW_LISTED -> {
                UnavailableReason.DEVICE_UNSUPPORTED
            }

            LocalModelState.UNPROVISIONED, LocalModelState.MISSING, LocalModelState.CORRUPT -> {
                UnavailableReason.MODEL_NOT_PROVISIONED
            }

            LocalModelState.INSUFFICIENT_RESOURCES -> {
                UnavailableReason.DEVICE_UNSUPPORTED
            }

            else -> {
                UnavailableReason.UNKNOWN
            }
        }

    private fun codeFor(state: LocalModelState): ErrorCode =
        when (state) {
            LocalModelState.CORRUPT -> ErrorCode.MODEL_CORRUPT
            LocalModelState.MISSING, LocalModelState.UNPROVISIONED -> ErrorCode.MODEL_UNAVAILABLE
            LocalModelState.INSUFFICIENT_RESOURCES -> ErrorCode.MODEL_UNAVAILABLE
            LocalModelState.NOT_ALLOW_LISTED -> ErrorCode.MODEL_UNAVAILABLE
            LocalModelState.DEVICE_UNSUPPORTED -> ErrorCode.MODEL_UNAVAILABLE
            else -> ErrorCode.MODEL_UNAVAILABLE
        }

    private fun defaultDetail(state: LocalModelState): String =
        when (state) {
            LocalModelState.UNPROVISIONED -> "the model is not provisioned on this device"
            LocalModelState.MISSING -> "the model files are missing"
            LocalModelState.CORRUPT -> "the model failed integrity verification"
            LocalModelState.INSUFFICIENT_RESOURCES -> "the device does not have enough resources for this model"
            LocalModelState.DEVICE_UNSUPPORTED -> "the device does not support this model"
            LocalModelState.NOT_ALLOW_LISTED -> "the model is not on the app's allow-list"
            else -> "the model is unavailable"
        }
}
