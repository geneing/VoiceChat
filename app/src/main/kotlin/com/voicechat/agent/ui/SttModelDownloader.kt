package com.voicechat.agent.ui

import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.SttDownloadStatus
import com.voicechat.agent.stt.SttEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Starts the user-approved, system-managed STT model download (M08) for one
 * engine and reports its progress.
 *
 * It exists as a seam so [SettingsViewModel] never touches the vendor API: the
 * production implementation delegates to [MlKitSttStatus.download], and tests
 * drive a deterministic flow without a device. The download is always
 * system-managed through the supported ML Kit API — the app never fetches,
 * inspects, or deletes the model file itself.
 */
fun interface SttModelDownloader {
    /**
     * Starts the download and emits [SttDownloadStatus] until it reaches
     * [SttDownloadStatus.Completed] or [SttDownloadStatus.Failed]. Cancelling
     * collection cancels the download.
     */
    fun download(engine: SttEngine): Flow<SttDownloadStatus>
}

/** The production downloader: the real ML Kit GenAI status API. */
object MlKitSttModelDownloader : SttModelDownloader {
    override fun download(engine: SttEngine): Flow<SttDownloadStatus> = MlKitSttStatus.download(engine)
}

/**
 * The honest default when no downloader is wired: a single typed failure rather
 * than a silent no-op that would leave the UI waiting forever.
 */
object NoOpSttModelDownloader : SttModelDownloader {
    override fun download(engine: SttEngine): Flow<SttDownloadStatus> =
        flow {
            emit(
                SttDownloadStatus.Failed(
                    com.voicechat.agent.domain.VoiceAgentError(
                        com.voicechat.agent.domain.ErrorCode.MODEL_DOWNLOAD_FAILED,
                        "speech model downloads are not available in this build",
                    ),
                ),
            )
        }
}
