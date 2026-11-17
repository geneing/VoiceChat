package com.voicechat.assistant.memory

import kotlin.math.exp
import kotlin.math.ln

/**
 * Picks which remembered facts belong in a prompt.
 *
 * The corpus is tiny by design (a few hundred facts at most), so a linear
 * lexical pass is cheaper than an index and needs no model, no embeddings and no
 * native runtime: tokenising one sentence costs microseconds and the whole
 * ranking is a few hundred string-set lookups. IDF keeps words that appear in
 * every fact ("user") from matching on their own.
 */
object MemoryRanker {

    /** Facts in the session block: enough to know the person, not a wall of text. */
    const val SESSION_LIMIT = 12

    /** Facts attached to one turn. */
    const val TURN_LIMIT = 4

    private const val RECENCY_HALF_LIFE_DAYS = 30.0
    private const val PINNED_BOOST = 1.5
    private const val RECENCY_WEIGHT = 0.6
    private const val USAGE_WEIGHT = 0.15
    private const val DAY_MS = 86_400_000.0

    private val TOKEN = Regex("[\\p{L}\\p{N}]+")

    private val STOPWORDS = setOf(
        "the", "a", "an", "and", "or", "but", "to", "of", "in", "on", "at", "for",
        "with", "is", "are", "was", "were", "be", "been", "being", "it", "this",
        "that", "these", "those", "i", "you", "he", "she", "we", "they", "my",
        "your", "his", "her", "our", "their", "me", "him", "us", "them", "do",
        "does", "did", "have", "has", "had", "can", "could", "should", "would",
        "will", "just", "really", "very", "so", "like", "what", "when", "where",
        "who", "how", "why", "about", "from", "as", "if", "then", "than", "there",
        "here", "not", "no", "yes", "ok", "okay", "please", "thanks", "thank",
        "hey", "hi", "hello", "um", "uh", "know", "think", "tell", "say", "also",
        "get", "got", "go", "going", "want", "need", "make", "much", "many",
        "some", "any", "all", "one", "two", "good", "bad", "sure", "right",
        "user", "users", "remember", "memory",
    )

    /**
     * Lowercase word tokens, plus character bigrams for scripts written without
     * spaces so a Chinese or Japanese sentence still matches a stored fact.
     */
    fun tokens(text: String): List<String> {
        val out = LinkedHashSet<String>()
        TOKEN.findAll(text.lowercase()).forEach { match ->
            val token = match.value
            if (token.length < 2) return@forEach
            if (token.any { it.isUnspacedScript() }) {
                if (token.length == 2) {
                    out += token
                } else {
                    for (i in 0 until token.length - 1) out += token.substring(i, i + 2)
                }
            } else {
                out += token
            }
        }
        return out.toList()
    }

    /**
     * Facts relevant to [query], best first. A query that shares no meaningful
     * word with any fact (small talk, a new topic) returns nothing, which is what
     * keeps prompts clean; the corpus is small enough that one real word hit is
     * signal, so there is no absolute score floor — the caller's [limit] bounds
     * the block instead.
     */
    fun rank(
        facts: List<MemoryFact>,
        query: String,
        now: Long,
        limit: Int = TURN_LIMIT,
    ): List<MemoryFact> {
        if (facts.isEmpty() || limit <= 0) return emptyList()
        val queryTokens = tokens(query).filterNot { it in STOPWORDS }.distinct()
        if (queryTokens.isEmpty()) return emptyList()
        val factTokens = facts.associate { fact ->
            fact.id to tokens("${fact.subject} ${fact.text} ${fact.keywords}").toSet()
        }
        val total = facts.size.toDouble()
        val idf = queryTokens.associateWith { token ->
            val documentFrequency = factTokens.values.count { token in it }
            ln(1.0 + total / (1.0 + documentFrequency))
        }
        return facts
            .mapNotNull { fact ->
                val present = factTokens[fact.id].orEmpty()
                var score = 0.0
                queryTokens.forEach { token -> if (token in present) score += idf.getValue(token) }
                if (score <= 0.0) return@mapNotNull null
                if (fact.pinned) score += PINNED_BOOST
                score += recencyBoost(fact, now) + usageBoost(fact)
                fact to score
            }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /**
     * The standing block every session starts with: pinned facts first, then
     * whatever is confident, recent and actually used.
     */
    fun sessionFacts(
        facts: List<MemoryFact>,
        now: Long,
        limit: Int = SESSION_LIMIT,
    ): List<MemoryFact> = facts
        .sortedWith(
            compareByDescending<MemoryFact> { it.pinned }
                .thenByDescending { it.confidence + recencyBoost(it, now) + usageBoost(it) }
                .thenByDescending { it.updatedAt },
        )
        .take(limit)

    private fun recencyBoost(fact: MemoryFact, now: Long): Double {
        val ageDays = ((now - fact.updatedAt).coerceAtLeast(0L)) / DAY_MS
        return RECENCY_WEIGHT * exp(-ageDays / RECENCY_HALF_LIFE_DAYS)
    }

    private fun usageBoost(fact: MemoryFact): Double =
        USAGE_WEIGHT * ln(1.0 + fact.useCount.coerceAtLeast(0))

    private fun Char.isUnspacedScript(): Boolean =
        this in '\u3040'..'\u30FF' ||
            this in '\u3400'..'\u4DBF' ||
            this in '\u4E00'..'\u9FFF' ||
            this in '\uAC00'..'\uD7AF'
}
