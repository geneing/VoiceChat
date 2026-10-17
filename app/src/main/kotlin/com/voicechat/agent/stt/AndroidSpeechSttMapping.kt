package com.voicechat.agent.stt

import android.content.Intent
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Translations between the platform `android.speech` API and the app's
 * platform-free STT types (M27).
 *
 * Everything `RecognizerIntent`/`SpeechRecognizer`-specific passes through this
 * file, so the vendor types never reach orchestration or the UI. The adapter
 * (`AndroidSpeechRecognizerSpeechToText`) and the status checker
 * (`AndroidSpeechSttStatus`) are the only other files that import the platform
 * package.
 */
internal object AndroidSpeechSttMapping {
    /** Sample rate the recognizer's custom-audio source is told to expect. */
    const val SAMPLE_RATE_HZ: Int = 16_000

    /** Channel count the recognizer's custom-audio source is told to expect. */
    const val CHANNEL_COUNT: Int = 1

    /** Encoding the recognizer's custom-audio source is told to expect. */
    const val ENCODING: Int = AudioFormat.ENCODING_PCM_16BIT

    /**
     * Builds the recognition intent.
     *
     * [audioSource] is the read end of the PCM pipe the adapter writes captured
     * frames into (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, API 33+). It is always
     * sent with the explicit channel count, encoding, and sample rate so the
     * recognizer reads our audio rather than opening the microphone; a recognizer
     * that does not honour the extra falls back to the microphone, which is
     * recorded as a limitation rather than hidden.
     *
     * `EXTRA_PREFER_OFFLINE` keeps the request on device; the adapter itself is
     * only ever built for an on-device recognizer (see [AndroidSpeechSttStatus]).
     */
    fun recognizerIntent(
        engine: SttEngine,
        audioSource: ParcelFileDescriptor,
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, engine.locale.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, audioSource)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, CHANNEL_COUNT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, ENCODING)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
        }

    /**
     * Maps a `SpeechRecognizer.ERROR_*` code to a vendor-neutral engine response.
     *
     * Only the stable integer code is inspected; no engine string is copied into
     * a failure detail, because a recognizer message is not guaranteed to be free
     * of user content. `ERROR_NO_MATCH` and `ERROR_SPEECH_TIMEOUT` mean the
     * recognizer heard no usable speech; they become a clean [SttEngineResponse.Completed]
     * so the turn is rejected as "no speech" rather than reported as a failure.
     */
    fun toEngineResponse(error: Int): SttEngineResponse =
        when (error) {
            // Benign: nothing was said, or the speaker stopped too early to score.
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            -> {
                SttEngineResponse.Completed
            }

            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                SttEngineResponse.Failure(SttFailureKind.UNAVAILABLE, detail = "RECORD_AUDIO_PERMISSION_MISSING")
            }

            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
            -> {
                SttEngineResponse.Failure(SttFailureKind.UNAVAILABLE, detail = "LANGUAGE_UNSUPPORTED")
            }

            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> {
                SttEngineResponse.Failure(SttFailureKind.NOT_READY, detail = "RECOGNIZER_BUSY")
            }

            SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
            -> {
                SttEngineResponse.Failure(SttFailureKind.RECOGNITION_FAILED, detail = "ASR_ERROR_$error")
            }

            else -> {
                SttEngineResponse.Failure(SttFailureKind.RECOGNITION_FAILED, detail = "ASR_ERROR_$error")
            }
        }

    /** How the adapter asks the recognizer to run, for diagnostics only. */
    fun modeLabel(onDevice: Boolean): String = if (onDevice) "on_device" else "prefer_offline"

    /** Locale used for the recognizer intent; extracted so the mapping stays testable. */
    internal fun languageTag(engine: SttEngine): String = engine.locale.toLanguageTag()

    /** The set of locales a settings surface should treat as the app default. */
    internal val DEFAULT_LOCALE: Locale = SttEngines.DEFAULT_LOCALE
}
