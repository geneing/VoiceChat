package com.voicechat.agent.ui

import android.content.Context
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.providers.DocumentedModelCatalog
import com.voicechat.agent.settings.SettingsCapabilities
import com.voicechat.agent.settings.SettingsCapabilityProvider
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.tts.AndroidTtsEngine
import com.voicechat.agent.tts.TtsVoice
import com.voicechat.agent.turn.SmartTurnCatalog
import com.voicechat.agent.turn.SmartTurnModelStore
import kotlinx.coroutines.CancellationException
import java.io.File

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
 * - Smart Turn availability (M10) comes from the app-private model file and its
 *   size + SHA-256 integrity check: installed → Available, missing →
 *   DownloadRequired, wrong size/hash → Unavailable with a reason;
 * - the model catalog is the **documented** static list (M23): the ids a
 *   provider's own page places, not a live `/models` result, so a selection is
 *   real without claiming the live surface is wired (R-0102 stays open).
 */
class AndroidSettingsCapabilityProvider(
    context: Context,
) : SettingsCapabilityProvider {
    private val appContext = context.applicationContext

    // One store per provider instance so repeated snapshots reuse the same
    // app-private directory; verification runs off the main thread inside state().
    private val smartTurnStore: SmartTurnModelStore =
        SmartTurnModelStore(File(appContext.filesDir, SmartTurnModelStore.DIRECTORY_NAME))

    override suspend fun snapshot(): SettingsCapabilities =
        SettingsCapabilities(
            sttAvailability = SttEngines.catalog().map { MlKitSttStatus.check(it) },
            ttsVoices = readTtsVoices(),
            smartTurn = SmartTurnCatalog.settingsState(smartTurnStore.state()),
            models = DocumentedModelCatalog.availableModels(),
        )

    private suspend fun readTtsVoices(): List<TtsVoice> {
        val engine = AndroidTtsEngine(appContext)
        return try {
            // initialize() discovers and selects a voice; installedVoices() then
            // returns the full list (the settings screen keeps only embedded ones).
            engine.initialize()
            engine.installedVoices()
        } catch (cancellation: CancellationException) {
            // A cancelled refresh must stay cancelled, not become a successful
            // "no voices" snapshot (CODE_REVIEW P2, R-0225).
            throw cancellation
        } catch (failure: Throwable) {
            // An engine that cannot initialize reports no voices rather than a
            // fabricated list; the screen shows the explicit no-voice state.
            // (A distinct init-failure state is part of the settings-state
            // simplification still tracked as R-0164/R-0221.)
            AppLog.w(failure) { "settings: TTS voice discovery failed" }
            emptyList()
        } finally {
            runCatching { engine.close() }
        }
    }
}
