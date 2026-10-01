package com.voicechat.agent.orchestration

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmCapabilities
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ModelId
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.TurnPhase
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for the M21 turn orchestration.
 *
 * It runs the **real** [TurnOrchestrator] and [TurnStateMachine] on the device
 * with an inline fake provider and an in-memory repository, proving the pipeline
 * compiles and completes a turn to a truthful persisted state under the Android
 * runtime. It uses no network, credentials, microphone, or real TTS, so it is a
 * structural smoke check, not a provider/voice measurement (see `Tests.md`).
 *
 * Compiled by `:app:assembleDebugAndroidTest`; run only on a device with
 * `:app:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class TurnOrchestrationInstrumentedTest {
    private val selection = ProviderModelSelection(ProviderId("device-fake"), ModelId("device-model"))

    @Test
    fun aFakeTurnCompletesAndPersistsTruthfullyOnDevice() =
        runBlocking {
            val repository = InlineRepository()
            val orchestrator =
                TurnOrchestrator(
                    repository = repository,
                    languageModel = InlineLanguageModel(selection.providerId),
                    textToSpeech = null,
                    diagnostics = NoOpDiagnosticsSink,
                    clock = SystemMonotonicClock,
                    wallClock = { 1L },
                    assistantTurnId = { TurnId("assistant-1") },
                )
            val conversation =
                Conversation(
                    id = ConversationId("device-conversation"),
                    createdAtEpochMillis = 1L,
                    updatedAtEpochMillis = 1L,
                )

            val result =
                orchestrator.run(
                    TurnRequest(
                        conversation = conversation,
                        userTurn = UserTurn(TurnId("u1"), Transcript.final("hello device"), UserTurnSource.TEXT),
                        selection = selection,
                    ),
                )

            assertEquals(TurnPhase.COMPLETED, result.record?.phase)
            val persisted = repository.load(ConversationId("device-conversation"))
            assertTrue(persisted != null)
            assertEquals(2, persisted!!.turns.size)
            val assistant = persisted.turns[1] as AssistantTurn
            assertEquals("Hello device", assistant.generated.text)
            assertEquals(GenerationState.COMPLETED, assistant.generated.state)
            assertEquals(DeliveryState.COMPLETED, assistant.delivery.state)
        }

    private class InlineLanguageModel(
        override val providerId: ProviderId,
    ) : LanguageModel {
        override val capabilities: LlmCapabilities = LlmCapabilities()

        override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
            flowOf(
                LlmStreamEvent.Delta("Hello"),
                LlmStreamEvent.Delta(" device"),
                LlmStreamEvent.Completed(),
            )

        override suspend fun close() = Unit
    }

    private class InlineRepository : ConversationRepository {
        private val conversations = MutableStateFlow<Map<ConversationId, Conversation>>(emptyMap())

        override fun observeConversations(): Flow<List<ConversationSummary>> =
            conversations.map { stored ->
                stored.values.map {
                    ConversationSummary(id = it.id, updatedAtEpochMillis = it.updatedAtEpochMillis, turnCount = it.turns.size)
                }
            }

        override suspend fun load(id: ConversationId): Conversation? = conversations.value[id]

        override suspend fun save(conversation: Conversation) {
            conversations.value = conversations.value + (conversation.id to conversation)
        }

        override suspend fun saveTurn(
            conversation: Conversation,
            turn: Turn,
        ) {
            conversations.value =
                conversations.value +
                (
                    conversation.id to
                        (
                            conversations.value[conversation.id]?.let { existing ->
                                conversation.copy(turns = existing.turns.filterNot { it.id == turn.id } + turn)
                            } ?: conversation
                        )
                )
        }

        override suspend fun delete(id: ConversationId) {
            conversations.value = conversations.value - id
        }
    }
}
