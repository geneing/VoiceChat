package com.voicechat.agent.stt

import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
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

/**
 * Runtime availability of the single on-device STT engine, through the
 * supported ML Kit GenAI APIs.
 *
 * This is the mandatory gate from `docs/decisions.md` §2.1: a caller must
 * [check] before presenting STT as ready, and the result is never inferred from
 * the app's own state. Availability and provisioning are system-managed; the
 * app never downloads, inspects, or deletes AICore model files itself — it only
 * observes status and, when the user approves, triggers the API's `download()`.
 *
 * All work runs off the main thread.
 */
object MlKitSttStatus {
    /**
     * Checks [engine] and maps the feature status to [SttAvailability].
     *
     * A thrown status check is surfaced as an explicit [SttAvailability.Unavailable],
     * never as a crash and never as ready. Cancellation is rethrown so the caller
     * can stop normally.
     */
    suspend fun check(engine: SttEngine): SttAvailability =
        withContext(Dispatchers.IO) {
            val recognizer = SpeechRecognition.getClient(engine.toRecognizerOptions())
            try {
                val availability = SttAvailabilityMapper.map(engine, recognizer.checkStatus().toSttFeatureStatus())
                AppLog.i {
                    "stt: availability mode=${engine.mode} model=${engine.modelId.value} " +
                        "-> ${availability::class.simpleName}"
                }
                availability
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AppLog.w(failure) { "stt: availability check failed (${failure.javaClass.simpleName})" }
                SttAvailability.Unavailable(
                    engine = engine,
                    reason = UnavailableReason.UNKNOWN,
                    error =
                        VoiceAgentError(
                            code = ErrorCode.STT_UNAVAILABLE,
                            detail = "feature status check failed (${failure.javaClass.simpleName})",
                        ),
                )
            } finally {
                runCatching { recognizer.close() }
            }
        }

    /**
     * Starts a user-approved model download for [engine] and reports progress.
     *
     * The flow ends after [SttDownloadStatus.Completed] or
     * [SttDownloadStatus.Failed]; cancelling collection cancels the download.
     * Only call this when [check] returned [SttAvailability.DownloadRequired].
     */
    fun download(engine: SttEngine): Flow<SttDownloadStatus> =
        flow {
            AppLog.i { "stt: model download start model=${engine.modelId.value}" }
            val recognizer = SpeechRecognition.getClient(engine.toRecognizerOptions())
            try {
                recognizer.download().collect { status ->
                    when (status) {
                        is DownloadStatus.DownloadStarted -> {
                            AppLog.d { "stt: download started bytes=${status.bytesToDownload}" }
                            emit(SttDownloadStatus.Started(status.bytesToDownload))
                        }

                        is DownloadStatus.DownloadProgress -> {
                            emit(SttDownloadStatus.Progress(status.totalBytesDownloaded))
                        }

                        is DownloadStatus.DownloadCompleted -> {
                            AppLog.i { "stt: model download completed model=${engine.modelId.value}" }
                            emit(SttDownloadStatus.Completed)
                        }

                        is DownloadStatus.DownloadFailed -> {
                            AppLog.e(status.e) {
                                "stt: model download failed (${status.e.toFailureKind().name.lowercase()})"
                            }
                            emit(
                                SttDownloadStatus.Failed(
                                    VoiceAgentError(
                                        code = ErrorCode.MODEL_DOWNLOAD_FAILED,
                                        detail =
                                            "speech model download failed (${status.e.toFailureKind().name.lowercase()})",
                                    ),
                                ),
                            )
                        }
                    }
                }
            } finally {
                runCatching { recognizer.close() }
            }
        }.flowOn(Dispatchers.IO)
}
