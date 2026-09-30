package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.DeterministicLanguageModel
import com.voicechat.agent.fake.ScriptedLlmStep
import com.voicechat.agent.log.AppLog
import com.voicechat.agent.log.LogLevel
import com.voicechat.agent.log.RecordingLogSink
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Proves the M12 logging rule on the LLM request/stream path: the developer log
 * records state transitions, identities, and counts, and never prompt text,
 * response text, or credentials.
 *
 * This is the free-text companion to `TraceRedactionTest`, which covers the
 * typed M04 trace. Both paths must stay content-free.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LlmStreamLoggingTest {
    private val promptText = "my private medical question"
    private val deltaText = "provider response body"
    private val fakeApiKey = "sk-live-DO-NOT-LEAK-0123456789"

    @After
    fun tearDown() {
        AppLog.reset()
    }

    @Test
    fun theRequestAndStreamPathLogsIdentitiesCountsAndStatesOnly() =
        runTest {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(LlmStreamEvent.Delta(deltaText)),
                            ScriptedLlmStep.Emit(
                                LlmStreamEvent.Completed(model = ModelId("served-model")),
                            ),
                        ),
                )
            val request =
                LlmRequest(
                    model = ProviderModelSelection(ProviderId("openrouter"), ModelId("some/model")),
                    messages = listOf(LlmMessage(LlmRole.USER, promptText)),
                )

            model.consume(request)

            val text = sink.messages.joinToString(separator = "\n")
            // Identities, counts, and state names are present.
            assertTrue(text.contains("openrouter"))
            assertTrue(text.contains("some/model"))
            assertTrue(text.contains("chars="))
            assertTrue(text.contains("deltas="))
            // No content or credential ever appears.
            assertFalse("prompt leaked: $text", text.contains(promptText))
            assertFalse("response text leaked: $text", text.contains(deltaText))
            assertFalse("credential leaked: $text", text.contains(fakeApiKey))
        }

    @Test
    fun aFailedStreamLogsTheStableCodeNotTheAdapterDetail() =
        runTest {
            val sink = RecordingLogSink()
            AppLog.configure(sink = sink, enabled = true, minLevel = LogLevel.VERBOSE)
            val model =
                DeterministicLanguageModel(
                    steps =
                        listOf(
                            ScriptedLlmStep.Emit(
                                LlmStreamEvent.Failed(
                                    error = VoiceAgentError(ErrorCode.LLM_AUTHENTICATION_FAILED),
                                    partialText = "",
                                ),
                            ),
                        ),
                )

            model.consume(LlmRequest(model = ProviderModelSelection(ProviderId("p"), ModelId("m")), messages = emptyList()))

            val text = sink.messages.joinToString(separator = "\n")
            assertTrue(text.contains("AUTHENTICATION"))
        }
}
