package com.voicechat.agent.eval.llm

import com.voicechat.agent.credentials.Credential
import com.voicechat.agent.credentials.CredentialKind
import com.voicechat.agent.credentials.InMemoryCredentialStore
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.providers.KnownProviders
import com.voicechat.agent.providers.opencodego.OpenCodeGoLanguageModel
import com.voicechat.agent.remote.OkHttpStreamingEngine
import com.voicechat.agent.remote.RemoteTransport
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import kotlin.time.Duration.Companion.minutes

/**
 * **Opt-in only.** The LLM side of the voice loop measured against the real
 * OpenCode Go service: the same voice-shaped request through every model the
 * voice loop would use, with the thinking parameter recommended for each, and a
 * markdown record of the timings and the answers.
 *
 * It is skipped — never failed, never run — unless **both**
 * `VOICECHAT_LLM_HARNESS=1` and `OPENCODE_API_KEY` are set, so routine CI and
 * `:app:testDebugUnitTest` never make a provider call. The key reaches only the
 * request headers, and nothing asserts on prompt or response content (the record
 * file holds the answers, which is the point of the harness).
 *
 * Run it deliberately, from the repository root, with:
 *
 * ```
 * bash scripts/run-llm-harness.sh          # WSL/Git Bash, reads secrets/opencode-go.key
 * ```
 *
 * or directly:
 *
 * ```
 * $env:VOICECHAT_LLM_HARNESS = "1"
 * $env:OPENCODE_API_KEY = "<key>"
 * $env:VOICECHAT_LLM_HARNESS_OUT = "app/build/llm-harness/voice-llm-yosemite.md"  # optional
 * .\gradlew.bat :app:testDebugUnitTest --tests "*VoiceLlmHarnessTest"
 * ```
 */
class VoiceLlmHarnessTest {
    @Test
    fun everyRecommendedVoiceModelAnswersTheYosemiteRequest() =
        runTest(timeout = 10.minutes) {
            val apiKey = System.getenv("OPENCODE_API_KEY")
            val enabled = System.getenv("VOICECHAT_LLM_HARNESS") == "1"
            assumeTrue(
                "opt-in harness: set VOICECHAT_LLM_HARNESS=1 and OPENCODE_API_KEY to run it",
                enabled && !apiKey.isNullOrBlank(),
            )

            val store =
                InMemoryCredentialStore().apply {
                    store(Credential(KnownProviders.OPENCODE_GO, CredentialKind.API_KEY, apiKey!!))
                }
            val model = OpenCodeGoLanguageModel(store, RemoteTransport(OkHttpStreamingEngine()))
            val harness =
                VoiceLlmHarness(
                    providerId = KnownProviders.OPENCODE_GO,
                    languageModel = model,
                    now = { LocalDate.now().toString() },
                )
            try {
                val report = harness.run(VoiceLlmHarnessCases.VOICE_CASES, VoiceLlmHarnessCases.VOICE_PROMPT, repeats = REPEATS)
                val output = File(System.getenv("VOICECHAT_LLM_HARNESS_OUT") ?: DEFAULT_OUT)
                output.parentFile?.mkdirs()
                output.writeText(report.toMarkdown())
                println("llm harness record: ${output.absolutePath}")
                println(report.summaryMarkdown())

                // Every case must have produced an answer; a failure is a real
                // result worth failing the run for, not something to hide.
                val failed = report.results.filter { !it.allCompleted }
                assertTrue(
                    "cases that did not complete: ${failed.map { "${it.case.modelId}=${it.failures}" }}",
                    failed.isEmpty(),
                )
            } finally {
                model.close()
            }
        }

    private companion object {
        const val DEFAULT_OUT = "build/llm-harness/voice-llm-yosemite.md"

        /**
         * Attempts per case. Repeated because a single provider call is not a
         * latency measurement; override with `VOICECHAT_LLM_HARNESS_REPEATS`.
         */
        val REPEATS: Int = System.getenv("VOICECHAT_LLM_HARNESS_REPEATS")?.toIntOrNull()?.coerceAtLeast(1) ?: 3
    }
}
