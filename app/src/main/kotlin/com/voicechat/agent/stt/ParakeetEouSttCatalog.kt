package com.voicechat.agent.stt

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.stt.parakeet.ParakeetEouModelState
import java.util.Locale

/**
 * Maps the on-disk state of the app-managed Parakeet-EOU model set to the app-facing
 * [SttAvailability] the settings screen and the voice loop resolve against
 * (stream 4; `docs/parakeet-eou-streaming.md`).
 *
 * Parakeet-EOU is an **app-managed** engine, unlike the system-managed ML Kit
 * modes: the model set is absent on a fresh install, so the honest default is
 * [SttAvailability.DownloadRequired] (the settings screen offers the
 * user-approved download) rather than a fabricated ready state. A missing,
 * wrong-size, or wrong-hash file is a first-class result, never a success-shaped
 * fallback — the same rule as [SttAvailabilityMapper] for ML Kit and
 * `SmartTurnCatalog` for the semantic detector.
 */
object ParakeetEouSttCatalog {
    /** The single Parakeet-EOU engine configuration, using its own engine id. */
    fun engine(locale: Locale = SttEngines.DEFAULT_LOCALE): SttEngine = SttEngine(SttMode.PARAKEET_EOU, locale)

    /** Maps the resolved model state to the STT availability for one locale. */
    fun availability(
        state: ParakeetEouModelState,
        locale: Locale = SttEngines.DEFAULT_LOCALE,
    ): SttAvailability {
        val engine = engine(locale)
        return when (state) {
            is ParakeetEouModelState.Installed -> {
                SttAvailability.Ready(engine)
            }

            ParakeetEouModelState.Missing -> {
                SttAvailability.DownloadRequired(engine)
            }

            is ParakeetEouModelState.Corrupt -> {
                SttAvailability.Unavailable(
                    engine = engine,
                    reason = UnavailableReason.MODEL_NOT_PROVISIONED,
                    error =
                        VoiceAgentError(
                            code = ErrorCode.MODEL_CORRUPT,
                            detail = state.reason,
                        ),
                )
            }
        }
    }
}
