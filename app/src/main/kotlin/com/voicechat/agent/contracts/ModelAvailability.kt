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
