package com.voicechat.agent.ui

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.LlmRequest
import com.voicechat.agent.contracts.LlmStreamEvent
import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.GeneratedText
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.ProviderId
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion

/** Deterministic, readable conversation and turn IDs for assertions. */
class SequentialConversationIdFactory(
    private val prefix: String = "test",
) : ConversationIdFactory {
    private var conversations = 0
    private var turns = 0

    override fun newConversationId(): ConversationId = ConversationId("$prefix-conversation-${++conversations}")

    override fun newTurnId(): TurnId = TurnId("$prefix-turn-${++turns}")
}

/**
 * [LanguageModel] that streams a different script per collection, so a test can
 * fail the first attempt and succeed on a retry. Deterministic and offline.
 */
class ScriptedLanguageModel(
    override val providerId: ProviderId = ProviderId("test-provider"),
    private val scripts: List<List<LlmStreamEvent>>,
    private val eventDelayMillis: Long = 0L,
) : LanguageModel {
    /** The most recent request, for asserting request construction. */
    var lastRequest: LlmRequest? = null
        private set

    /** Number of times [stream] was collected. */
    var streamCount: Int = 0
        private set

    /** Number of collections cancelled by their consumer. */
    var cancellationCount: Int = 0
        private set

    override fun stream(request: LlmRequest): Flow<LlmStreamEvent> =
        flow {
            lastRequest = request
            val script = scripts.getOrElse(streamCount) { scripts.last() }
            streamCount++
            script.forEach { event ->
                if (eventDelayMillis > 0L) delay(eventDelayMillis)
                emit(event)
            }
        }.onCompletion { cause ->
            if (cause is CancellationException) cancellationCount++
        }

    override suspend fun close() = Unit
}

/** Builds a test [Conversation]. */
fun testConversation(
    id: String,
    updatedAt: Long,
    title: String? = null,
    turns: List<Turn> = emptyList(),
): Conversation =
    Conversation(
        id = ConversationId(id),
        createdAtEpochMillis = 1L,
        updatedAtEpochMillis = updatedAt,
        title = title,
        turns = turns,
    )

/** Builds a committed test [UserTurn]. */
fun testUserTurn(
    id: String,
    text: String,
    source: UserTurnSource = UserTurnSource.TEXT,
): UserTurn =
    UserTurn(
        id = TurnId(id),
        transcript = Transcript.final(text),
        source = source,
    )

/** Builds a test [AssistantTurn]. */
fun testAssistantTurn(
    id: String,
    generated: String,
    delivered: String = generated,
    generationState: GenerationState = GenerationState.COMPLETED,
    deliveryState: DeliveryState = DeliveryState.COMPLETED,
): AssistantTurn =
    AssistantTurn(
        id = TurnId(id),
        generated = GeneratedText(text = generated, state = generationState),
        delivery = AssistantDelivery(deliveredText = delivered, state = deliveryState),
    )
