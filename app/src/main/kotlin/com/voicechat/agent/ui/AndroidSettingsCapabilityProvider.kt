package com.voicechat.agent.ui

import android.content.Context
import com.voicechat.agent.providers.DocumentedModelCatalog
import com.voicechat.agent.settings.SettingsCapabilities
import com.voicechat.agent.settings.SettingsCapabilityProvider
import com.voicechat.agent.settings.SmartTurnState
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.tts.AndroidTtsEngine
import com.voicechat.agent.tts.TtsVoice

/**
 * App-boundary [SettingsCapabilityProvider] that reads the **real** runtime
 * availability (M22).
 *
 * It is the only settings-adjacent platform file: it queries the M08 ML Kit
 * feature status per catalog mode and enumerates the M11 platform TTS voices, so
 * the settings options come from what the device actually reports rather than a
 * static assumption. It never claims a capability it did not just read:
 *
 * - STT modes come from `checkStatus()`, so an unprovisioned/unavailable mode is
 *   reported as such;
 * - TTS voices come from `getVoices()` and the settings screen filters to embedded
 *   voices;
 * - Smart Turn is M10 and is honestly unavailable until it is implemented;
 * - the model catalog is the **documented** static list (M23): the ids a
 *   provider's own page places, not a live `/models` result, so a selection is
 *   real without claiming the live surface is wired (R-0102 stays open).
 */
class AndroidSettingsCapabilityProvider(
    context: Context,
) : SettingsCapabilityProvider {
    private val appContext = context.applicationContext

    override suspend fun snapshot(): SettingsCapabilities =
        SettingsCapabilities(
            sttAvailability = SttEngines.catalog().map { MlKitSttStatus.check(it) },
            ttsVoices = readTtsVoices(),
            smartTurn = SMART_TURN_UNAVAILABLE,
            models = DocumentedModelCatalog.availableModels(),
        )

    private suspend fun readTtsVoices(): List<TtsVoice> {
        val engine = AndroidTtsEngine(appContext)
        return try {
            // initialize() discovers and selects a voice; installedVoices() then
            // returns the full list (the settings screen keeps only embedded ones).
            engine.initialize()
            engine.installedVoices()
        } catch (failure: Throwable) {
            // An engine that cannot initialize reports no voices rather than a
            // fabricated list; the screen shows the explicit no-voice state.
            emptyList()
        } finally {
            runCatching { engine.close() }
        }
    }

    private companion object {
        val SMART_TURN_UNAVAILABLE =
            SmartTurnState.Unavailable("Smart Turn is not installed; the bounded VAD endpoint is used.")
    }
}
