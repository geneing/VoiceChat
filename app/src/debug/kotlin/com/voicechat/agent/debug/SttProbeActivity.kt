package com.voicechat.agent.debug

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.util.Log
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerOptions
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.voicechat.agent.audio.MicrophoneAudioCapture
import com.voicechat.agent.audio.audioSourceName
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.stt.MlKitSpeechToText
import com.voicechat.agent.stt.MlKitSttStatus
import com.voicechat.agent.stt.PcmFraming
import com.voicechat.agent.stt.SttAvailability
import com.voicechat.agent.stt.SttDownloadStatus
import com.voicechat.agent.stt.SttEngine
import com.voicechat.agent.stt.SttEngines
import com.voicechat.agent.stt.SttMode
import com.voicechat.agent.stt.isLowConfidence
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.util.Locale

/**
 * Debug-only manual probe for the M08 on-device STT engine (Pixel 10 run).
 *
 * It exists only in the `debug` source set (`app/src/debug`) and is never part
 * of a release artifact. It provides the smallest repeatable way to:
 *
 *  1. call [MlKitSttStatus.check] (and the raw `checkStatus()` int) and record
 *     the device's `FeatureStatus` → `SttAvailability`;
 *  2. drive the real M07 capture → M08 adapter path and show partials/final;
 *  3. run the raw recognizer so the per-`FinalTextResponse` segment text and the
 *     engine's flow-completion behavior (R-0054) are visible.
 *
 * Because no human is available to speak, the probe synthesizes known phrases
 * with the platform `TextToSpeech` API and captures the speaker output through
 * the microphone. That is synthetic speech, not a human run.
 *
 * Transcript text is written to logcat/UI here only because this is a
 * debug-only probe; production code never logs transcripts.
 *
 * `adb shell am start -n com.voicechat.agent/.debug.SttProbeActivity --es action <action>`
 * with action one of `status`, `download`, `adapter`, `raw`. Phrases may join
 * two utterances with `||`; the probe inserts a silence gap so the engine can
 * finalize two segments.
 */
class SttProbeActivity : ComponentActivity() {
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true) }
        val root =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(ScrollView(this@SttProbeActivity).apply { addView(output) })
            }
        val buttons =
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button("Status") { lifecycleScope.launch { runStatus() } })
                addView(button("Download") { lifecycleScope.launch { runDownload() } })
                addView(button("Adapter") { lifecycleScope.launch { runAdapter(ADVANCED, DEFAULT_PHRASE) } })
                addView(button("Raw") { lifecycleScope.launch { runRaw(ADVANCED, DEFAULT_PHRASE) } })
            }
        root.addView(buttons)
        setContentView(root)

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
        }

        val requestedPhrase = requestedPhrase()
        when (intent?.getStringExtra(EXTRA_ACTION)) {
            ACTION_STATUS -> lifecycleScope.launch { runStatus() }
            ACTION_DOWNLOAD -> lifecycleScope.launch { runDownload() }
            ACTION_ADAPTER -> lifecycleScope.launch { runAdapter(ADVANCED, requestedPhrase) }
            ACTION_RAW -> lifecycleScope.launch { runRaw(ADVANCED, requestedPhrase) }
            null -> append("Ready. Use the buttons or am start --es action.")
        }
    }

    /**
     * Reads the phrase from a base64 extra when present. Base64 avoids the
     * `adb shell` argument re-parsing that truncates values containing spaces.
     */
    private fun requestedPhrase(): String {
        val encoded = intent?.getStringExtra(EXTRA_PHRASE_B64)
        if (!encoded.isNullOrBlank()) {
            return runCatching { String(Base64.decode(encoded, Base64.DEFAULT)) }.getOrDefault(DEFAULT_PHRASE)
        }
        return intent?.getStringExtra(EXTRA_PHRASE) ?: DEFAULT_PHRASE
    }

    private fun button(label: String, onClick: () -> Unit) =
        Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { onClick() }
        }

    // --- Status / provisioning ------------------------------------------------

    private suspend fun runStatus() {
        append("=== status: engine=${engine.engineId.value} locale=${engine.locale.toLanguageTag()} ===")
        for (candidate in SttEngines.catalog(engine.locale)) {
            val raw = rawFeatureStatus(candidate)
            val availability = MlKitSttStatus.check(candidate)
            append("mode=${candidate.mode} model=${candidate.modelId.value} rawFeatureStatus=$raw availability=${describe(availability)}")
        }
    }

    private suspend fun runDownload() {
        for (candidate in SttEngines.catalog(engine.locale)) {
            when (val availability = MlKitSttStatus.check(candidate)) {
                is SttAvailability.DownloadRequired -> {
                    append("download: starting ${candidate.mode}")
                    MlKitSttStatus.download(candidate).collect { status ->
                        when (status) {
                            is SttDownloadStatus.Started -> append("download ${candidate.mode}: started bytes=${status.bytesToDownload}")
                            is SttDownloadStatus.Progress -> append("download ${candidate.mode}: bytes=${status.bytesDownloaded}")
                            is SttDownloadStatus.Completed -> append("download ${candidate.mode}: completed")
                            is SttDownloadStatus.Failed -> append("download ${candidate.mode}: FAILED ${status.error.code} ${status.error.detail}")
                        }
                    }
                }

                else -> append("download: ${candidate.mode} not downloadable (${describe(availability)})")
            }
        }
        runStatus()
    }

    // --- Adapter path (M07 capture -> M08 adapter) ----------------------------

    private suspend fun runAdapter(
        candidate: SttEngine,
        phrase: String,
    ) {
        val source = intent.getIntExtra(EXTRA_SOURCE, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        append("=== adapter: mode=${candidate.mode} source=${audioSourceName(source)} phrase=\"$phrase\" ===")
        val capture = MicrophoneAudioCapture.create(applicationContext, audioSource = source)
        val adapter = MlKitSpeechToText(candidate)
        var frames = 0
        try {
            val framed =
                capture
                    .frames()
                    .onEach { frames++ }
                    .onCompletion { append("capture: frames completed count=$frames") }
            val job =
                lifecycleScope.launch(Dispatchers.IO) {
                    adapter.transcribe(framed).collect { event ->
                        when (event) {
                            is SttEvent.Result ->
                                append(
                                    "adapter event: isFinal=${event.transcript.isFinal} rev=${event.transcript.revision.value} " +
                                        "lowConfidence=${event.transcript.isLowConfidence()} text=\"${event.transcript.text}\"",
                                )

                            is SttEvent.Failed -> append("adapter event: FAILED ${event.error.code} ${event.error.detail}")
                        }
                    }
                }
            withTts { speakAll(it, phrase) }
            delay(TRAILING_SILENCE_MILLIS)
            append("adapter: closing capture")
            runCatching { capture.close() }
            if (withTimeoutOrNull(8_000) { job.join() } == null) {
                append("adapter: recognition flow did not complete after capture end; cancelling")
                job.cancelAndJoin()
            }
            append("adapter: run complete")
        } finally {
            runCatching { adapter.close() }
            runCatching { capture.close() }
        }
    }

    // --- Raw recognizer (per-segment FinalTextResponse) -----------------------

    private suspend fun runRaw(
        candidate: SttEngine,
        phrase: String,
    ) {
        val source = intent.getIntExtra(EXTRA_SOURCE, MediaRecorder.AudioSource.VOICE_RECOGNITION)
        append("=== raw: mode=${candidate.mode} source=${audioSourceName(source)} phrase=\"$phrase\" ===")
        val capture = MicrophoneAudioCapture.create(applicationContext, audioSource = source)
        val recognizer = SpeechRecognition.getClient(candidate.toOptions())
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        var frames = 0
        try {
            append("raw: checkStatus=${rawFeatureStatus(candidate)}")
            val pump =
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        FileOutputStream(writeEnd.fileDescriptor).use { out ->
                            capture
                                .frames()
                                .onEach { frames++ }
                                .onCompletion { append("raw capture: frames completed count=$frames") }
                                .collect { frame -> out.write(PcmFraming.toLittleEndianPcm(frame)) }
                        }
                    }
                }
            val recognition =
                lifecycleScope.launch(Dispatchers.IO) {
                    runCatching {
                        recognizer
                            .startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromPfd(readEnd) })
                            .collect { response ->
                                when (response) {
                                    is SpeechRecognizerResponse.PartialTextResponse -> append("raw partial: \"${response.text}\"")
                                    is SpeechRecognizerResponse.FinalTextResponse -> append("raw FINAL segment: \"${response.text}\"")
                                    is SpeechRecognizerResponse.CompletedResponse -> append("raw completed")
                                    is SpeechRecognizerResponse.ErrorResponse -> append("raw error: ${response.e.errorCode}")
                                }
                            }
                    }.onFailure { append("raw: recognition stream failed (${it.javaClass.simpleName})") }
                }
            withTts { speakAll(it, phrase) }
            delay(TRAILING_SILENCE_MILLIS)
            append("raw: closing capture")
            runCatching { capture.close() }
            if (withTimeoutOrNull(12_000) { recognition.join() } == null) {
                append("raw: recognition flow did not complete after capture end; calling stopRecognition")
                withContext(Dispatchers.IO) { runCatching { recognizer.stopRecognition() } }
                withTimeoutOrNull(5_000) { recognition.join() }
            }
            pump.cancelAndJoin()
            append("raw: run complete")
        } finally {
            runCatching { recognizer.close() }
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
            runCatching { capture.close() }
        }
    }

    // --- TTS loopback ---------------------------------------------------------

    /**
     * Initializes one platform TTS instance, runs [block], and always shuts it
     * down. TTS is the only way to produce speech on an unattended device.
     */
    private suspend fun withTts(block: suspend (TextToSpeech) -> Unit) {
        val ready = CompletableDeferred<Boolean>()
        val tts =
            TextToSpeech(applicationContext) { status ->
                if (!ready.isCompleted) ready.complete(status == TextToSpeech.SUCCESS)
            }
        try {
            val initialized = withTimeoutOrNull(6_000) { ready.await() } ?: false
            if (!initialized) {
                append("tts: init failed")
                return
            }
            maximizeVolume()
            block(tts)
        } finally {
            runCatching { tts.stop() }
            runCatching { tts.shutdown() }
        }
    }

    /** Speaks one or more `@@`-separated utterances with a silence gap between. */
    private suspend fun speakAll(
        tts: TextToSpeech,
        phrase: String,
    ) {
        val parts = phrase.split("@@").map { it.trim() }.filter { it.isNotEmpty() }
        parts.forEachIndexed { index, part ->
            val done = CompletableDeferred<Unit>()
            tts.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (!done.isCompleted) done.complete(Unit)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (!done.isCompleted) done.complete(Unit)
                    }

                    override fun onError(
                        utteranceId: String?,
                        errorCode: Int,
                    ) {
                        if (!done.isCompleted) done.complete(Unit)
                    }
                },
            )
            tts.speak(part, TextToSpeech.QUEUE_FLUSH, null, "m08-probe-$index")
            val finished = withTimeoutOrNull(20_000) { done.await() } != null
            append("tts: utterance=$index finished=$finished text=\"$part\"")
            if (index < parts.lastIndex) delay(INTER_UTTERANCE_SILENCE_MILLIS)
        }
    }

    private fun maximizeVolume() {
        runCatching {
            val audio = getSystemService(AudioManager::class.java)
            audio?.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        }
    }

    // --- Helpers --------------------------------------------------------------

    private fun SttEngine.toOptions(): SpeechRecognizerOptions =
        speechRecognizerOptions {
            locale = this@toOptions.locale
            preferredMode =
                when (this@toOptions.mode) {
                    SttMode.BASIC -> SpeechRecognizerOptions.Mode.MODE_BASIC
                    SttMode.ADVANCED -> SpeechRecognizerOptions.Mode.MODE_ADVANCED
                }
        }

    private suspend fun rawFeatureStatus(candidate: SttEngine): String {
        val recognizer = SpeechRecognition.getClient(candidate.toOptions())
        return try {
            featureStatusName(recognizer.checkStatus())
        } catch (failure: Throwable) {
            "checkStatus threw ${failure.javaClass.simpleName}"
        } finally {
            runCatching { recognizer.close() }
        }
    }

    private fun featureStatusName(status: Int): String =
        when (status) {
            FeatureStatus.AVAILABLE -> "AVAILABLE($status)"
            FeatureStatus.DOWNLOADABLE -> "DOWNLOADABLE($status)"
            FeatureStatus.DOWNLOADING -> "DOWNLOADING($status)"
            FeatureStatus.UNAVAILABLE -> "UNAVAILABLE($status)"
            else -> "UNKNOWN($status)"
        }

    private fun describe(availability: SttAvailability): String =
        when (availability) {
            is SttAvailability.Ready -> "Ready"
            is SttAvailability.DownloadRequired -> "DownloadRequired"
            is SttAvailability.Downloading -> "Downloading"
            is SttAvailability.Unavailable -> "Unavailable(reason=${availability.reason}, error=${availability.error.code})"
        }

    private fun append(line: String) {
        Log.i(TAG, line)
        runOnUiThread {
            output.append("$line\n")
        }
    }

    private val engine: SttEngine = SttEngine(SttMode.ADVANCED, Locale.US)

    companion object {
        private const val TAG = "VoiceChatSttProbe"
        private const val EXTRA_ACTION = "action"
        private const val EXTRA_PHRASE = "phrase"
        private const val EXTRA_PHRASE_B64 = "phraseB64"
        private const val EXTRA_SOURCE = "source"
        private const val ACTION_STATUS = "status"
        private const val ACTION_DOWNLOAD = "download"
        private const val ACTION_ADAPTER = "adapter"
        private const val ACTION_RAW = "raw"
        private const val DEFAULT_PHRASE = "Hello, this is a test. Please open the settings."
        private const val TRAILING_SILENCE_MILLIS = 2_500L
        private const val INTER_UTTERANCE_SILENCE_MILLIS = 1_500L
        private val ADVANCED = SttEngine(SttMode.ADVANCED, Locale.US)
    }
}
