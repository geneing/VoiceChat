package com.voicechat.agent.local

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelAvailabilityProvider
import com.voicechat.agent.contracts.ModelTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Exposes the on-device language models and their availability (M20).
 *
 * AICore is probed through its own status surface; app-managed `.litertlm`
 * entries are reported from their verified install state. A device that cannot
 * run any local model yields an empty list, which is the explicit "no local model
 * available" result rather than a fabricated option. When the catalog is empty
 * (today) only AICore can appear, and only when its status is `AVAILABLE`.
 *
 * The flow is cold and re-emits only once per collection; a provisioning change
 * is picked up on the next collection or [snapshot]. Runtime probing runs off the
 * main thread inside the probe.
 */
class LocalModelAvailabilityProvider(
    private val aicoreProbe: LocalRuntimeProbe,
    private val installer: LocalModelInstaller,
) : ModelAvailabilityProvider {
    override fun observe(task: ModelTask): Flow<List<ModelAvailability>> =
        flow {
            emit(if (task == ModelTask.LANGUAGE_MODEL) snapshot() else emptyList())
        }

    /** Reads the current local-model availability; never throws for "unknown". */
    suspend fun snapshot(): List<ModelAvailability> {
        val result = mutableListOf<ModelAvailability>()

        result +=
            LocalAvailabilityMapper
                .fromProbe(LocalModels.geminiNano, aicoreProbe.probe(), systemManaged = true)
                .asModelAvailability()

        LocalModelCatalog.entries().forEach { model ->
            result +=
                LocalAvailabilityMapper
                    .fromInstallState(model.descriptor, installer.state(model))
                    .asModelAvailability()
        }

        return result
    }
}
