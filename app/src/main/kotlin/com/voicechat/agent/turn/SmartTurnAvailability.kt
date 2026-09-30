package com.voicechat.agent.turn

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelAvailabilityProvider
import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.settings.SmartTurnState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Maps the on-disk state of the pinned Smart Turn artifact to the app-facing
 * model-availability and settings types (M10).
 *
 * This is how the `smartTurnEnabled` setting is exposed honestly: the model is
 * [ModelAvailability.Ready]/[SmartTurnState.Available] only when the pinned file
 * is present **and** passes the size + SHA-256 integrity check. A missing model is
 * a download candidate; a wrong-size/hash file is unavailable with a reason.
 *
 * The descriptor declares [ModelTask.TURN_COMPLETION] and
 * [ModelRuntime.ONNX_RUNTIME], so it can only ever be offered for the semantic
 * completion decision and never mistaken for STT/TTS or an LLM.
 */
object SmartTurnCatalog {
    /** The app-facing identity of the single Smart Turn model. */
    val descriptor: ModelDescriptor =
        ModelDescriptor(
            id = SmartTurnArtifact.PINNED.modelId,
            task = ModelTask.TURN_COMPLETION,
            displayName = "Smart Turn v3.2 (int8)",
            runtime = ModelRuntime.ONNX_RUNTIME,
        )

    /** Maps the resolved model state to the M02 availability contract. */
    fun availability(state: SmartTurnModelState): ModelAvailability =
        when (state) {
            is SmartTurnModelState.Installed -> ModelAvailability.Ready(descriptor)
            SmartTurnModelState.Missing -> ModelAvailability.DownloadRequired(descriptor)
            is SmartTurnModelState.Corrupt -> ModelAvailability.Unavailable(descriptor, state.error)
        }

    /** Maps the resolved model state to the settings section's availability. */
    fun settingsState(state: SmartTurnModelState): SmartTurnState =
        when (state) {
            is SmartTurnModelState.Installed -> SmartTurnState.Available
            SmartTurnModelState.Missing -> SmartTurnState.DownloadRequired
            is SmartTurnModelState.Corrupt -> SmartTurnState.Unavailable(state.reason)
        }
}

/**
 * Exposes Smart Turn availability for [ModelTask.TURN_COMPLETION].
 *
 * The provider verifies the file once per collection; a task other than
 * [ModelTask.TURN_COMPLETION] emits the explicit empty list rather than implying
 * the detector is compatible with something it is not.
 */
class SmartTurnAvailabilityProvider(
    private val store: SmartTurnModelStore,
) : ModelAvailabilityProvider {
    override fun observe(task: ModelTask): Flow<List<ModelAvailability>> =
        flow {
            if (task != ModelTask.TURN_COMPLETION) {
                emit(emptyList())
                return@flow
            }
            emit(listOf(SmartTurnCatalog.availability(store.state())))
        }
}
