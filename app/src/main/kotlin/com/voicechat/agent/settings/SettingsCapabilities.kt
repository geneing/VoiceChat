package com.voicechat.agent.settings

import com.voicechat.agent.contracts.ModelAvailability
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.tts.TtsVoice

/**
 * Availability of the optional Smart Turn v3.2 semantic turn detector.
 *
 * Smart Turn is opt-in and default-off until M25 evidence (`docs/decisions.md`
 * §3.3). M10 is not implemented yet, so the honest default is [Unavailable] with
 * a reason: the bounded VAD-only endpoint still terminates every turn, and the
 * settings screen must not offer a detector that cannot run.
 */
sealed interface SmartTurnState {
    /** The model is installed and can be selected. */
    data object Available : SmartTurnState

    /** Installed only after a user-approved app-private download (M10). */
    data object DownloadRequired : SmartTurnState

    /** Not usable; [reason] is safe to show. */
    data class Unavailable(
        val reason: String,
    ) : SmartTurnState
}

/**
 * A snapshot of what the current device/runtime actually offers (M22).
 *
 * These lists come from the running engines and runtime status checks
 * (`SettingsCapabilityProvider`), never from a static assumption: STT modes from
 * the ML Kit feature status, TTS voices from `getVoices()`, and models from the
 * provider's `/models` surface. An unknown or unverified entry simply does not
 * appear, so an unsupported option is never presented as fact.
 */
data class SettingsCapabilities(
    /** Availability of the STT modes on this device. */
    val sttAvailability: List<SttAvailability> = emptyList(),
    /** Every installed TTS voice; the on-device policy filters this further. */
    val ttsVoices: List<TtsVoice> = emptyList(),
    /** Smart Turn availability; default unavailable until M10. */
    val smartTurn: SmartTurnState =
        SmartTurnState.Unavailable("Smart Turn is not installed; the bounded VAD endpoint is used."),
    /** The models the selected provider currently offers and their availability. */
    val models: List<ModelAvailability> = emptyList(),
    /**
     * True when no runtime check has produced this snapshot yet.
     *
     * An unread snapshot is **not** evidence that a stored selection is
     * unsupported, so validation must not treat its empty lists as a rejection
     * (and must not raise an "invalid selection cleared" notice). The empty
     * snapshot is [EMPTY] / [UNREAD]; a real check that found nothing is a
     * different, authoritative answer.
     */
    val isUnread: Boolean = false,
) {
    companion object {
        /**
         * The honest empty snapshot: nothing is known to be available. Used
         * before the first runtime check and in tests that only exercise one
         * capability.
         */
        val EMPTY: SettingsCapabilities = SettingsCapabilities(isUnread = true)
    }
}

/**
 * Supplies a fresh [SettingsCapabilities] snapshot.
 *
 * The app boundary implements this against the real engines and runtime status
 * checks (off the main thread); tests and previews use
 * [StaticSettingsCapabilityProvider]. It is `suspend` because device status
 * checks are asynchronous.
 */
interface SettingsCapabilityProvider {
    /** Reads the current runtime availability; never throws for “unknown”. */
    suspend fun snapshot(): SettingsCapabilities
}

/** A fixed snapshot, for tests, previews, and the pre-check default. */
class StaticSettingsCapabilityProvider(
    private val capabilities: SettingsCapabilities = SettingsCapabilities.EMPTY,
) : SettingsCapabilityProvider {
    override suspend fun snapshot(): SettingsCapabilities = capabilities
}
