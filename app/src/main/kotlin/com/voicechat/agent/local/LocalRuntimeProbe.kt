package com.voicechat.agent.local

import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError

/**
 * Stable identities for the app's on-device language-model runtimes (M20).
 *
 * A local runtime is **not** a remote provider: the identity is deliberately
 * distinct from the M13 remote registry so the user can see which backend
 * served a turn and so the two can never be conflated or silently swapped.
 */
object LocalProviders {
    /** AICore / Gemini Nano, reached only through the ML Kit GenAI Prompt API. */
    val AICORE: ProviderId = ProviderId("on-device-aicore")

    /** The optional app-managed LiteRT-LM `.litertlm` runtime. */
    val LITERT_LM: ProviderId = ProviderId("on-device-litert-lm")
}

/**
 * The vendor-neutral result of probing a local runtime's own status surface.
 *
 * It exists so discovery can be mapped and unit tested on the JVM without the
 * native runtime: only the `MlKitGenAiPromptProbe` / LiteRT-LM wrapper touch the
 * vendor API, and every other local type speaks this result.
 */
sealed interface LocalProbeResult {
    /** The model is provisioned and usable now. */
    data object Ready : LocalProbeResult

    /**
     * The runtime could host the model but it is not provisioned. For an
     * app-managed artifact this is actionable; for system-managed AICore the
     * provisioning happens outside the app (see `docs/local-models.md`).
     */
    data object DownloadRequired : LocalProbeResult

    /** Provisioning is already in progress on the device. */
    data object Provisioning : LocalProbeResult

    /** The runtime/device cannot host the model, with a typed reason. */
    data class Unavailable(
        val reason: UnavailableReason,
        val detail: String,
    ) : LocalProbeResult

    /** The status check itself failed; never treated as ready. */
    data class Failed(
        val error: VoiceAgentError,
    ) : LocalProbeResult
}

/**
 * Reads a local runtime's availability from its own status surface.
 *
 * A probe is a cold, one-shot check; it never downloads, copies, inspects, or
 * deletes a model file. Cancelling it cancels the underlying status call.
 */
fun interface LocalRuntimeProbe {
    /** Returns the runtime's current availability; never reports ready on failure. */
    suspend fun probe(): LocalProbeResult
}
