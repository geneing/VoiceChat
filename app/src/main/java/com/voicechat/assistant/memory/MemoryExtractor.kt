package com.voicechat.assistant.memory

/**
 * Pulls durable facts out of what the user said, on the device, without a model.
 *
 * The rules are deliberately narrow: one false fact is copied into every later
 * session's instructions, while a missed one costs nothing. So only phrasings
 * that state something lasting about the user produce a candidate, and every
 * capture is cleaned before it is stored. Pure, so the behaviour is unit tested
 * without a device.
 */
object MemoryExtractor {

    private const val MAX_VALUE_CHARS = 120

    private val WHITESPACE = Regex("\\s+")
    private val TRAILING_CONJUNCTION = Regex(
        "(?i)\\s+(?:and|but|because|so|when|whenever|if|while|although|or|then|too)$",
    )
    /**
     * Speech joins thoughts without punctuation ("my name is anna and i live in
     * berlin"), so a capture stops at the first conjunction rather than running to
     * the end of the sentence.
     */
    private val CLAUSE_BREAK = Regex("(?i)\\s+(?:and|but|because|so|then)\\s+")
    private val TRIM_CHARS = charArrayOf(' ', ',', ';', ':', '-', '.', '!', '?', '"', '\'')
    private val LEADING_ARTICLE = Regex("^(?:the|a|an|my|your)\\s+")

    /**
     * Values that say nothing durable. A loose rule such as "I like …" catches
     * these constantly in speech ("I like that idea"), and storing them would
     * pollute every prompt.
     */
    private val EMPTY_VALUES = setOf(
        "that", "this", "it", "you", "your", "yours", "him", "her", "them", "me",
        "us", "so", "much", "a lot", "lots", "stuff", "things", "something",
        "anything", "everything", "nothing", "that one", "that idea", "this one",
    )

    /** A pronoun start means the object was a remark, not a lasting preference. */
    private val PRONOUN_START = Regex(
        "^(?:that|this|it|these|those|you|your|him|her|them|me|us)\\b",
        RegexOption.IGNORE_CASE,
    )

    private class Rule(val pattern: Regex, val build: (MatchResult) -> MemoryCandidate?)

    /**
     * One rule per phrasing family. The builder receives the match so a rule
     * with two captures (a relation and a name) can still shape its own fact.
     */
    private val rules: List<Rule> = listOf(
        // Identity and place.
        rule("\\bmy name is (?:actually )?([^,.;!?]{2,40})", MemoryKind.IDENTITY, "name", 0.95f, pinned = true) {
            "The user's name is ${titleCase(it)}."
        },
        rule("\\bcall me ([^,.;!?]{2,30})", MemoryKind.IDENTITY, "name", 0.85f, pinned = true) {
            "The user goes by ${titleCase(it)}."
        },
        rule("\\bi(?:'m living| am living| live) in ([^,.;!?]{2,40})", MemoryKind.PLACE, "home", 0.85f) {
            "The user lives in ${titleCase(it)}."
        },
        rule("\\bi(?:'m| am) from ([^,.;!?]{2,40})", MemoryKind.PLACE, "origin", 0.7f) {
            "The user is from ${titleCase(it)}."
        },
        rule(
            "\\bi(?:'m working| am working| work) (?:at|for) ([^,.;!?]{2,40})",
            MemoryKind.IDENTITY,
            "employer",
            0.8f,
        ) { "The user works at $it." },
        rule("\\bi speak ([^,.;!?]{2,40})", MemoryKind.IDENTITY, "languages", 0.7f) {
            "The user speaks $it."
        },

        // Projects.
        rule(
            "\\bi(?:'m| am) learning ([^,.;!?]{2,40})",
            MemoryKind.PROJECT,
            null,
            0.7f,
            prefix = "learning:",
        ) { "The user is learning $it." },
        rule(
            "\\bi(?:'m| am) (?:working on|building|writing) ([^,.;!?]{2,60})",
            MemoryKind.PROJECT,
            null,
            0.7f,
            prefix = "project:",
        ) { "The user is working on $it." },
        rule("\\bmy (?:current )?project is (?:called )?([^,.;!?]{2,60})", MemoryKind.PROJECT, "project", 0.75f) {
            "The user's project is $it."
        },

        // Preferences. Polarity is part of the subject, so changing your mind
        // updates the row instead of leaving both statements behind, while an
        // allergy and a fondness for the same food stay separate facts.
        rule(
            "\\bi (?:really |kinda |kind of |sort of )?(?:like|love|enjoy|adore|prefer) " +
                "([^,.;!?]{2,50})",
            MemoryKind.PREFERENCE,
            null,
            0.6f,
            prefix = "likes:",
        ) { "The user likes $it." },
        rule(
            "\\bi (?:really )?(?:don't|do not) (?:like|love|enjoy|eat|drink|use) " +
                "([^,.;!?]{2,50})",
            MemoryKind.PREFERENCE,
            null,
            0.65f,
            prefix = "dislikes:",
        ) { "The user dislikes $it." },
        rule(
            "\\bi (?:hate|can't stand|cannot stand) ([^,.;!?]{2,50})",
            MemoryKind.PREFERENCE,
            null,
            0.7f,
            prefix = "dislikes:",
        ) { "The user dislikes $it." },
        rule(
            "\\bi(?:'m| am) allergic to ([^,.;!?]{2,40})",
            MemoryKind.PREFERENCE,
            null,
            0.95f,
            prefix = "allergy:",
        ) { "The user is allergic to $it." },
        rule(
            "\\bi can't eat ([^,.;!?]{2,40})",
            MemoryKind.PREFERENCE,
            null,
            0.85f,
            prefix = "avoids:",
        ) { "The user cannot eat $it." },
        rule(
            "\\bi(?:'m| am) (?:a )?(vegetarian|vegan|pescatarian|gluten[- ]free|lactose intolerant|diabetic)",
            MemoryKind.PREFERENCE,
            "diet",
            0.9f,
        ) { "The user is $it." },

        // People. The relation is the subject, so a corrected name replaces the
        // old one.
        Rule(
            Regex(
                "\\bmy (wife|husband|partner|girlfriend|boyfriend|son|daughter|mother|mom|" +
                    "mum|father|dad|brother|sister|friend|colleague|coworker|boss|manager)" +
                    "(?:'s)? (?:name is|is called|is named|name's) ([^,.;!?]{2,30})",
                RegexOption.IGNORE_CASE,
            ),
        ) { match ->
            candidate(match, 0.9f) { (relation, name) ->
                val role = normalizeSubject(relation)
                MemoryCandidate(
                    kind = MemoryKind.PERSON,
                    subject = role,
                    text = "The user's ${relation.lowercase()} is called ${titleCase(name)}.",
                    keywords = keywordsOf(name),
                )
            }
        },
        Rule(
            Regex(
                "\\bmy (dog|cat|bird|rabbit|horse)(?:'s)? (?:name is|is called|is named|name's) " +
                    "([^,.;!?]{2,30})",
                RegexOption.IGNORE_CASE,
            ),
        ) { match ->
            candidate(match, 0.85f) { (animal, name) ->
                MemoryCandidate(
                    kind = MemoryKind.PERSON,
                    subject = normalizeSubject("pet $animal"),
                    text = "The user's ${animal.lowercase()} is called ${titleCase(name)}.",
                    keywords = keywordsOf(name),
                )
            }
        },

        // Routines.
        Rule(
            Regex(
                "\\b(?:every (?:day|morning|afternoon|evening|night|week|weekend|monday|" +
                    "tuesday|wednesday|thursday|friday|saturday|sunday)|on (?:" +
                    "weekdays|weekends|mondays|tuesdays|wednesdays|thursdays|fridays|" +
                    "saturdays|sundays))\\b[^.!?]{0,70}",
                RegexOption.IGNORE_CASE,
            ),
        ) { match -> routine(match.value) },
        rule(
            "\\bi (?:usually|always|normally) ([^,.;!?]{3,70})",
            MemoryKind.ROUTINE,
            null,
            0.5f,
            prefix = "routine:",
        ) { "The user's routine includes: $it." },

        // Explicit requests win: they are pinned and outrank inferred facts.
        Rule(Regex("\\b(?:please )?remember (?:that )?([^.!?]{3,140})", RegexOption.IGNORE_CASE)) { match ->
            explicit(match, "The user asked you to remember: ")
        },
        Rule(Regex("\\b(?:please )?keep in mind (?:that )?([^.!?]{3,140})", RegexOption.IGNORE_CASE)) { match ->
            explicit(match, "The user asked you to keep in mind: ")
        },
        Rule(Regex("\\bnote that ([^.!?]{3,140})", RegexOption.IGNORE_CASE)) { match ->
            explicit(match, "Noted: ")
        },
    )

    /**
     * Every candidate a turn states. Duplicate keys collapse to the most
     * confident one, so one utterance never stores the same fact twice.
     */
    fun extract(text: String): List<MemoryCandidate> {
        val normalized = text.replace('\u2019', '\'').replace(WHITESPACE, " ").trim()
        if (normalized.length < 4) return emptyList()
        val out = LinkedHashMap<String, MemoryCandidate>()
        rules.forEach { rule ->
            rule.pattern.findAll(normalized).forEach { match ->
                val candidate = runCatching { rule.build(match) }.getOrNull() ?: return@forEach
                val clean = MemoryGuard.sanitize(candidate.text)
                if (clean.isEmpty() || !MemoryGuard.isStorable(clean)) return@forEach
                val stored = candidate.copy(text = clean)
                val existing = out[stored.dedupeKey]
                if (existing == null || stored.confidence >= existing.confidence) {
                    out[stored.dedupeKey] = stored
                }
            }
        }
        return out.values.toList()
    }

    /**
     * The subject a fact dedupes on. [kind]-specific prefixes keep "likes X" and
     * "dislikes X" apart while both stay a preference.
     */
    fun normalizeSubject(value: String): String {
        val cleaned = value.lowercase()
            .replace(WHITESPACE, " ")
            .replace(LEADING_ARTICLE, "")
            .trim(*TRIM_CHARS)
        return cleaned.take(48)
    }

    private fun rule(
        pattern: String,
        kind: MemoryKind,
        subject: String?,
        confidence: Float,
        pinned: Boolean = false,
        prefix: String = "",
        text: (String) -> String,
    ): Rule = Rule(Regex(pattern, RegexOption.IGNORE_CASE)) { match ->
        val value = cleanValue(match.groupValues.getOrNull(1).orEmpty()) ?: return@Rule null
        val normalized = normalizeSubject(value)
        MemoryCandidate(
            kind = kind,
            subject = subject ?: (prefix + normalized),
            text = text(value),
            confidence = confidence,
            pinned = pinned,
            keywords = keywordsOf(value),
        )
    }

    private fun explicit(match: MatchResult, prefix: String): MemoryCandidate? {
        val value = cleanValue(match.groupValues.getOrNull(1).orEmpty()) ?: return null
        // "Remember to buy milk" is a reminder, not a durable fact.
        if (value.lowercase().startsWith("to ")) return null
        return MemoryCandidate(
            kind = MemoryKind.NOTE,
            subject = normalizeSubject(value),
            text = prefix + value,
            confidence = 0.9f,
            pinned = true,
            keywords = keywordsOf(value),
        )
    }

    private fun routine(raw: String): MemoryCandidate? {
        val value = cleanValue(raw) ?: return null
        return MemoryCandidate(
            kind = MemoryKind.ROUTINE,
            subject = normalizeSubject(value),
            text = "The user's routine includes: $value.",
            confidence = 0.55f,
            keywords = keywordsOf(value),
        )
    }

    private inline fun candidate(
        match: MatchResult,
        confidence: Float,
        build: (Pair<String, String>) -> MemoryCandidate,
    ): MemoryCandidate? {
        val first = match.groupValues.getOrNull(1).orEmpty()
        val second = cleanValue(match.groupValues.getOrNull(2).orEmpty()) ?: return null
        if (first.isBlank()) return null
        return build(first to second).copy(confidence = confidence)
    }

    /**
     * Trims a capture to a storable value, or null when the phrasing turned out
     * to say nothing lasting. Shared by every rule so the guard behaves the same
     * everywhere.
     */
    private fun cleanValue(raw: String): String? {
        var value = raw.trim().replace(WHITESPACE, " ").trim(*TRIM_CHARS)
        value = CLAUSE_BREAK.split(value).first().trim(*TRIM_CHARS)
        value = TRAILING_CONJUNCTION.replace(value, "").trim(*TRIM_CHARS)
        if (value.length < 2 || value.length > MAX_VALUE_CHARS) return null
        if (value.none { it.isLetter() }) return null
        if (PRONOUN_START.containsMatchIn(value)) return null
        val normalized = normalizeSubject(value)
        if (normalized.isBlank() || normalized in EMPTY_VALUES) return null
        if (!MemoryGuard.isStorable(value)) return null
        return value
    }

    private fun keywordsOf(value: String): String =
        MemoryRanker.tokens(value).joinToString(" ")

    /** Voice transcripts are usually lowercase; names and places read better. */
    private fun titleCase(value: String): String = value.split(" ").joinToString(" ") { word ->
        if (word.length > 1 && word.all { it.isLowerCase() || !it.isLetter() }) {
            word[0].uppercaseChar() + word.substring(1)
        } else {
            word
        }
    }
}
