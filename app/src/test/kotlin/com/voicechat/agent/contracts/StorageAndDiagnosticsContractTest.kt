package com.voicechat.agent.contracts

import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.fake.FakeModelAvailabilityProvider
import com.voicechat.agent.fake.InMemoryConversationRepository
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageAndDiagnosticsContractTest {
    private fun conversation(
        id: String,
        updatedAt: Long,
    ): Conversation =
        Conversation(
            id = ConversationId(id),
            createdAtEpochMillis = 1L,
            updatedAtEpochMillis = updatedAt,
            turns =
                listOf(
                    UserTurn(TurnId("$id-turn-1"), Transcript.final("hello"), UserTurnSource.VOICE),
                ),
        )

    @Test
    fun conversationsArePersistedLoadedAndOrderedByUpdateTime() =
        runTest {
            val repository = InMemoryConversationRepository()

            repository.save(conversation("older", updatedAt = 10L))
            repository.save(conversation("newer", updatedAt = 20L))

            val summaries = repository.observeConversations().first()
            assertEquals(listOf(ConversationId("newer"), ConversationId("older")), summaries.map { it.id })
            assertEquals(1, summaries.first().turnCount)
            assertEquals(1, repository.load(ConversationId("newer"))?.turns?.size)
        }

    @Test
    fun deletingAConversationRemovesIt() =
        runTest {
            val repository = InMemoryConversationRepository()
            repository.save(conversation("conversation-1", updatedAt = 10L))

            repository.delete(ConversationId("conversation-1"))

            assertNull(repository.load(ConversationId("conversation-1")))
            assertTrue(repository.observeConversations().first().isEmpty())
        }

    @Test
    fun persistenceFailureIsATypedException() =
        runTest {
            val repository = InMemoryConversationRepository()
            repository.failOnNextSave = VoiceAgentError(ErrorCode.PERSISTENCE_FAILED)

            val thrown =
                assertThrows(VoiceAgentException::class.java) {
                    kotlinx.coroutines.runBlocking { repository.save(conversation("conversation-1", updatedAt = 10L)) }
                }

            assertEquals(ErrorCode.PERSISTENCE_FAILED, thrown.error.code)
        }

    @Test
    fun modelAvailabilityIsExplicitAndUnknownTasksAreEmpty() =
        runTest {
            val ready =
                ModelAvailability.Ready(
                    ModelDescriptor(
                        id = ModelId("model-1"),
                        task = ModelTask.LANGUAGE_MODEL,
                        displayName = "Model 1",
                        runtime = ModelRuntime.REMOTE_API,
                        providerId = ProviderId("provider"),
                    ),
                )
            val unavailable =
                ModelAvailability.Unavailable(
                    model =
                        ModelDescriptor(
                            id = ModelId("model-2"),
                            task = ModelTask.LANGUAGE_MODEL,
                            displayName = "Model 2",
                            runtime = ModelRuntime.REMOTE_API,
                        ),
                    error = VoiceAgentError(ErrorCode.MODEL_UNAVAILABLE),
                )
            val provider =
                FakeModelAvailabilityProvider(
                    availability = mapOf(ModelTask.LANGUAGE_MODEL to listOf(ready, unavailable)),
                )

            assertEquals(listOf(ready, unavailable), provider.observe(ModelTask.LANGUAGE_MODEL).first())
            assertTrue(provider.observe(ModelTask.SPEECH_TO_TEXT).first().isEmpty())
        }

    @Test
    fun diagnosticsRecordsEventsInOrder() {
        val sink = RecordingDiagnosticsSink()
        val turnId = TurnId("turn-1")
        val traceId = TraceId("trace-1")
        val started =
            DiagnosticEvent(
                stage = DiagnosticStage.LLM_REQUEST,
                outcome = DiagnosticOutcome.STARTED,
                monotonicTimeNanos = 1_000L,
                turnId = turnId,
                traceId = traceId,
            )
        val completed = started.copy(outcome = DiagnosticOutcome.COMPLETED, durationNanos = 500L)

        sink.record(started)
        sink.record(completed)

        assertEquals(listOf(started, completed), sink.events)
        assertEquals(listOf(DiagnosticOutcome.STARTED, DiagnosticOutcome.COMPLETED), sink.events.map { it.outcome })
        assertTrue(sink.isSingleTrace)
        assertEquals(listOf(traceId), sink.traceIds)
        assertTrue(sink.events.all { it.traceId == traceId })
    }

    @Test
    fun noOpDiagnosticsSinkAcceptsEvents() {
        NoOpDiagnosticsSink.record(
            DiagnosticEvent(
                stage = DiagnosticStage.AUDIO_INPUT,
                outcome = DiagnosticOutcome.STARTED,
                monotonicTimeNanos = 0L,
            ),
        )
    }
}
