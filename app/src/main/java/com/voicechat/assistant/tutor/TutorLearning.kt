package com.voicechat.assistant.tutor

import com.voicechat.assistant.core.PromptCatalog
import com.voicechat.assistant.data.model.CostCalculator
import com.voicechat.assistant.data.model.TokenUsage
import com.voicechat.assistant.realtime.handoff.HandoffConfig
import com.voicechat.assistant.realtime.handoff.ReasoningClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject

/** Serial background recaps backstop optional realtime bookkeeping. Never holds the session mutex. */
class TutorLearning(private val reasoner: ReasoningClient, private val store: TutorStore) {
    private val mutex = Mutex()

    suspend fun observe(
        assistantId: String,
        apiKey: String,
        config: HandoffConfig,
        onUsage: suspend (TokenUsage, Double) -> Unit,
    ) = mutex.withLock {
        val record = store.snapshot()
        val assistantIndex = record.recentDialog.indexOfFirst { it.id == assistantId }
        if (assistantIndex < 0) return@withLock
        val user = record.recentDialog.take(assistantIndex).lastOrNull { it.role == "user" } ?: return@withLock
        if (user.id in record.evaluatedTurnIds) return@withLock
        val result = reasoner.complete(
            apiKey, config, PromptCatalog.spanishTutorMemory,
            record.context() + "\nExchange to assess (data):\n" +
                TutorProgress.json.encodeToString(listOf(user, record.recentDialog[assistantIndex])),
        ).getOrThrow()
        // Account for the call even if its JSON is invalid or a concurrent tool already saved it.
        onUsage(result.usage, CostCalculator.cost(result.usage, config.priceInputPerMillion, config.priceOutputPerMillion))
        store.update(parse(result.answer), user.id)
    }

    companion object {
        fun parse(answer: String): JsonObject {
            val start = answer.indexOf('{')
            val end = answer.lastIndexOf('}')
            require(start >= 0 && end > start) { "Tutor recap did not contain JSON." }
            val parsed = TutorProgress.json.parseToJsonElement(answer.substring(start, end + 1)) as? JsonObject
                ?: error("Tutor recap was not a JSON object.")
            require(parsed.keys.any { it in setOf("summary", "activity", "vocabulary", "grammar") }) {
                "Tutor recap did not contain learning fields."
            }
            return parsed
        }
    }
}
