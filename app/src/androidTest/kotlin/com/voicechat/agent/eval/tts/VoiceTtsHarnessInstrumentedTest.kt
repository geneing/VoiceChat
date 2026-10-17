package com.voicechat.agent.eval.tts

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.eval.MeasurementConditions
import com.voicechat.agent.log.AndroidLogging
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.tts.AndroidTtsEngine
import com.voicechat.agent.tts.EngineTextToSpeech
import com.voicechat.agent.tts.TtsEngineAvailability
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
 * The TTS-stage harness **on the device**: the same fixed texts through the real
 * M11 platform `TextToSpeech` engine and the selected on-device voice, measured
 * from the Pixel's own synthesis and playback path.
 *
 * It stops at the M02 `TextToSpeech` contract: no microphone, no recognizer, and
 * no LLM. What it adds over the host harness (`VoiceTtsHarnessTest`) is a real
 * engine, a real selected voice, and real audible output (R-0254/R-0255 stay the
 * echo and end-to-end-turn questions). A run with no embedded voice is skipped
 * (`Assume`), never faked.
 *
 * Opt-in: skipped unless `-e ttsHarnessRunId <id>` is passed, so routine
 * connected runs do not play audio.
 *
 * Reports are written to app-private cache, the same shape the LLM device
 * harness uses:
 *
 * ```text
 * cache/tts-harness/<runId>/summary.md   timings only
 * cache/tts-harness/<runId>/record.md    timings plus the conditions
 * ```
 *
 * `scripts/run-tts-harness-device.ps1` runs it and copies both back.
 */
@RunWith(AndroidJUnit4::class)
class VoiceTtsHarnessInstrumentedTest {
    @Test
    fun theSelectedOnDeviceVoiceSpeaksTheFixedTexts() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the on-device TTS harness", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid harness run id" }
            val repeats = arguments.getString(ARG_REPEATS)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_REPEATS

            AndroidLogging.install(debug = true, minLevel = LogLevel.INFO)
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val locale = Locale.getDefault()

            // The app's default: the platform engine its configured voice lives in.
            // The engine selects the most natural embedded voice for the locale; a
            // device with no embedded voice is skipped rather than measured against
            // a network voice.
            val engine = AndroidTtsEngine(context, locale)
            val availability = engine.initialize()
            assumeTrue(
                "no embedded voice on this device; the TTS harness needs a real on-device voice",
                availability is TtsEngineAvailability.Ready,
            )
            val voice = (availability as TtsEngineAvailability.Ready).voice
            val speech = EngineTextToSpeech(engine)
            try {
                val conditions =
                    MeasurementConditions(
                        device = "${Build.MODEL} (Android ${Build.VERSION.RELEASE}, API ${Build.VERSION.SDK_INT})",
                        route = "built-in speaker (assumed)",
                        locale = locale.toLanguageTag(),
                        fixture = "tts-harness",
                        model = voice.id,
                        notes = "engine=${engine.engineId.value}, fingerprint=${Build.FINGERPRINT}",
                    )
                val harness =
                    VoiceTtsHarness(
                        textToSpeech = speech,
                        conditions = conditions,
                        now = { timestamp() },
                    )
                val report = harness.run(VoiceTtsHarnessCases.TTS_TEXTS, repeats = repeats)

                val runDir = File(File(context.cacheDir, STAGE_DIRECTORY), runId)
                runDir.mkdirs()
                File(runDir, RECORD_FILE).writeText(report.toMarkdown())
                File(runDir, SUMMARY_FILE).writeText(report.summaryMarkdown() + "\n")
                Log.i(TAG, "tts harness record: ${File(runDir, RECORD_FILE).absolutePath}")
                report.summaryMarkdown().lines().forEach { line -> Log.i(TAG, line) }

                // Every text must have been delivered; a failure is a real device
                // result worth failing the run for, not something to hide.
                val failed = report.results.filter { !it.allCompleted }
                assertTrue(
                    "texts that did not deliver: ${failed.map { "${it.text}=${it.failures}" }}",
                    failed.isEmpty(),
                )
            } finally {
                speech.close()
            }
        }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    companion object {
        /** Instrumentation argument that opts the harness in. */
        const val ARG_RUN_ID: String = "ttsHarnessRunId"

        /** Instrumentation argument for attempts per text. */
        const val ARG_REPEATS: String = "ttsHarnessRepeats"

        /** Logcat tag the harness prints its content-free summary under. */
        const val TAG: String = "TtsHarness"

        /** App-private cache subdirectory the reports land in. */
        const val STAGE_DIRECTORY: String = "tts-harness"

        /** Content-free timing table. */
        const val SUMMARY_FILE: String = "summary.md"

        /** Timing table plus the conditions, for local review only. */
        const val RECORD_FILE: String = "record.md"

        /** Attempts per text when the caller does not say otherwise. */
        const val DEFAULT_REPEATS: Int = 3

        private val RUN_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
