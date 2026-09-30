package com.voicechat.agent.providers

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.contracts.ModelDescriptor
import com.voicechat.agent.contracts.ModelRuntime
import com.voicechat.agent.contracts.ModelTask
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.providers.opencodego.OpenCodeGoModels

/**
 * The documented, static model list a provider has published, used before its
 * live `/models` surface is wired (M23).
 *
 * The M22 settings model picker is fed by the runtime capability snapshot; with
 * no live `/models` adapter yet (R-0102), that snapshot is empty and no remote
 * model is selectable. For the M23 vertical slice this exposes only the ids the
 * provider's own official page **places** — currently the OpenCode Go model
 * table via [OpenCodeGoModels.documentedFamilies] — so a selection is real and
 * validated rather than guessed.
 *
 * It is intentionally conservative: a provider with no dated, published list
 * contributes nothing, and this is **not** a substitute for the live surface
 * (R-0102 remains open). Every entry is a remote language model.
 */
object DocumentedModelCatalog {
    /** The documented remote chat models, in a deterministic order. */
    fun availableModels(): List<ModelAvailability> =
        OpenCodeGoModels
            .documentedFamilies()
            .keys
            .sorted()
            .map { id ->
                ModelAvailability.Ready(
                    ModelDescriptor(
                        id = ModelId(id),
                        task = ModelTask.LANGUAGE_MODEL,
                        displayName = id,
                        runtime = ModelRuntime.REMOTE_API,
                        providerId = KnownProviders.OPENCODE_GO,
                    ),
                )
            }
}
