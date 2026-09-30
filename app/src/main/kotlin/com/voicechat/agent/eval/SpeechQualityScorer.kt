package com.voicechat.agent.eval

/**
 * Error buckets M25 reports alongside WER/CER.
 *
 * Recognition quality is not only an aggregate number: the milestone calls out
 * names, numbers, negation, correction, and acknowledgement errors by condition
 * because those carry the most meaning in a voice agent. A bucket is present in
 * [SpeechQualityResult.categories] when at least one reference token in that
 * bucket was substituted or deleted.
 */
enum class SpeechErrorCategory(
    val id: String,
) {
    NAME("name"),
    NUMBER("number"),
    NEGATION("negation"),
    CORRECTION("correction"),
    ACKNOWLEDGEMENT("acknowledgement"),
    OTHER("other"),
}

/**
 * Reference-versus-hypothesis scoring for one utterance.
 *
 * [wer] and [cer] are computed with the standard edit-distance definitions over
 * normalized word and character sequences. Category buckets are best-effort,
 * lexicon-based aids for triage, not a second correctness definition; the
 * aggregate errors are always exact for the normalized strings scored.
 */
data class SpeechQualityResult(
    val referenceWordCount: Int,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    val referenceCharacterCount: Int,
    val characterErrors: Int,
    val categories: Set<SpeechErrorCategory>,
) {
    /** All word-level edits. */
    val wordErrors: Int get() = substitutions + deletions + insertions

    /**
     * Word error rate. Empty references score `0.0` when the hypothesis is also
     * empty and `1.0` (all inserted) otherwise, matching the usual convention.
     */
    val wer: Double
        get() =
            when {
                referenceWordCount == 0 -> if (wordErrors == 0) 0.0 else 1.0
                else -> wordErrors.toDouble() / referenceWordCount
            }

    /** Character error rate over the normalized reference characters. */
    val cer: Double
        get() =
            when {
                referenceCharacterCount == 0 -> if (characterErrors == 0) 0.0 else 1.0
                else -> characterErrors.toDouble() / referenceCharacterCount
            }
}

/**
 * Deterministic, platform-free reference scoring for the M25 speech-quality
 * corpus.
 *
 * Normalization is intentionally conservative and fixed so a WER recorded today
 * is comparable to one recorded later: lower-case, Unicode-normalize, strip
 * punctuation (keeping intra-word apostrophes), collapse whitespace. No number
 * expansion or filler removal happens here, because those choices change what
 * "an error" means and belong to a versioned corpus policy if ever added.
 *
 * This class measures; it does not run a recognizer. Its inputs are the
 * reference and hypothesis strings produced by a labeled run, so it is fully
 * JVM-testable and reproducible from the frozen fixtures.
 */
object SpeechQualityScorer {
    /** @return reference/hypothesis scores with the aggregate and category detail. */
    fun score(
        reference: String,
        hypothesis: String,
    ): SpeechQualityResult {
        val referenceRawTokens = rawTokens(reference)
        val referenceTokens = referenceRawTokens.map(::normalizeToken).filter { it.isNotEmpty() }
        val hypothesisTokens = rawTokens(hypothesis).map(::normalizeToken).filter { it.isNotEmpty() }

        val alignment = align(referenceTokens, hypothesisTokens)
        val categories = mutableSetOf<SpeechErrorCategory>()
        for (case in alignment.referenceEdits) {
            val raw = referenceRawTokens.getOrNull(case.referenceIndex) ?: continue
            categories += classify(raw, referenceRawTokens, index = case.referenceIndex)
        }
        if (alignment.insertions > 0 && categories.isEmpty()) categories += SpeechErrorCategory.OTHER

        val referenceCharacters = referenceTokens.joinToString("")
        val hypothesisCharacters = hypothesisTokens.joinToString("")
        return SpeechQualityResult(
            referenceWordCount = referenceTokens.size,
            substitutions = alignment.substitutions,
            deletions = alignment.deletions,
            insertions = alignment.insertions,
            referenceCharacterCount = referenceCharacters.length,
            characterErrors = characterEditDistance(referenceCharacters, hypothesisCharacters),
            categories = categories,
        )
    }

    /** Case-folded, punctuation-stripped token list used for WER. */
    fun tokenize(text: String): List<String> = rawTokens(text).map(::normalizeToken).filter { it.isNotEmpty() }

    /** Normalizes one token: lower-case, strip punctuation, keep intra-word apostrophes. */
    private fun normalizeToken(token: String): String =
        token
            .lowercase()
            .map { char -> if (char.isLetterOrDigit() || char == '\'') char else ' ' }
            .joinToString("")
            .trim()
            .trim('\'')

    private fun rawTokens(text: String): List<String> = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }

    private fun classify(
        rawToken: String,
        rawTokens: List<String>,
        index: Int,
    ): SpeechErrorCategory {
        val bare = rawToken.trim().trim(*PUNCTUATION).lowercase()
        return when {
            bare.isEmpty() -> SpeechErrorCategory.OTHER
            NUMBER.matches(bare) || bare in NUMBER_WORDS -> SpeechErrorCategory.NUMBER
            bare in NEGATIONS -> SpeechErrorCategory.NEGATION
            bare in CORRECTIONS -> SpeechErrorCategory.CORRECTION
            bare in ACKNOWLEDGEMENTS -> SpeechErrorCategory.ACKNOWLEDGEMENT
            isProperNoun(rawToken, rawTokens, index) -> SpeechErrorCategory.NAME
            else -> SpeechErrorCategory.OTHER
        }
    }

    /** A capitalized token that does not start a sentence reads as a proper noun. */
    private fun isProperNoun(
        rawToken: String,
        rawTokens: List<String>,
        index: Int,
    ): Boolean {
        val first = rawToken.firstOrNull() ?: return false
        if (!first.isUpperCase()) return false
        val previous = rawTokens.getOrNull(index - 1) ?: return true
        return previous.lastOrNull() !in SENTENCE_END
    }

    // --- edit-distance alignment -------------------------------------------------

    private data class ReferenceEdit(
        val referenceIndex: Int,
    )

    private data class Alignment(
        val substitutions: Int,
        val deletions: Int,
        val insertions: Int,
        val referenceEdits: List<ReferenceEdit>,
    )

    /**
     * Word-level Levenshtein alignment with backtrace, so a mismatched reference
     * token can be attributed to a category. Ties are broken toward substitution,
     * then deletion, then insertion, deterministically.
     */
    private fun align(
        reference: List<String>,
        hypothesis: List<String>,
    ): Alignment {
        val n = reference.size
        val m = hypothesis.size
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i
        for (j in 0..m) cost[0][j] = j
        for (i in 1..n) {
            for (j in 1..m) {
                val substitution = cost[i - 1][j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
                val deletion = cost[i - 1][j] + 1
                val insertion = cost[i][j - 1] + 1
                cost[i][j] = minOf(substitution, deletion, insertion)
            }
        }

        var i = n
        var j = m
        var substitutions = 0
        var deletions = 0
        var insertions = 0
        val referenceEdits = mutableListOf<ReferenceEdit>()
        while (i > 0 || j > 0) {
            val substitution =
                if (i > 0 && j > 0) cost[i - 1][j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1 else Int.MAX_VALUE
            val deletion = if (i > 0) cost[i - 1][j] + 1 else Int.MAX_VALUE
            val insertion = if (j > 0) cost[i][j - 1] + 1 else Int.MAX_VALUE
            when (minOf(substitution, deletion, insertion)) {
                substitution -> {
                    if (reference[i - 1] != hypothesis[j - 1]) {
                        substitutions++
                        referenceEdits += ReferenceEdit(i - 1)
                    }
                    i--
                    j--
                }

                deletion -> {
                    deletions++
                    referenceEdits += ReferenceEdit(i - 1)
                    i--
                }

                else -> {
                    insertions++
                    j--
                }
            }
        }
        return Alignment(substitutions, deletions, insertions, referenceEdits)
    }

    private fun characterEditDistance(
        reference: String,
        hypothesis: String,
    ): Int {
        if (reference.isEmpty()) return hypothesis.length
        if (hypothesis.isEmpty()) return reference.length
        var previous = IntArray(hypothesis.length + 1) { it }
        var current = IntArray(hypothesis.length + 1)
        for (i in 1..reference.length) {
            current[0] = i
            for (j in 1..hypothesis.length) {
                val substitution =
                    previous[j - 1] + if (reference[i - 1] == hypothesis[j - 1]) 0 else 1
                current[j] = minOf(substitution, previous[j] + 1, current[j - 1] + 1)
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[hypothesis.length]
    }

    private val WHITESPACE = Regex("\\s+")
    private val NUMBER = Regex("^[+-]?\\d+([.,]\\d+)*$")
    private val PUNCTUATION = charArrayOf('.', ',', '?', '!', ';', ':', '"', '\'', '(', ')', '[', ']', '-')
    private val SENTENCE_END = setOf('.', '?', '!')
    private val NUMBER_WORDS =
        setOf(
            "zero",
            "one",
            "two",
            "three",
            "four",
            "five",
            "six",
            "seven",
            "eight",
            "nine",
            "ten",
            "eleven",
            "twelve",
            "thirteen",
            "fourteen",
            "fifteen",
            "sixteen",
            "seventeen",
            "eighteen",
            "nineteen",
            "twenty",
            "thirty",
            "forty",
            "fifty",
            "sixty",
            "seventy",
            "eighty",
            "ninety",
            "hundred",
            "thousand",
            "million",
            "billion",
        )
    private val NEGATIONS =
        setOf(
            "no",
            "not",
            "never",
            "none",
            "nothing",
            "nobody",
            "nowhere",
            "neither",
            "nor",
            "cannot",
            "don't",
            "doesn't",
            "didn't",
            "isn't",
            "aren't",
            "wasn't",
            "weren't",
            "won't",
            "can't",
            "couldn't",
            "shouldn't",
            "wouldn't",
            "haven't",
            "hasn't",
            "hadn't",
        )
    private val CORRECTIONS =
        setOf("actually", "sorry", "rather", "instead", "correction", "oops")
    private val ACKNOWLEDGEMENTS =
        setOf("okay", "ok", "yes", "yeah", "yep", "right", "sure", "mm", "hmm", "uh", "huh", "thanks")
}
