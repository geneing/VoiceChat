package com.voicechat.agent.stt

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError

/**
 * Vendor-neutral feature status returned by the engine's `checkStatus()`.
 *
 * It mirrors the four states the ML Kit GenAI `FeatureStatus` annotation can
 * report. Keeping our own enum lets the availability mapping be unit tested on
 * the JVM and keeps the vendor annotation inside the adapter.
 */
enum class SttFeatureStatus {
    UNAVAILABLE,
    DOWNLOADABLE,
    DOWNLOADING,
    AVAILABLE,
}

/**
 * Availability of one STT engine/mode on this device.
 *
 * This is the "clearly reported single-engine state" the milestone requires:
 * [Unavailable] and [DownloadRequired] are first-class and a mode is never
 * reported [Ready] unless its feature status is `AVAILABLE`. A model download is
 * system-managed through the supported ML Kit API, not an app-managed artifact.
 */
sealed interface SttAvailability {
    val engine: SttEngine

    /** Usable now; the feature status is `AVAILABLE`. */
    data class Ready(
        override val engine: SttEngine,
    ) : SttAvailability

    /** Not provisioned; the user can download it through the supported API. */
    data class DownloadRequired(
        override val engine: SttEngine,
    ) : SttAvailability

    /** A download is already in progress. */
    data class Downloading(
        override val engine: SttEngine,
    ) : SttAvailability

    /** Not usable on this device, with a typed reason. */
    data class Unavailable(
        override val engine: SttEngine,
        val reason: UnavailableReason,
        val error: VoiceAgentError,
    ) : SttAvailability
}

/** Progress of a user-approved model download through the supported API. */
sealed interface SttDownloadStatus {
    /** Download accepted; [bytesToDownload] is the expected size, or 0 if unknown. */
    data class Started(
        val bytesToDownload: Long,
    ) : SttDownloadStatus

    /** Bytes downloaded so far. */
    data class Progress(
        val bytesDownloaded: Long,
    ) : SttDownloadStatus

    /** The model is provisioned and can be used. */
    data object Completed : SttDownloadStatus

    /** The download failed with a typed error; the model is not ready. */
    data class Failed(
        val error: VoiceAgentError,
    ) : SttDownloadStatus
}

/**
 * Pure mapping from a feature status to [SttAvailability].
 *
 * Only [SttFeatureStatus.AVAILABLE] produces [SttAvailability.Ready]; every
 * other status is explicit. This is the rule enforced by deliverable 4 of M08
 * ("never claim a missing or unprovisioned model is ready").
 */
object SttAvailabilityMapper {
    fun map(
        engine: SttEngine,
        status: SttFeatureStatus,
    ): SttAvailability =
        when (status) {
            SttFeatureStatus.AVAILABLE -> {
                SttAvailability.Ready(engine)
            }

            SttFeatureStatus.DOWNLOADABLE -> {
                SttAvailability.DownloadRequired(engine)
            }

            SttFeatureStatus.DOWNLOADING -> {
                SttAvailability.Downloading(engine)
            }

            SttFeatureStatus.UNAVAILABLE -> {
                SttAvailability.Unavailable(
                    engine = engine,
                    reason = UnavailableReason.DEVICE_UNSUPPORTED,
                    error =
                        VoiceAgentError(
                            code = ErrorCode.STT_UNAVAILABLE,
                            detail = "ML Kit GenAI Speech Recognition is not available on this device",
                        ),
                )
            }
        }
}
