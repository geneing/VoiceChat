package com.voicechat.agent.eval.llm

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.voicechat.agent.credentials.AndroidKeystoreCredentialStore
import com.voicechat.agent.debug.DebugCredentialImport
import com.voicechat.agent.eval.llm.VoiceLlmHarnessCases.VOICE_CASES
import com.voicechat.agent.eval.llm.VoiceLlmHarnessCases.VOICE_PROMPT
import com.voicechat.agent.log.AndroidLogging
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.opencodego.OpenCodeGoLanguageModel
import com.voicechat.agent.remote.OkHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
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
 * The LLM-only harness **on the device**: the same voice-shaped request through
 * every model the voice loop may select, with the recommended thinking parameter,
 * measured from the Pixel's own network path and ART runtime.
 *
 * It deliberately stops at the M12 `LanguageModel` contract: no microphone, no
 * recognizer, no TTS, and no turn orchestration. What it adds over the host
 * harness (`VoiceLlmHarnessTest`) is the device's network path, radio state, and
 * runtime — the part the host numbers cannot speak for (R-0253). TTS
 * time-to-first-audio and the end-to-end voice turn are separate device questions
 * (R-0254, R-0255).
 *
 * Opt-in: skipped unless `-e llmHarnessRunId <id>` is passed. The credential comes
 * from the app's own AndroidKeyStore-backed store, populated by the debug
 * application from the file `scripts/push-credentials.sh` stages; nothing is
 * passed on the command line and no key reaches logcat or the reports.
 *
 * Reports are written to app-private cache, one content-free timing summary and
 * one full record with the answers for local review:
 *
 * ```text
 * cache/llm-harness/<runId>/summary.md   timings only
 * cache/llm-harness/<runId>/record.md    timings plus the verbatim answers
 * ```
 *
 * `scripts/run-llm-harness-device.ps1` stages the credential, runs this test, and
 * copies both back.
 */
@RunWith(AndroidJUnit4::class)
class VoiceLlmHarnessInstrumentedTest {
    @Test
    fun everyRecommendedVoiceModelAnswersFromTheDevice() =
        runBlocking {
            val arguments = InstrumentationRegistry.getArguments()
            val runId = arguments.getString(ARG_RUN_ID)
            assumeTrue("set $ARG_RUN_ID to opt in to the on-device LLM harness", !runId.isNullOrBlank())
            requireNotNull(runId)
            require(RUN_ID.matches(runId)) { "invalid harness run id" }
            val repeats = arguments.getString(ARG_REPEATS)?.toIntOrNull()?.coerceAtLeast(1) ?: DEFAULT_REPEATS

            AndroidLogging.install(debug = true, minLevel = LogLevel.INFO)
            val context = InstrumentationRegistry.getInstrumentation().targetContext

            // The debug application imports the pushed file into the Keystore store
            // on launch; importing again here is a no-op when it already did, and
            // covers a process that started before the file was staged.
            val store = AndroidKeystoreCredentialStore.create(context)
            DebugCredentialImport.importIfPresent(context, store)
            val credential = store.load(KnownProviders.OPENCODE_GO)
            assumeTrue(
                "no OpenCode Go credential in the app store; run scripts/push-credentials.sh first",
                credential != null,
            )

            val model = OpenCodeGoLanguageModel(store, RemoteTransport(OkHttpStreamingEngine()))
            val harness =
                VoiceLlmHarness(
                    providerId = KnownProviders.OPENCODE_GO,
                    languageModel = model,
                    now = { timestamp() },
                )
            try {
                val report = harness.run(VOICE_CASES, VOICE_PROMPT, repeats = repeats)
                val runDir = File(File(context.cacheDir, STAGE_DIRECTORY), runId)
                runDir.mkdirs()
                File(runDir, RECORD_FILE).writeText(report.toMarkdown())
                File(runDir, SUMMARY_FILE).writeText(report.summaryMarkdown() + "\n")
                Log.i(TAG, "llm harness record: ${File(runDir, RECORD_FILE).absolutePath}")
                report.summaryMarkdown().lines().forEach { line -> Log.i(TAG, line) }

                // Every case must have produced an answer: a failure here is a real
                // device/provider result, not something to hide.
                val failed = report.results.filter { !it.allCompleted }
                assertTrue(
                    "cases that did not complete: ${failed.map { "${it.case.modelId}=${it.failures}" }}",
                    failed.isEmpty(),
                )
            } finally {
                model.close()
            }
        }

    private fun timestamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())

    companion object {
        /** Instrumentation argument that opts the harness in. */
        const val ARG_RUN_ID: String = "llmHarnessRunId"

        /** Instrumentation argument for attempts per case. */
        const val ARG_REPEATS: String = "llmHarnessRepeats"

        /** Logcat tag the harness prints its content-free summary under. */
        const val TAG: String = "LlmHarness"

        /** App-private cache subdirectory the reports land in. */
        const val STAGE_DIRECTORY: String = "llm-harness"

        /** Content-free timing table. */
        const val SUMMARY_FILE: String = "summary.md"

        /** Timing table plus the verbatim answers, for local review only. */
        const val RECORD_FILE: String = "record.md"

        /** Attempts per case when the caller does not say otherwise. */
        const val DEFAULT_REPEATS: Int = 3

        private val RUN_ID = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
