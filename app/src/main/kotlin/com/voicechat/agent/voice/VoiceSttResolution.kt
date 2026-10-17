package com.voicechat.agent.voice

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttEngineFamily
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.stt.SttMode

/**
 * The resolved on-device STT choice for one session (M26, R-0181; stream 4).
 *
 * [Ready] names the one engine the loop must use. [Unavailable] carries a typed
 * error and is **never** substituted with another engine: a persisted mode the
 * runtime does not report ready is reported, not silently replaced (R-0181).
 * This mirrors the `SttAvailability` contract at the orchestration seam.
 */
sealed interface VoiceSttSelection {
    /** The engine to use; its [SttEngine.family] decides the adapter. */
    data class Ready(
        val engine: SttEngine,
    ) : VoiceSttSelection

    /** No ready engine for the persisted/selected mode; [error] is safe to show. */
    data class Unavailable(
        val error: VoiceAgentError,
    ) : VoiceSttSelection
}

/**
 * Pure resolution from runtime availability to a [VoiceSttSelection] (stream 4).
 *
 * It is the JVM-testable seam the voice assembly and `AppContainer` share: the
 * app boundary reads availability from the real engines (the platform
 * `SpeechRecognizer` plus ML Kit `checkStatus()`), and this object makes the one
 * decision.
 *
 * - a persisted [SttMode] that is ready resolves to that engine;
 * - a persisted mode that is installable but missing is [Unavailable] with
 *   `MODEL_UNAVAILABLE` (a download is required) — never substituted;
 * - a persisted mode the device cannot run is [Unavailable] with
 *   `STT_UNAVAILABLE`;
 * - no preference (`mode == null`) prefers the first ready engine in catalog
 *   order, matching `SttEngines.preferred`.
 */
object VoiceSttResolution {
    fun resolve(
        availabilities: List<SttAvailability>,
        mode: SttMode?,
    ): VoiceSttSelection {
        val engine = SttEngines.select(availabilities, mode)
        if (engine != null) return VoiceSttSelection.Ready(engine)

        val selected = mode?.let { selectedMode -> availabilities.firstOrNull { it.engine.mode == selectedMode } }
        val family = mode?.let(::familyOf)
        val (code, detail) =
            when (selected) {
                is SttAvailability.DownloadRequired -> {
                    ErrorCode.MODEL_UNAVAILABLE to
                        "the ${familyLabel(family)} model is not installed; a download is required"
                }

                is SttAvailability.Downloading -> {
                    ErrorCode.MODEL_UNAVAILABLE to
                        "the ${familyLabel(family)} model is still downloading"
                }

                is SttAvailability.Unavailable -> {
                    ErrorCode.STT_UNAVAILABLE to (selected.error.detail ?: "the selected STT engine is unavailable on this device")
                }

                is SttAvailability.Ready -> {
                    // Unreachable: select() would have returned it. Keep a typed
                    // failure rather than a success-shaped fallback.
                    ErrorCode.STT_UNAVAILABLE to "the selected STT engine could not be resolved"
                }

                null -> {
                    ErrorCode.STT_UNAVAILABLE to "no ready on-device STT engine for the selected mode"
                }
            }
        return VoiceSttSelection.Unavailable(VoiceAgentError(code, detail))
    }

    private fun familyOf(mode: SttMode): SttEngineFamily = SttEngine(mode, SttEngines.DEFAULT_LOCALE).family

    private fun familyLabel(family: SttEngineFamily?): String =
        when (family) {
            SttEngineFamily.ANDROID_SPEECH -> "Android Speech Recognizer"
            SttEngineFamily.ML_KIT_GENAI -> "ML Kit GenAI"
            null -> "on-device STT"
        }
}
