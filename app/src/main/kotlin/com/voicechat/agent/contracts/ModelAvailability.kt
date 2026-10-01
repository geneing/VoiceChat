package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow

/** What a model does; selection only offers models for the current task. */
enum class ModelTask {
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
    TURN_COMPLETION,
    LANGUAGE_MODEL,
}

/** Where a model executes. Remote API models are distinct from on-device runtimes. */
enum class ModelRuntime {
    ML_KIT_GENAI,
    ANDROID_PLATFORM_TTS,
    LITERT,
    LITERT_LM,
    ONNX_RUNTIME,
    REMOTE_API,
}

/**
 * Catalog metadata for one selectable model.
 *
 * This is the app-facing identity only; provenance, license, checksum, and
 * tensor contract stay in the model allow-list documentation and are validated
 * before a model enters the catalog (see `docs/model-runtime.md`).
 */
data class ModelDescriptor(
    val id: ModelId,
    val task: ModelTask,
    val displayName: String,
    val runtime: ModelRuntime,
    val providerId: ProviderId? = null,
) {
    init {
        require(displayName.isNotBlank()) { "displayName must not be blank" }
    }
}

/**
 * Availability of one model on the current device.
 *
 * Unavailable is a first-class result: a missing, unprovisioned, or corrupt
 * model is never reported as ready.
 */
sealed interface ModelAvailability {
    val model: ModelDescriptor

    /** Usable now. */
    data class Ready(
        override val model: ModelDescriptor,
    ) : ModelAvailability

    /**
     * Usable after a user-approved download. App-managed artifacts only;
     * system-managed AICore models are never downloaded here.
     */
    data class DownloadRequired(
        override val model: ModelDescriptor,
    ) : ModelAvailability

    /** Not usable, with a typed reason. */
    data class Unavailable(
        override val model: ModelDescriptor,
        val error: VoiceAgentError,
    ) : ModelAvailability
}

/**
 * The catalog state for a provider's models, as a first-class value (M27, R-0102).
 *
 * A model list is not just "some entries": the UI must distinguish a catalog that
 * is still loading from one that came back empty because no source is wired, from
 * one that failed. This is what stops a provider from appearing configured when no
 * model can actually be selected, without inventing a fallback model.
 */
sealed interface ModelCatalogState {
    /** The catalog has not been read yet. */
    data object Loading : ModelCatalogState

    /**
     * The catalog was read and contains at least one model. Individual entries
     * still carry their own [ModelAvailability] (ready or unavailable).
     */
    data class Available(
        override val models: List<ModelAvailability>,
    ) : ModelCatalogState

    /** The read succeeded but returned nothing; [reason] is safe to show. */
    data class Empty(
        val reason: String,
    ) : ModelCatalogState

    /**
     * The entries exist but none is currently selectable (for example every
     * offered model needs a download). Distinct from [Empty]: a model is listed
     * and disabled with its own reason.
     */
    data class Unavailable(
        override val models: List<ModelAvailability>,
    ) : ModelCatalogState

    /** The read failed; [error] is a typed, non-sensitive reason. */
    data class Failed(
        val error: VoiceAgentError,
    ) : ModelCatalogState

    /** The entries are cached from an earlier read and were not revalidated. */
    data class Stale(
        override val models: List<ModelAvailability>,
        val reason: String,
    ) : ModelCatalogState {
        init {
            require(models.isNotEmpty()) { "a stale catalog must carry the cached entries" }
        }
    }

    /** Every listed entry, regardless of state; empty for [Loading]/[Empty]/[Failed]. */
    val models: List<ModelAvailability>
        get() =
            when (this) {
                is Available -> models
                is Unavailable -> models
                is Stale -> models
                Loading, is Empty, is Failed -> emptyList()
            }

    /** True when at least one listed entry is selectable now. */
    val hasSelectableModel: Boolean get() = models.any { it is ModelAvailability.Ready }

    /** True when a selection can never be made from this state without a retry. */
    val needsAttention: Boolean
        get() =
            when (this) {
                Loading, is Available -> false
                is Empty, is Unavailable, is Stale, is Failed -> !hasSelectableModel
            }

    companion object {
        /** The pre-read state. */
        val INITIAL: ModelCatalogState = Loading

        /**
         * Classifies a read result. An empty list is [Empty] with [emptyReason];
         * a non-empty list is [Available] when something is ready and
         * [Unavailable] when nothing is.
         */
        fun of(
            models: List<ModelAvailability>,
            emptyReason: String,
        ): ModelCatalogState =
            when {
                models.isEmpty() -> Empty(emptyReason)
                models.any { it is ModelAvailability.Ready } -> Available(models)
                else -> Unavailable(models)
            }
    }
}

/**
 * Exposes the models available for a task on the current device.
 *
 * **Ownership and lifecycle.** [observe] is a cold flow with no terminal event:
 * it emits the current list and re-emits when availability changes (for example
 * after a model download or provisioning change). The consumer owns each
 * collection; cancelling it releases any device-status listener. Runtime
 * discovery and model-status checks run off the main thread.
 */
interface ModelAvailabilityProvider {
    /** Observes availability for every known model of [task]. */
    fun observe(task: ModelTask): Flow<List<ModelAvailability>>
}
