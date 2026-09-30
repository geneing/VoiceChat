package com.voicechat.agent.local

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

// The only file that imports the ML Kit GenAI Prompt API (AICore / Gemini Nano).
// Everything else in the local package speaks LocalRuntimeProbe / LocalTextGenerator,
// so discovery and streaming are unit-testable on the JVM without AICore.
//
// Verified against the ML Kit GenAI Prompt API docs (accessed 2026-09-29):
// https://developers.google.com/ml-kit/genai/prompt/android/get-started
// The API is beta and not subject to an SLA; `checkStatus()` is the documented
// availability gate. AICore models are system-managed: this probe only reads
// status and never downloads, copies, inspects, or deletes model files.

/**
 * Reads Gemini Nano availability through the Prompt API's own `checkStatus()`.
 *
 * It never reports [LocalProbeResult.Ready] unless the API says `AVAILABLE`, and
 * it never triggers the API's `download()` (system-managed provisioning is out
 * of scope for this milestone; see `docs/local-models.md`).
 */
class MlKitGenAiPromptProbe : LocalRuntimeProbe {
    override suspend fun probe(): LocalProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val model = Generation.getClient()
                val result = model.checkStatus().toProbeResult()
                AppLog.i { "local: aicore prompt status -> ${result::class.simpleName}" }
                result
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AppLog.w(failure) { "local: aicore prompt status check failed (${failure.javaClass.simpleName})" }
                LocalProbeResult.Failed(
                    VoiceAgentError(
                        code = ErrorCode.MODEL_UNAVAILABLE,
                        detail = "AICore Prompt API status check failed (${failure.javaClass.simpleName})",
                    ),
                )
            }
        }
}

/**
 * Streams Gemini Nano text through the Prompt API.
 *
 * The app-level context is flattened to one prompt because the Prompt API's
 * system-instruction/structured surfaces are beta and not re-verified here; this
 * keeps the adapter honest about what it sends (see `docs/local-models.md`).
 * Reasoning is not exposed by the app for AICore, so no level is claimed.
 */
class MlKitPromptGenerator : LocalTextGenerator {
    override val capabilities: LlmCapabilities =
        LlmCapabilities(streaming = true, usageReporting = false, reasoningLevels = emptySet())

    override fun generate(prompt: String): Flow<String> =
        flow {
            val model = Generation.getClient()
            model.generateContentStream(prompt).collect { chunk ->
                val text = chunk.candidates.firstOrNull()?.text
                if (!text.isNullOrEmpty()) emit(text)
            }
        }.flowOn(Dispatchers.IO)
}

/** Maps the ML Kit `FeatureStatus` int this code was compiled against. */
internal fun Int.toProbeResult(): LocalProbeResult =
    when (this) {
        FeatureStatus.AVAILABLE -> {
            LocalProbeResult.Ready
        }

        FeatureStatus.DOWNLOADABLE -> {
            LocalProbeResult.DownloadRequired
        }

        FeatureStatus.DOWNLOADING -> {
            LocalProbeResult.Provisioning
        }

        else -> {
            LocalProbeResult.Unavailable(
                reason = UnavailableReason.DEVICE_UNSUPPORTED,
                detail = "Gemini Nano is not supported on this device",
            )
        }
    }
