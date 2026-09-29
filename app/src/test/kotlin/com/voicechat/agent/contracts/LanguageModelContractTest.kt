package com.voicechat.agent.contracts

import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.fake.FakeLanguageModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanguageModelContractTest {
    private val selection = ProviderModelSelection(ProviderId("provider"), ModelId("model"))
    private val request =
        LlmRequest(
            model = selection,
            messages = listOf(LlmMessage(LlmRole.USER, "hello")),
            reasoning = null,
        )

    @Test
    fun deltaEventsArriveBeforeTheSingleTerminalCompletion() =
        runTest {
            val model =
                FakeLanguageModel(
                    script =
                        listOf(
                            LlmStreamEvent.Delta("Hel"),
                            LlmStreamEvent.Delta("lo"),
                            LlmStreamEvent.Completed(usage = LlmUsage(promptTokens = 3, completionTokens = 2)),
                        ),
                )

            val events = model.stream(request).toList()

            assertEquals(
                listOf(
                    LlmStreamEvent.Delta("Hel"),
                    LlmStreamEvent.Delta("lo"),
                    LlmStreamEvent.Completed(usage = LlmUsage(promptTokens = 3, completionTokens = 2)),
                ),
                events,
            )
        }

    @Test
    fun requestIsDeliveredToTheAdapterUnchanged() =
        runTest {
            val model = FakeLanguageModel()

            model.stream(request).toList()

            assertEquals(request, model.lastRequest)
            assertEquals(selection, model.lastRequest?.model)
        }

    @Test
    fun failureTerminatesWithThePartialTextReceivedSoFar() =
        runTest {
            val error = VoiceAgentError(ErrorCode.LLM_NETWORK_FAILED)
            val model =
                FakeLanguageModel(
                    script =
                        listOf(
                            LlmStreamEvent.Delta("partial"),
                            LlmStreamEvent.Failed(error = error, partialText = "partial"),
                        ),
                )

            val events = model.stream(request).toList()

            assertEquals(2, events.size)
            val terminal = events.last()
            assertTrue(terminal is LlmStreamEvent.Failed)
            assertEquals("partial", (terminal as LlmStreamEvent.Failed).partialText)
            assertEquals(error, terminal.error)
        }

    @Test
    fun cancellingCollectionStopsTheStreamWithoutACompletion() =
        runTest {
            val model =
                FakeLanguageModel(
                    script = listOf(LlmStreamEvent.Delta("first"), LlmStreamEvent.Completed()),
                    eventDelayMillis = 100L,
                )
            val received = mutableListOf<LlmStreamEvent>()

            val job = launch { model.stream(request).collect { received += it } }
            advanceTimeBy(150L)
            job.cancelAndJoin()

            assertEquals(1, model.cancellationCount)
            assertTrue(received.any { it is LlmStreamEvent.Delta })
            assertTrue(received.none { it is LlmStreamEvent.Completed })
        }

    @Test
    fun closeReleasesTheAdapter() =
        runTest {
            val model = FakeLanguageModel()

            assertNull(model.lastRequest)
            model.close()

            assertTrue(model.closed)
        }
}
