package com.voicechat.agent.persistence

import com.voicechat.agent.domain.AssistantDelivery
import com.voicechat.agent.domain.AssistantTurn
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.DeliveryState
import com.voicechat.agent.domain.GeneratedText
import com.voicechat.agent.domain.GenerationState
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TranscriptRevision
import com.voicechat.agent.domain.Turn
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.UserTurnSource

/**
 * Mapping at the persistence boundary.
 *
 * Domain types are platform-free; Room rows are a storage detail. These functions
 * are the only place the two representations meet, so the domain never imports
 * Room. Enum values are stored by name to keep the schema a stable contract.
 */

internal fun Conversation.toEntity(): ConversationEntity =
    ConversationEntity(
        id = id.value,
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        title = title,
    )

internal fun Conversation.toTurnEntities(): List<TurnEntity> =
    turns.mapIndexed { position, turn -> turn.toEntity(conversationId = id.value, position = position) }

internal fun Turn.toEntity(
    conversationId: String,
    position: Int,
): TurnEntity =
    when (this) {
        is UserTurn -> {
            TurnEntity(
                id = id.value,
                conversationId = conversationId,
                position = position,
                kind = TurnKind.USER,
                transcriptText = transcript.text,
                transcriptRevision = transcript.revision.value,
                transcriptIsFinal = transcript.isFinal,
                transcriptLanguageTag = transcript.languageTag,
                transcriptConfidence = transcript.confidence,
                userTurnSource = source.name,
            )
        }

        is AssistantTurn -> {
            TurnEntity(
                id = id.value,
                conversationId = conversationId,
                position = position,
                kind = TurnKind.ASSISTANT,
                generatedText = generated.text,
                generationState = generated.state.name,
                deliveredText = delivery.deliveredText,
                deliveryState = delivery.state.name,
            )
        }
    }

internal fun ConversationEntity.toDomain(turns: List<TurnEntity>): Conversation =
    Conversation(
        id = ConversationId(id),
        createdAtEpochMillis = createdAtEpochMillis,
        updatedAtEpochMillis = updatedAtEpochMillis,
        title = title,
        turns = turns.map { it.toDomain() },
    )

internal fun TurnEntity.toDomain(): Turn =
    when (kind) {
        TurnKind.USER -> {
            UserTurn(
                id = TurnId(id),
                transcript =
                    Transcript(
                        text = requireNotNull(transcriptText) { "user turn $id is missing transcriptText" },
                        revision = TranscriptRevision(requireNotNull(transcriptRevision) { "user turn $id is missing revision" }),
                        isFinal = requireNotNull(transcriptIsFinal) { "user turn $id is missing isFinal" },
                        languageTag = transcriptLanguageTag,
                        confidence = transcriptConfidence,
                    ),
                source = enumValue(UserTurnSource.entries, userTurnSource, "userTurnSource", id),
            )
        }

        TurnKind.ASSISTANT -> {
            AssistantTurn(
                id = TurnId(id),
                generated =
                    GeneratedText(
                        text = requireNotNull(generatedText) { "assistant turn $id is missing generatedText" },
                        state = enumValue(GenerationState.entries, generationState, "generationState", id),
                    ),
                delivery =
                    AssistantDelivery(
                        deliveredText = requireNotNull(deliveredText) { "assistant turn $id is missing deliveredText" },
                        state = enumValue(DeliveryState.entries, deliveryState, "deliveryState", id),
                    ),
            )
        }

        else -> {
            error("unknown turn kind '$kind' for turn $id")
        }
    }

internal fun ConversationSummaryRow.toDomain(): ConversationSummary =
    ConversationSummary(
        id = ConversationId(id),
        updatedAtEpochMillis = updatedAtEpochMillis,
        title = title,
        turnCount = turnCount,
    )

private fun <T : Enum<T>> enumValue(
    entries: List<T>,
    stored: String?,
    field: String,
    turnId: String,
): T {
    val name = requireNotNull(stored) { "turn $turnId is missing $field" }
    return entries.firstOrNull { it.name == name }
        ?: error("turn $turnId has unknown $field '$name'")
}
