package com.voicechat.assistant.memory

import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.db.MemoryDao
import com.voicechat.assistant.data.db.MemoryFactEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * Device-local memory: learns durable facts from what the user says, ranks them
 * against a question and shapes them for the prompt.
 *
 * Everything here is SQLite plus string work. No model runs on the device, no
 * embedding is computed and nothing leaves the phone except the few fact
 * sentences that end up in a prompt.
 */
class MemoryStore(
    private val dao: MemoryDao,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    companion object {
        /** Hard ceiling for one person's facts; the oldest and least used go first. */
        const val MAX_FACTS = 400
    }

    val facts: Flow<List<MemoryFact>> = dao.observeAll().map { rows ->
        rows.map { it.toDomain() }
    }

    suspend fun snapshot(): List<MemoryFact> = dao.all().map { it.toDomain() }

    /**
     * Runs the on-device extractor over one user turn. Returns the facts that
     * were written, which is what the log line reports.
     */
    suspend fun learn(text: String, chatId: String?): List<MemoryCandidate> {
        val candidates = MemoryExtractor.extract(text)
        if (candidates.isEmpty()) return emptyList()
        val stored = candidates.filter { store(it, chatId) }
        if (stored.isNotEmpty()) {
            AppLog.info(
                LogCategory.MEMORY,
                "Learned ${stored.size} fact(s)",
                stored.joinToString("; ") { it.text },
            )
        }
        return stored
    }

    /** An explicit "remember that …" from the model or the user. Always pinned. */
    suspend fun remember(text: String, kind: MemoryKind?, chatId: String?): MemoryCandidate? {
        val value = MemoryGuard.sanitize(text)
        if (value.isEmpty() || !MemoryGuard.isStorable(value)) return null
        val candidate = MemoryCandidate(
            kind = kind ?: MemoryKind.NOTE,
            subject = MemoryExtractor.normalizeSubject(value),
            text = value,
            confidence = 0.95f,
            pinned = true,
            keywords = MemoryRanker.tokens(value).joinToString(" "),
        )
        return candidate.takeIf { store(it, chatId) }
    }

    /**
     * The standing block a session starts with: pinned facts plus the most
     * confident recent ones. Reading it does not count as a use, so the block
     * cannot freeze on itself.
     */
    suspend fun sessionBlock(limit: Int = MemoryRanker.SESSION_LIMIT): String {
        val all = snapshot()
        if (all.isEmpty()) return ""
        return MemoryPrompt.sessionBlock(MemoryRanker.sessionFacts(all, clock(), limit))
    }

    /** Facts relevant to one turn, as a ready-to-append block. */
    suspend fun turnBlock(query: String, limit: Int = MemoryRanker.TURN_LIMIT): String =
        MemoryPrompt.turnBlock(recall(query, limit))

    suspend fun recall(query: String, limit: Int = MemoryRanker.TURN_LIMIT): List<MemoryFact> {
        if (query.isBlank()) return emptyList()
        val ranked = MemoryRanker.rank(snapshot(), query, clock(), limit)
        if (ranked.isNotEmpty()) {
            dao.markUsed(ranked.map { it.id }, clock())
            AppLog.debug(
                LogCategory.MEMORY,
                "Recalled ${ranked.size} fact(s)",
                ranked.joinToString("; ") { it.text.take(80) },
            )
        }
        return ranked
    }

    /** Removes the fact that best answers [query]. */
    suspend fun forget(query: String): MemoryFact? {
        val match = recall(query, 1).firstOrNull() ?: return null
        dao.delete(match.id)
        AppLog.info(LogCategory.MEMORY, "Forgot a fact", match.text.take(120))
        return match
    }

    suspend fun update(id: String, text: String) {
        val value = MemoryGuard.sanitize(text)
        if (value.isEmpty() || !MemoryGuard.isStorable(value)) return
        dao.edit(id, value, MemoryRanker.tokens(value).joinToString(" "), clock())
    }

    suspend fun delete(id: String) {
        dao.delete(id)
    }

    suspend fun clear() {
        dao.deleteAll()
        AppLog.info(LogCategory.MEMORY, "Memory erased")
    }

    /**
     * Updates the row with the same `kind:subject` key instead of inserting a
     * twin, which is why "I live in Berlin" said twice stays one fact.
     */
    private suspend fun store(candidate: MemoryCandidate, chatId: String?): Boolean {
        val text = MemoryGuard.sanitize(candidate.text)
        if (text.isEmpty() || !MemoryGuard.isStorable(text)) return false
        val now = clock()
        val existing = dao.findBySubject(candidate.kind.name, candidate.subject)
        val row = if (existing == null) {
            MemoryFactEntity(
                id = UUID.randomUUID().toString(),
                kind = candidate.kind.name,
                subject = candidate.subject,
                text = text,
                keywords = candidate.keywords,
                confidence = candidate.confidence.coerceIn(0f, 1f),
                pinned = candidate.pinned,
                createdAt = now,
                updatedAt = now,
                lastUsedAt = now,
                useCount = 0,
                sourceChatId = chatId,
            )
        } else {
            existing.copy(
                text = text,
                keywords = candidate.keywords.ifBlank { existing.keywords },
                confidence = maxOf(existing.confidence, candidate.confidence),
                pinned = existing.pinned || candidate.pinned,
                updatedAt = now,
                sourceChatId = chatId ?: existing.sourceChatId,
            )
        }
        dao.upsert(row)
        enforceCap()
        return true
    }

    private suspend fun enforceCap() {
        val count = dao.count()
        if (count <= MAX_FACTS) return
        dao.prune(count - MAX_FACTS)
        AppLog.debug(LogCategory.MEMORY, "Pruned memory", "kept $MAX_FACTS facts")
    }
}

internal fun MemoryFactEntity.toDomain(): MemoryFact = MemoryFact(
    id = id,
    kind = MemoryKind.entries.firstOrNull { it.name == kind } ?: MemoryKind.NOTE,
    subject = subject,
    text = text,
    keywords = keywords,
    confidence = confidence,
    pinned = pinned,
    createdAt = createdAt,
    updatedAt = updatedAt,
    lastUsedAt = lastUsedAt,
    useCount = useCount,
    sourceChatId = sourceChatId,
)
