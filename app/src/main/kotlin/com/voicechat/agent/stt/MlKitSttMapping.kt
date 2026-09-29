package com.voicechat.agent.stt

import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.GenAiException
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions

// Translations between the ML Kit GenAI Speech Recognition API and the
// platform-free STT types.
//
// Everything ML Kit-specific in M08 passes through this file, so the vendor
// types never reach orchestration or the UI. The public STT adapter and status
// checker are the only other files that import the vendor package.

/** Builds the ML Kit options for [this] engine/mode and locale. */
internal fun SttEngine.toRecognizerOptions(): SpeechRecognizerOptions {
    val engineLocale = locale
    val engineMode = mode
    return speechRecognizerOptions {
        locale = engineLocale
        preferredMode =
            when (engineMode) {
                SttMode.BASIC -> SpeechRecognizerOptions.Mode.MODE_BASIC
                SttMode.ADVANCED -> SpeechRecognizerOptions.Mode.MODE_ADVANCED
            }
    }
}

/** Maps the ML Kit `FeatureStatus` int this code was compiled against. */
internal fun Int.toSttFeatureStatus(): SttFeatureStatus =
    when (this) {
        FeatureStatus.AVAILABLE -> SttFeatureStatus.AVAILABLE
        FeatureStatus.DOWNLOADABLE -> SttFeatureStatus.DOWNLOADABLE
        FeatureStatus.DOWNLOADING -> SttFeatureStatus.DOWNLOADING
        else -> SttFeatureStatus.UNAVAILABLE
    }

/**
 * Maps a `GenAiException` error code to a vendor-neutral failure kind.
 *
 * Only the stable code is inspected; [GenAiException.message] is never copied
 * into a [com.voicechat.agent.domain.VoiceAgentError.detail], because an engine
 * message is not guaranteed to be free of user content.
 */
internal fun GenAiException.toFailureKind(): SttFailureKind =
    when (errorCode) {
        GenAiException.ErrorCode.NOT_AVAILABLE -> SttFailureKind.UNAVAILABLE

        GenAiException.ErrorCode.NEEDS_SYSTEM_UPDATE,
        GenAiException.ErrorCode.AICORE_INCOMPATIBLE,
        GenAiException.ErrorCode.BUSY,
        GenAiException.ErrorCode.NOT_ENOUGH_DISK_SPACE,
        -> SttFailureKind.NOT_READY

        else -> SttFailureKind.RECOGNITION_FAILED
    }
