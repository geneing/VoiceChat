package com.voicechat.agent.local

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.settings.OptionState
import com.voicechat.agent.settings.SelectableOption

/**
 * Builds the on-device model options shown alongside the remote providers (M20).
 *
 * Every label is suffixed `(on-device)` so the two backends can never be confused
 * in the picker, and the store/quality difference is visible before a choice. A
 * known-but-unavailable entry stays in the list as a disabled option with its
 * reason rather than disappearing, matching the M22 options contract. The
 * explicitly-empty catalog produces an empty list; the caller shows the honest
 * "no allow-listed local model" state from [LocalModelCatalog.status].
 */
object LocalModelOptions {
    /** Maps local availabilities to labelled, selectable options. */
    fun options(
        availabilities: List<ModelAvailability>,
        selected: ModelId?,
    ): List<SelectableOption<ModelAvailability>> =
        availabilities.map { availability ->
            SelectableOption(
                value = availability,
                label = "${availability.model.displayName} (on-device)",
                state = availability.toOptionState(),
                selected = availability.model.id == selected,
            )
        }

    private fun ModelAvailability.toOptionState(): OptionState =
        when (this) {
            is ModelAvailability.Ready -> OptionState.Available
            is ModelAvailability.DownloadRequired -> OptionState.Unavailable("Model download required")
            is ModelAvailability.Unavailable -> OptionState.Unavailable(error.detail ?: "Unavailable on this device")
        }
}
