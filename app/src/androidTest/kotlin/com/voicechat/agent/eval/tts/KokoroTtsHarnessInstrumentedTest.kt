package com.voicechat.agent.eval.tts

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.eval.MeasurementConditions
import com.voicechat.agent.log.AndroidLogging
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.settings.PreferencesSettingsStore
import com.voicechat.agent.tts.kokoro.KokoroArtifact
import com.voicechat.agent.tts.kokoro.KokoroModelState
import com.voicechat.agent.tts.kokoro.KokoroModelStore
import com.voicechat.agent.tts.kokoro.KokoroTts
import com.voicechat.agent.tts.kokoro.OnnxKokoroEngine
import com.voicechat.agent.tts.kokoro.phonemes.DefaultKokoroPhonemizer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The M28 distilled-Kokoro TTS latency harness **on the device**: text start to
 * first sound, measured on the Pixel's own phonemizer, ONNX graph, and audio
 * device.
 *
 * It answers one question with two honest halves, in one pass:
 *
 * - **synthesis** — [KokoroTtsStageHarness] drives the real phonemizer and the
 *   real graph and records `tts_phonemize` → `tts_first_pcm` →
 *   `tts_synthesis_complete`. This is everything that must happen before the
 *   audio sink can be handed its first buffer, so it is what a phonemizer or
 *   graph change moves.
 * - **audible** — the M25 [VoiceTtsHarness] drives the same texts through the
 *   real `KokoroTtsEngine` and `AudioTrack` and records `tts_first_audible`, the
 *   moment audio was actually audible. The gap between the two is the audio
 *   device's own start cost.
 *
 * Both are measured from the same instant (the start of the text), so the rows
 * read as a breakdown rather than unrelated numbers.
 *
 * A run with no installed voice is **skipped** (`Assume`), never faked and never
 * downloaded: the graph is a verified app-private artifact
 * (`scripts/fetch-models.sh` then `scripts/push-models.sh kokoro-af-heart`, or
 * the install action in Settings). Opt-in via `-e kokoroHarnessRunId <id>` so
 * routine connected runs do not play audio.
 *
 * Reports are written to app-private cache, the same shape the M11/M25 harnesses
 * use:
 *
 * ```text
 * cache/kokoro-tts/<runId>/synthesis-summary.md   timings only
 * cache/kokoro-tts/<runId>/synthesis-record.md    timings plus the conditions
 * cache/kokoro-tts/<runId>/audible-summary.md     timings only
 * cache/kokoro-tts/<runId>/audible-record.md      timings plus the conditions
 * ```
 *
 * `scripts/run-kokoro-tts-latency.ps1` runs it and copies all four back.
 */
@RunWith(AndroidJUnit4::class)
class KokoroTtsHarnessInstrumentedTest {
    @Test
    fun theDistilledVoiceReachesFirstSoundInMeasuredTime() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the Kokoro latency harness", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid harness run id" }
            val repeats = arguments.getString(ARG_REPEATS)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_REPEATS

            AndroidLogging.install(debug = true, minLevel = LogLevel.INFO)
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            // Measure the configuration the app itself would use: the persisted
            // locale and voice from the settings store, exactly as AppContainer
            // builds the session engine. A persisted platform voice id (from the
            // M11 picker) is not a Kokoro voice, so it resolves to the default
            // rather than being handed to the engine as an unavailable selection.
            val settings = PreferencesSettingsStore.create(context).observe().first()
            val locale = settings.locale()
            val voice = KokoroArtifact.voice(settings.ttsVoiceId.orEmpty()) ?: KokoroArtifact.DEFAULT

            val store = KokoroModelStore(File(context.filesDir, KokoroArtifact.DIRECTORY_NAME))
            val state = store.state(voice)
            assumeTrue(
                "the ${voice.id} graph is not installed; fetch and push it before measuring (state=$state)",
                state is KokoroModelState.Installed,
            )
            val installed = state as KokoroModelState.Installed

            val phonemizer = DefaultKokoroPhonemizer.fromAssets(context.assets)
            val synthesizer = OnnxKokoroEngine.load(installed.file)
            val conditions =
                MeasurementConditions(
                    device = "${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})",
                    route = "built-in speaker (assumed)",
                    locale = locale.toLanguageTag(),
                    fixture = "kokoro-tts-harness",
                    model = "${voice.id} (${KokoroArtifact.UPSTREAM_REPOSITORY}@${KokoroArtifact.UPSTREAM_REVISION})",
                    notes =
                        "speed=${KokoroArtifact.DEFAULT_SPEED}, sampleRate=${KokoroArtifact.SAMPLE_RATE_HZ}, " +
                            "selectedVoice=${settings.ttsVoiceId ?: voice.id}, fingerprint=${Build.FINGERPRINT}",
                )
            val runDir = File(File(context.cacheDir, STAGE_DIRECTORY), runId)
            runDir.mkdirs()
            try {
                // 1. Synthesis stage: text start -> first PCM, no audio device.
                val stage = KokoroTtsStageHarness(phonemizer = phonemizer, synthesizer = synthesizer, conditions = conditions, now = { timestamp() })
                val stageReport = stage.run(VoiceTtsHarnessCases.TTS_TEXTS, repeats = repeats)
                write(runDir, SYNTHESIS_RECORD, stageReport.toMarkdown())
                write(runDir, SYNTHESIS_SUMMARY, stageReport.summaryMarkdown() + "\n")
                Log.i(TAG, "---- synthesis (text start to first PCM) ----")
                stageReport.summaryMarkdown().lines().forEach { line -> Log.i(TAG, line) }

                // 2. Audible stage: the same texts through the real engine and
                //    AudioTrack. This is the number a user perceives.
                val speech = KokoroTts.create(context = context, locale = locale, voiceId = voice.id)
                try {
                    val audible = VoiceTtsHarness(textToSpeech = speech, conditions = conditions, now = { timestamp() })
                    val audibleReport = audible.run(VoiceTtsHarnessCases.TTS_TEXTS, repeats = repeats)
                    write(runDir, AUDIBLE_RECORD, audibleReport.toMarkdown())
                    write(runDir, AUDIBLE_SUMMARY, audibleReport.summaryMarkdown() + "\n")
                    Log.i(TAG, "---- audible (text start to first sound) ----")
                    audibleReport.summaryMarkdown().lines().forEach { line -> Log.i(TAG, line) }

                    // Every text must have been delivered; a failure is a real
                    // device result worth failing the run for, not something to hide.
                    val failed = audibleReport.results.filter { !it.allCompleted }
                    assertTrue(
                        "texts that did not deliver: ${failed.map { "${it.text}=${it.failures}" }}",
                        failed.isEmpty(),
                    )
                } finally {
                    speech.close()
                }
            } finally {
                synthesizer.close()
            }
        }

    private fun write(
        directory: File,
        name: String,
        content: String,
    ) {
        val file = File(directory, name)
        file.writeText(content)
        Log.i(TAG, "kokoro latency report: ${file.absolutePath}")
    }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    companion object {
        /** Instrumentation argument that opts the harness in. */
        const val ARG_RUN_ID: String = "kokoroHarnessRunId"

        /** Instrumentation argument for attempts per text. */
        const val ARG_REPEATS: String = "kokoroHarnessRepeats"

        /** Logcat tag the harness prints its content-free summary under. */
        const val TAG: String = "KokoroTtsHarness"

        /** App-private cache subdirectory the reports land in. */
        const val STAGE_DIRECTORY: String = "kokoro-tts"

        const val SYNTHESIS_SUMMARY: String = "synthesis-summary.md"
        const val SYNTHESIS_RECORD: String = "synthesis-record.md"
        const val AUDIBLE_SUMMARY: String = "audible-summary.md"
        const val AUDIBLE_RECORD: String = "audible-record.md"

        /** Attempts per text when the caller does not say otherwise. */
        const val DEFAULT_REPEATS: Int = 3

        private val RUN_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
