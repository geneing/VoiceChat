package com.voicechat.agent.stt

import android.content.Context
import android.speech.SpeechRecognizer
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.UnavailableReason
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runtime availability of the platform `SpeechRecognizer` engine (M27).
 *
 * The app keeps speech on device, so a device is reported [SttAvailability.Ready]
 * **only** when it exposes an on-device recognizer
 * (`SpeechRecognizer.isOnDeviceRecognitionAvailable`). A device that only offers
 * a network recognizer is reported unavailable with a typed reason rather than
 * silently routing the user's speech to a server — the same honesty rule the
 * rest of the STT layer follows (`docs/android-speech-recognition.md`).
 *
 * All work runs off the main thread; the check itself is cheap and synchronous.
 */
object AndroidSpeechSttStatus {
    /** Checks [engine] against the platform recognizer available on this device. */
    suspend fun check(
        context: Context,
        engine: SttEngine,
    ): SttAvailability =
        withContext(Dispatchers.Default) {
            try {
                val onDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                val anyRecognizer = SpeechRecognizer.isRecognitionAvailable(context)
                val availability =
                    when {
                        onDevice -> {
                            SttAvailability.Ready(engine)
                        }

                        anyRecognizer -> {
                            SttAvailability.Unavailable(
                                engine = engine,
                                reason = UnavailableReason.DEVICE_UNSUPPORTED,
                                error =
                                    VoiceAgentError(
                                        code = ErrorCode.STT_UNAVAILABLE,
                                        detail = "no on-device recognizer on this device; the app does not send speech to a server",
                                    ),
                            )
                        }

                        else -> {
                            SttAvailability.Unavailable(
                                engine = engine,
                                reason = UnavailableReason.DEVICE_UNSUPPORTED,
                                error =
                                    VoiceAgentError(
                                        code = ErrorCode.STT_UNAVAILABLE,
                                        detail = "no speech recognition service is installed on this device",
                                    ),
                            )
                        }
                    }
                AppLog.i {
                    "stt: platform recognizer availability model=${engine.modelId.value} " +
                        "on_device=$onDevice -> ${availability::class.simpleName}"
                }
                availability
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                AppLog.w(failure) { "stt: platform recognizer availability check failed (${failure.javaClass.simpleName})" }
                SttAvailability.Unavailable(
                    engine = engine,
                    reason = UnavailableReason.UNKNOWN,
                    error =
                        VoiceAgentError(
                            code = ErrorCode.STT_UNAVAILABLE,
                            detail = "speech recognition availability check failed (${failure.javaClass.simpleName})",
                        ),
                )
            }
        }
}
