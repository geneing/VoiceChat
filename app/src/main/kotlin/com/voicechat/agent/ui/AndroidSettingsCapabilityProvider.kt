package com.voicechat.agent.ui

import android.content.Context
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.providers.DocumentedModelCatalog
import com.voicechat.agent.settings.KokoroState
import com.voicechat.agent.settings.PreferencesSettingsStore
import com.voicechat.agent.settings.SettingsCapabilities
import com.voicechat.agent.settings.SettingsCapabilityProvider
import com.voicechat.agent.settings.TtsEngineChoice
import com.voicechat.agent.settings.TtsEngineOption
import com.voicechat.agent.stt.AndroidSpeechSttStatus
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.tts.AndroidTtsEngine
import com.voicechat.agent.tts.TtsEngineAvailability
import com.voicechat.agent.tts.TtsVoice
import com.voicechat.agent.tts.kokoro.KokoroArtifact
import com.voicechat.agent.tts.kokoro.KokoroModelState
import com.voicechat.agent.tts.kokoro.KokoroModelStore
import com.voicechat.agent.tts.kokoro.KokoroTtsEngine
import com.voicechat.agent.tts.kokoro.KokoroVoiceArtifact
import com.voicechat.agent.turn.SmartTurnCatalog
import com.voicechat.agent.turn.SmartTurnModelStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.Locale

/**
 * App-boundary [SettingsCapabilityProvider] that reads the **real** runtime
 * availability (M22).
 *
 * It is the only settings-adjacent platform file: it queries the M08 ML Kit
 * feature status per catalog mode and verifies the app-private Kokoro graph, so
 * the settings options come from runtime state rather than a static assumption.
 * It never claims a capability it did not just read:
 *
 * - STT modes come from `checkStatus()`, so an unprovisioned/unavailable mode is
 *   reported as such;
 * - TTS voices come only from graphs verified by KokoroModelStore; platform
 *   speech engines and voices are not offered;
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
    // The asset opener is what makes a bundled graph visible here: without it the
    // settings surface would report a download as required even though the pinned
    // graph is packaged in the APK (docs/decisions.md §2.4).
    private val smartTurnStore: SmartTurnModelStore =
        SmartTurnModelStore(File(appContext.filesDir, SmartTurnModelStore.DIRECTORY_NAME))
    private val kokoroStore: KokoroModelStore =
        KokoroModelStore(
            File(appContext.filesDir, KokoroArtifact.DIRECTORY_NAME),
            openAsset = { path -> appContext.assets.open(path) },
        )

    override suspend fun snapshot(): SettingsCapabilities {
        val tts = readTts()
        return SettingsCapabilities(
            sttAvailability = readSttAvailability(),
            ttsVoices = tts.voices,
            ttsEngines = emptyList(),
            ttsEngineChoices = tts.engineChoices,
            kokoro = tts.state,
            smartTurn = SmartTurnCatalog.settingsState(smartTurnStore.state()),
            models = DocumentedModelCatalog.availableModels(),
        )
    }

    /**
     * The platform `SpeechRecognizer` engine (the default) plus the ML Kit modes.
     * The platform engine is reported from `SpeechRecognizer`, so a device with no
     * on-device recognizer is a first-class [SttAvailability.Unavailable] and never
     * a fabricated ready state; only the ML Kit modes go through
     * [MlKitSttStatus.check].
     */
    private suspend fun readSttAvailability(): List<SttAvailability> =
        listOf(
            AndroidSpeechSttStatus.check(appContext, SttEngine(SttMode.ANDROID_SPEECH, SttEngines.DEFAULT_LOCALE)),
        ) + SttEngines.mlKitCatalog().map { MlKitSttStatus.check(it) }

    private suspend fun readTts(): TtsSnapshot =
        try {
            val states =
                mutableMapOf<KokoroVoiceArtifact, KokoroModelState>().also { voiceStates ->
                    for (voice in KokoroArtifact.VOICES) {
                        voiceStates[voice] = kokoroStore.state(voice)
                    }
                }
            val voices =
                states.mapNotNull { (voice, state) ->
                    if (state is KokoroModelState.Installed) {
                        TtsVoice(
                            id = voice.id,
                            displayName = voice.displayName,
                            locale = Locale.forLanguageTag(voice.languageTag),
                            requiresNetwork = false,
                            quality = KokoroTtsEngine.DISTILLED_QUALITY_RANK,
                            latency = KokoroTtsEngine.ON_DEVICE_LATENCY_RANK,
                        )
                    } else {
                        null
                    }
                }
            val defaultState = states[KokoroArtifact.DEFAULT]
            val kokoroState =
                when (defaultState) {
                    is KokoroModelState.Installed -> KokoroState.Installed
                    KokoroModelState.Missing -> KokoroState.DownloadRequired
                    is KokoroModelState.Corrupt -> KokoroState.Unavailable(defaultState.reason)
                    null -> KokoroState.Unavailable("Kokoro model availability could not be checked.")
                }
            TtsSnapshot(voices, kokoroState, engineChoices(kokoroState, readPlatformEngineAvailability()))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AppLog.w(failure) { "settings: Kokoro model discovery failed" }
            TtsSnapshot(emptyList(), KokoroState.Unavailable("Kokoro model availability could not be checked."), emptyList())
        }

    /**
     * The app engines this device can speak through, each with a reason when it
     * cannot.
     *
     * The distilled engine is available exactly when its graph is (bundled in the
     * APK or installed app-privately), and the platform engine exactly when the
     * platform reports an embedded voice for the locale — read from the device,
     * never assumed (`docs/decisions.md` §2.2).
     */
    private fun engineChoices(
        kokoro: KokoroState,
        platform: String?,
    ): List<TtsEngineOption> =
        listOf(
            TtsEngineOption(
                choice = TtsEngineChoice.DISTILLED_KOKORO,
                available = kokoro is KokoroState.Installed,
                reason =
                    (kokoro as? KokoroState.Unavailable)?.reason
                        ?: if (kokoro is KokoroState.DownloadRequired) "The voice model is not installed." else null,
            ),
            TtsEngineOption(
                choice = TtsEngineChoice.PLATFORM,
                available = platform == null,
                reason = platform,
            ),
        )

    /**
     * The reason the platform engine cannot be used, or `null` when it can.
     *
     * It is only usable with an **embedded** voice for the app's locale: the app
     * never speaks through a network voice (`docs/decisions.md` §2.2).
     */
    private suspend fun readPlatformEngineAvailability(): String? {
        val locale =
            runCatching {
                PreferencesSettingsStore
                    .create(appContext)
                    .observe()
                    .first()
                    .locale()
            }.getOrElse { Locale.getDefault() }
        val engine = AndroidTtsEngine(appContext, locale)
        return try {
            val availability = engine.initialize()
            when (availability) {
                is TtsEngineAvailability.Ready -> null
                is TtsEngineAvailability.NoOnDeviceVoice -> "No embedded system voice is installed for ${locale.toLanguageTag()}."
                is TtsEngineAvailability.SelectedVoiceUnavailable -> "The selected system voice is not installed."
                is TtsEngineAvailability.SelectedEngineUnavailable -> "The selected system speech engine is not installed."
                is TtsEngineAvailability.Unavailable -> availability.error.detail ?: "The system speech engine could not be initialized."
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AppLog.w(failure) { "settings: platform TTS availability check failed" }
            "The system speech engine could not be initialized."
        } finally {
            runCatching { engine.close() }
        }
    }

    private data class TtsSnapshot(
        val voices: List<TtsVoice>,
        val state: KokoroState,
        val engineChoices: List<TtsEngineOption>,
    )
}
