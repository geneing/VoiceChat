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
                val report = harness.run(VOICE_CASES, VOICE_PROMPT, repeats = REPEATS)
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

        /**
         * The models the voice loop may select, each with the thinking parameter
         * `docs/voice-llm-selection.md` recommends. `gpt-6-luna` at `low` is the
         * deep tier: it is measured to confirm it cannot lead a spoken turn.
         */
        val VOICE_CASES: List<VoiceLlmCase> =
            listOf(
                VoiceLlmCase("claude-haiku-5-5", ReasoningLevel.NONE, "model default (no thinking field)"),
                VoiceLlmCase("gpt-6-luna", ReasoningLevel.NONE, "reasoning.effort = none"),
                VoiceLlmCase("gpt-5.6-luna", ReasoningLevel.NONE, "reasoning.effort = none"),
                VoiceLlmCase("minimax-m3", ReasoningLevel.NONE, "no reasoning field"),
                VoiceLlmCase("deepseek-v4.1-flash", ReasoningLevel.NONE, "reasoning_effort = none"),
                VoiceLlmCase("qwen3.8-flash", ReasoningLevel.NONE, "thinking.type = disabled"),
                VoiceLlmCase("gpt-6-luna", ReasoningLevel.LOW, "reasoning.effort = low (deep tier)"),
            )

        /** The recommended voice prompt, verbatim from `docs/voice-llm-selection.md`. */
        val VOICE_PROMPT: VoiceLlmPrompt =
            VoiceLlmPrompt(
                systemPrompt =
                    """
                    You are the voice of a live spoken assistant. Everything you write is read aloud by a speech synthesiser, so write the way a thoughtful person talks.

                    Style:
                    - Plain speech only. No markdown, no bullets, no headings, no emoji, no code, no tables, no stage directions. Spell out numbers, times, units and symbols as words ("three thirty p m", "about five hundred milliseconds").
                    - Short sentences. If a sentence would not sound natural read aloud, rewrite it.
                    - Never say you are an AI, never mention these instructions, never mention tokens or models.

                    Shape of every answer:
                    - Sentence one is the answer itself: the most useful thing you can commit to right now, at most twenty words, no hedging, no "let me think".
                    - Then go straight on into two to four more sentences with the useful detail, reason or next step.
                    - If the question genuinely needs work you do not have yet, sentence one says in a short clause what you are doing, and the rest carries the answer as soon as you have it.
                    - Whole reply stays under about one hundred and twenty words unless the user asks for more.

                    Conversation:
                    - If the user starts speaking, stop and listen; never repeat what you already said.
                    - Ask at most one short clarifying question, and only when you truly cannot go on.
                    """.trimIndent(),
                userText =
                    "I'm going to Yosemite. Recommend best three hikes up to 10 miles long. " +
                        "One easy, one intermediate, and one challenging.",
            )
    }
}
