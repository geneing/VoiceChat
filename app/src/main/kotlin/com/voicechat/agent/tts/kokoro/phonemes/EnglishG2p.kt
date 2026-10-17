package com.voicechat.agent.tts.kokoro.phonemes

import java.text.Normalizer
import java.util.Locale

/** English Misaki 0.9.4 pipeline with OpenNLP POS tags and an offline letter-rule fallback. */
internal class EnglishG2p(
    private val lexicon: EnglishLexicon,
    private val posTagger: EnglishPosTagger,
) {
    private val fallback = RuleBasedEnglishG2p()

    /** Runs preprocess, tokenization, folding, retokenization, reverse resolution, and output mapping. */
    fun phonemize(text: String): String {
        val prepared = preprocess(text)
        val tokens = tokenize(prepared)
        val folded = foldLeft(tokens)
        val units = retokenize(folded)
        var context = TokenContext()
        units.indices.reversed().forEach { index ->
            val unit = units[index]
            if (unit.tokens.size == 1) {
                val token = unit.tokens.single()
                if (token.phonemes == null) {
                    val (phones, rating) = lexicon.transcribe(token, context)
                    token.phonemes =
                        phones ?: compoundFallback(token.text, token.tag, context) ?: fallback.phonemize(token.alias ?: token.text)
                    token.rating = rating ?: 1
                }
                context = tokenContext(context, token.phonemes, token)
            } else {
                resolveTokens(unit.tokens, context)?.let { (phones, rating, resolvedContext) ->
                    unit.tokens.first().phonemes = phones
                    unit.tokens.first().rating = rating
                    unit.tokens.drop(1).forEach {
                        it.phonemes = ""
                        it.rating = rating
                    }
                    context = resolvedContext
                }
            }
        }
        val result =
            buildString {
                units.forEach { unit ->
                    val token = unit.tokens.first()
                    append((token.phonemes ?: "").replace("ɾ", "T").replace("ʔ", "t"))
                    append(unit.tokens.last().whitespace)
                }
            }
        return result
    }

    private fun preprocess(text: String): PreparedText {
        val source = expandContractions(text).trimStart()
        val output = StringBuilder()
        val features = mutableListOf<FeatureSpan>()
        var lastEnd = 0
        ANNOTATION.findAll(source).forEach { match ->
            val before = source.substring(lastEnd, match.range.first)
            output.append(before)
            val markdownLabel = match.groupValues[1]
            val label = markdownLabel.ifEmpty { match.groupValues[3] }
            val feature =
                if (markdownLabel.isNotEmpty()) {
                    parseLinkFeature(match.groupValues[2])
                } else {
                    LinkFeature.Phones(match.groupValues[4].replace(".", ""))
                }
            val featureStart = output.length
            output.append(label)
            if (feature != null) features += FeatureSpan(featureStart, output.length, feature)
            lastEnd = match.range.last + 1
        }
        if (lastEnd < source.length) {
            output.append(source.substring(lastEnd))
        }
        return PreparedText(output.toString(), features)
    }

    private fun expandContractions(text: String): String =
        CONTRACTIONS.fold(text) { expanded, (pattern, replacement) -> pattern.replace(expanded, replacement) }

    private fun parseLinkFeature(value: String): LinkFeature? =
        when {
            value.matches(Regex("[+-]?\\d+")) -> {
                LinkFeature.Stress(value.toDoubleOrNull())
            }

            value in setOf("0.5", "+0.5", "-0.5") -> {
                LinkFeature.Stress(value.toDouble())
            }

            value.length > 1 && value.startsWith('/') && value.endsWith('/') -> {
                LinkFeature.Phones(value.drop(1).trimEnd('/'))
            }

            value.length > 1 && value.startsWith('#') && value.endsWith('#') -> {
                LinkFeature.Flags(value.drop(1).trimEnd('#'))
            }

            else -> {
                null
            }
        }

    private fun tokenize(prepared: PreparedText): MutableList<EnglishToken> {
        val raw = mutableListOf<EnglishToken>()
        val appliedPhoneAnnotations = mutableSetOf<Int>()
        var cursor = 0
        TOKEN.findAll(prepared.text).forEach { match ->
            val tokenText = match.value
            var end = match.range.last + 1
            while (end < prepared.text.length && prepared.text[end].isWhitespace()) end++
            var whitespace = prepared.text.substring(match.range.last + 1, end)
            val featureIndex =
                prepared.features.indexOfFirst { span ->
                    span.start < match.range.last + 1 && span.end > match.range.first
                }
            val featureSpan = prepared.features.getOrNull(featureIndex)
            val feature = featureSpan?.feature
            val token =
                EnglishToken(
                    text = tokenText,
                    tag = tag(tokenText, raw.lastOrNull()),
                    whitespace = whitespace,
                )
            when (feature) {
                is LinkFeature.Stress -> {
                    token.stress = feature.value
                }

                is LinkFeature.Phones -> {
                    token.phonemes = if (appliedPhoneAnnotations.add(featureIndex)) feature.value else ""
                    token.rating = 5
                    if (match.range.last + 1 < featureSpan.end) token.whitespace = ""
                }

                is LinkFeature.Flags -> {
                    token.numFlags = feature.value
                }

                null -> {}
            }
            raw += token
            cursor = end
        }
        val taggerInputs =
            raw.map { token ->
                token.text.replace(NON_ALPHA_PRESERVING_CASE, "").ifBlank { "." }
            }
        val posTags = posTagger.tag(taggerInputs)
        require(posTags.size == raw.size) { "OpenNLP returned a tag count that does not match token count" }
        raw.forEachIndexed { index, token ->
            if (token.tag !in PRESERVED_TAGS) token.tag = posTags[index]
            if (token.text in setOf("AM", "AN")) token.tag = "NNP"
            if (token.text == "A" && raw.getOrNull(index + 1)?.text in SYMBOLS + ADD_SYMBOLS) {
                token.tag = "NNP"
            }
        }
        if (cursor < prepared.text.length && raw.isNotEmpty()) {
            raw.last().whitespace += prepared.text.substring(cursor)
        }
        return raw
    }

    /** Initial tags preserve symbols and punctuation before OpenNLP supplies word tags. */
    private fun tag(
        word: String,
        previous: EnglishToken?,
    ): String {
        if (word.all(Char::isDigit) || word.firstOrNull()?.isDigit() == true) return "CD"
        if (word in setOf("AM", "AN")) return "NNP"
        return when (word) {
            "(", "[", "{" -> {
                "-LRB-"
            }

            ")", "]", "}" -> {
                "-RRB-"
            }

            "$", "£", "€" -> {
                "$"
            }

            "," -> {
                ","
            }

            ".", "!", "?", "…" -> {
                "."
            }

            ":" -> {
                ":"
            }

            "-", "–" -> {
                ":"
            }

            "'", "\"", "“", "”", "‘", "’" -> {
                "''"
            }

            "to", "To", "TO" -> {
                "TO"
            }

            else -> {
                when (word.lowercase(Locale.ROOT)) {
                    "a", "an", "the", "this", "that", "these", "those", "my", "your", "his", "her", "our", "their" -> {
                        "DT"
                    }

                    "i", "you", "he", "she", "it", "we", "they", "me", "him", "us", "them" -> {
                        "PRP"
                    }

                    "in", "on", "at", "by", "for", "from", "of", "with", "without", "about", "after", "before",
                    "between", "through", "under", "over", "into", "as", "than", "since", "until", "during",
                    -> {
                        "IN"
                    }

                    "and", "or", "but", "nor", "yet", "so" -> {
                        "CC"
                    }

                    "is", "am", "are", "was", "were", "be", "been", "being", "have", "has", "had", "do", "does",
                    "did", "can", "could", "will", "would", "shall", "should", "may", "might", "must",
                    -> {
                        "VB"
                    }

                    "used" -> {
                        if (previous?.text?.equals("to", true) == true) "VB" else "VBD"
                    }

                    "not", "n't" -> {
                        "RB"
                    }

                    else -> {
                        when {
                            word.all { !it.isLetterOrDigit() } -> if (word in setOf("%", "&", "+", "@", "/")) "SYM" else "NFP"
                            word.firstOrNull()?.isUpperCase() == true && previous?.tag !in setOf("DT", "TO", "IN") -> "NNP"
                            else -> "NN"
                        }
                    }
                }
            }
        }
    }

    private fun foldLeft(tokens: MutableList<EnglishToken>): MutableList<EnglishToken> {
        val result = mutableListOf<EnglishToken>()
        tokens.forEach { token ->
            if (result.isNotEmpty() && !token.isHead) {
                val previous = result.removeAt(result.lastIndex)
                result += mergeTokens(listOf(previous, token), "")
            } else {
                result += token
            }
        }
        return result
    }

    private fun retokenize(tokens: List<EnglishToken>): List<EnglishUnit> {
        val units = mutableListOf<EnglishUnit>()
        var currency: String? = null
        tokens.forEachIndexed { tokenIndex, token ->
            val parts = if (token.alias == null && token.phonemes == null) subtokenize(token.text) else listOf(token.text)
            val split =
                parts
                    .mapIndexed { index, part ->
                        token.copy(
                            text = part,
                            whitespace = if (index == parts.lastIndex) token.whitespace else "",
                            isHead = index == 0,
                            prespace = false,
                        )
                    }.toMutableList()
            split.forEachIndexed { partIndex, piece ->
                when {
                    piece.alias != null || piece.phonemes != null -> {}

                    piece.tag == "$" && piece.text in CURRENCIES -> {
                        currency = piece.text
                        piece.phonemes = ""
                        piece.rating = 4
                    }

                    piece.tag == ":" && piece.text in setOf("-", "–") -> {
                        piece.phonemes = "—"
                        piece.rating = 3
                    }

                    piece.tag in PUNCT_TAGS && !piece.text.all { it.lowercaseChar() in 'a'..'z' } -> {
                        piece.phonemes = PUNCT_PHONEMES[piece.tag]
                            ?: piece.text.filter { it in PUNCTUATION }
                        piece.rating = 4
                    }

                    currency != null -> {
                        if (piece.tag != "CD") {
                            currency = null
                        } else if (partIndex == split.lastIndex &&
                            (tokenIndex + 1 == tokens.size || tokens[tokenIndex + 1].tag != "CD")
                        ) {
                            piece.currency = currency
                        }
                    }

                    partIndex in 1 until split.lastIndex && piece.text == "2" &&
                        split[partIndex - 1].text.lastOrNull()?.isLetter() == true &&
                        split[partIndex + 1].text.firstOrNull()?.isLetter() == true -> {
                        piece.alias = "to"
                    }
                }
            }
            split.forEach { piece ->
                val previousUnit = units.lastOrNull()
                if (piece.alias != null || piece.phonemes != null) {
                    units += EnglishUnit(mutableListOf(piece))
                } else if (previousUnit != null &&
                    previousUnit.tokens
                        .last()
                        .whitespace
                        .isEmpty() &&
                    previousUnit.tokens.last().phonemes == null &&
                    previousUnit.tokens.last().alias == null
                ) {
                    piece.isHead = false
                    previousUnit.tokens += piece
                } else {
                    units += EnglishUnit(mutableListOf(piece))
                }
            }
        }
        return units
    }

    private fun resolveTokens(
        tokens: MutableList<EnglishToken>,
        initial: TokenContext,
    ): ResolvedGroup? {
        val text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text
        val prespace =
            ' ' in text || '/' in text || text
                .filterNot { it in SUBTOKEN_JUNKS }
                .map {
                    when {
                        it.isLetter() -> 0
                        it.isDigit() -> 1
                        else -> 2
                    }
                }.toSet()
                .size > 1
        tokens.forEachIndexed { index, token ->
            if (token.phonemes == null) {
                if (index == tokens.lastIndex && token.text.all { it in NON_QUOTE_PUNCTUATION }) {
                    token.phonemes = token.text
                    token.rating = 3
                } else if (token.text.all { it in SUBTOKEN_JUNKS }) {
                    token.phonemes = ""
                    token.rating = 3
                }
            } else if (index > 0) {
                token.prespace = prespace
            }
        }
        var context = initial
        var left = 0
        var right = tokens.size
        var fallbackNeeded = false
        while (left < right) {
            val candidate =
                if (tokens.subList(left, right).any { it.alias != null || it.phonemes != null }) {
                    null
                } else {
                    mergeTokens(tokens.subList(left, right))
                }
            val (phones, rating) = if (candidate == null) null to null else lexicon.transcribe(candidate, context)
            if (phones != null) {
                tokens[left].phonemes = phones
                tokens[left].rating = rating
                tokens.subList(left + 1, right).forEach {
                    it.phonemes = ""
                    it.rating = rating
                }
                context = tokenContext(context, phones, candidate!!)
                right = left
                left = 0
            } else if (left + 1 < right) {
                left++
            } else {
                right--
                val last = tokens[right]
                if (last.phonemes == null) {
                    if (last.text.all { it in SUBTOKEN_JUNKS }) {
                        last.phonemes = ""
                        last.rating = 3
                    } else {
                        fallbackNeeded = true
                        break
                    }
                }
                left = 0
            }
        }

        if (fallbackNeeded) {
            val merged = mergeTokens(tokens)
            val phones = compoundFallback(merged.text, merged.tag, context) ?: fallback.phonemize(merged.text)
            tokens.first().phonemes = phones
            tokens.first().rating = 1
            tokens.drop(1).forEach {
                it.phonemes = ""
                it.rating = 1
            }
        }
        if (prespace) {
            tokens.forEachIndexed { index, token ->
                if (index > 0 && token.phonemes != null) token.prespace = true
            }
        } else {
            val stressed =
                tokens.mapIndexedNotNull { index, token ->
                    token.phonemes?.takeIf(String::isNotEmpty)?.let { Triple('ˈ' in it, stressWeight(it), index) }
                }
            if (stressed.size == 2 && tokens[stressed[0].third].text.length == 1) {
                val index = stressed[1].third
                tokens[index].phonemes = applyStress(tokens[index].phonemes, -0.5)
            } else if (stressed.size >= 2 && stressed.count { it.first } > (stressed.size + 1) / 2) {
                stressed
                    .sortedWith(compareBy<Triple<Boolean, Int, Int>> { it.first }.thenBy { it.second })
                    .take(stressed.size / 2)
                    .forEach { (_, _, index) ->
                        tokens[index].phonemes = applyStress(tokens[index].phonemes, -0.5)
                    }
            }
        }
        val merged = mergeTokens(tokens, unknown = "")
        return ResolvedGroup(merged.phonemes.orEmpty(), merged.rating ?: 1, context)
    }

    private fun compoundFallback(
        original: String,
        tag: String,
        context: TokenContext,
    ): String? {
        val word = original.lowercase(Locale.ROOT)
        if (word.length < 6 || !word.all { it in 'a'..'z' }) return null
        for (split in 3..word.length - 3) {
            val left = word.substring(0, split)
            if (!lexicon.isKnown(left, tag)) continue
            val first = lexicon.lookup(left, tag, 1.0, context).first ?: continue
            val right = word.substring(split)
            val second =
                (if (lexicon.isKnown(right, tag)) lexicon.lookup(right, tag, 1.0, context).first else null)
                    ?: lexicon.stemS(right, tag, 1.0, context).first
                    ?: lexicon.stemEd(right, tag, 1.0, context).first
                    ?: lexicon.stemIng(right, tag, 1.0, context).first
                    ?: continue
            val reducedSecond = if (second.startsWith("ˈ")) "ˌ${second.drop(1)}" else second.replace("ˈ", "")
            return first + reducedSecond
        }
        return null
    }

    private fun tokenContext(
        ctx: TokenContext,
        phones: String?,
        token: EnglishToken,
    ): TokenContext {
        val nextSound =
            phones?.firstOrNull { char ->
                char in VOWELS || char in CONSONANTS || char in NON_QUOTE_PUNCTUATION
            }
        val vowel =
            when {
                nextSound == null -> ctx.futureVowel
                nextSound in NON_QUOTE_PUNCTUATION -> null
                else -> nextSound in VOWELS
            }
        val futureTo = token.text in setOf("to", "To") || (token.text == "TO" && token.tag in setOf("TO", "IN"))
        return TokenContext(vowel, futureTo)
    }

    private fun mergeTokens(
        tokens: List<EnglishToken>,
        unknown: String? = null,
    ): EnglishToken {
        val stresses = tokens.mapNotNull(EnglishToken::stress).toSet()
        val currencies = tokens.mapNotNull(EnglishToken::currency).toSet()
        val ratings = tokens.map(EnglishToken::rating)
        val phones =
            if (unknown == null) {
                null
            } else {
                buildString {
                    tokens.forEach { token ->
                        if (token.prespace && isNotEmpty() && last().isWhitespace().not() && !token.phonemes.isNullOrEmpty()) append(' ')
                        append(token.phonemes ?: unknown)
                    }
                }
            }
        val rank = tokens.maxBy { token -> token.text.sumOf { if (it == it.lowercaseChar()) 1 else 2 } }
        return EnglishToken(
            text = tokens.dropLast(1).joinToString("") { it.text + it.whitespace } + tokens.last().text,
            tag = rank.tag,
            whitespace = tokens.last().whitespace,
            phonemes = phones,
            alias = null,
            stress = stresses.singleOrNull(),
            currency = currencies.maxOrNull(),
            numFlags = tokens.flatMap { it.numFlags.toList() }.toSortedSet().joinToString(""),
            isHead = tokens.first().isHead,
            prespace = tokens.first().prespace,
            rating = if (null in ratings) null else ratings.filterNotNull().minOrNull(),
        )
    }

    private fun subtokenize(word: String): List<String> =
        SUBTOKEN
            .findAll(word)
            .map { it.value }
            .toList()
            .ifEmpty { listOf(word) }

    private fun stressWeight(phones: String): Int = phones.sumOf { if (it in DIPHTHONGS) 2 else 1 }

    private fun applyStress(
        phones: String?,
        stress: Double?,
    ): String? {
        if (phones == null || stress == null) return phones

        fun restress(input: String): String {
            val indexed = input.withIndex().toList()
            val mapped =
                indexed.map { (index, char) ->
                    if (char !in STRESSES) {
                        index.toDouble() to char
                    } else {
                        (
                            indexed
                                .drop(index)
                                .firstOrNull { it.value in VOWELS }
                                ?.index
                                ?.minus(0.5) ?: index.toDouble()
                        ) to char
                    }
                }
            return mapped.sortedBy { it.first }.joinToString("") { it.second.toString() }
        }
        return when {
            stress < -1 -> {
                phones.replace("ˈ", "").replace("ˌ", "")
            }

            stress == -1.0 || (stress in setOf(0.0, -0.5) && 'ˈ' in phones) -> {
                phones.replace("ˌ", "").replace("ˈ", "ˌ")
            }

            stress in setOf(0.0, 0.5, 1.0) && STRESSES.none(phones::contains) -> {
                if (phones.none { it in VOWELS }) phones else restress("ˌ$phones")
            }

            stress >= 1 && 'ˈ' !in phones && 'ˌ' in phones -> {
                phones.replace("ˌ", "ˈ")
            }

            stress > 1 && STRESSES.none(phones::contains) -> {
                if (phones.none { it in VOWELS }) phones else restress("ˈ$phones")
            }

            else -> {
                phones
            }
        }
    }

    private data class PreparedText(
        val text: String,
        val features: List<FeatureSpan>,
    )

    private data class FeatureSpan(
        val start: Int,
        val end: Int,
        val feature: LinkFeature,
    )

    private data class EnglishUnit(
        val tokens: MutableList<EnglishToken>,
    )

    private data class ResolvedGroup(
        val phones: String,
        val rating: Int,
        val context: TokenContext,
    )

    private sealed interface LinkFeature {
        data class Stress(
            val value: Double?,
        ) : LinkFeature

        data class Phones(
            val value: String,
        ) : LinkFeature

        data class Flags(
            val value: String,
        ) : LinkFeature
    }

    companion object {
        /** Supports Misaki markdown links and the README's `(word)[IPA]` override form. */
        private val ANNOTATION = Regex("""\[([^\]]+)]\(([^)]*)\)|\(([^)]+)\)\[([^]]+)]""")

        /** Expand the contraction handled explicitly by the linked phoneme-generator README. */
        private val CONTRACTIONS = listOf(Regex("\\bI'm\\b") to "I am")
        private val TOKEN = Regex("\\p{L}+(?:['’][\\p{L}]+)?|\\d+(?:[,.]\\d+)*(?:st|nd|rd|th)?|[^\\s]")
        private val SUBTOKEN =
            Regex(
                "^['‘’]+|\\p{Lu}(?=\\p{Lu}\\p{Ll})|(?:^-)?(?:\\d?[,.]?\\d)+|[-_]+|['‘’]{2,}|" +
                    "\\p{L}*?(?:['‘’]\\p{L})*?\\p{Ll}(?=\\p{Lu})|\\p{L}+(?:['‘’]\\p{L})*|" +
                    "[^-_\\p{L}'‘’\\d]|['‘’]+$",
            )
        private val PUNCT_TAGS = setOf(".", ",", "-LRB-", "-RRB-", "``", "\"\"", "''", ":", "$", "#", "NFP")
        private val PRESERVED_TAGS = PUNCT_TAGS + setOf("CD", "SYM")
        private val PUNCT_PHONEMES = mapOf("-LRB-" to "(", "-RRB-" to ")", "``" to "“", "\"\"" to "”", "''" to "”")
        private val NON_ALPHA_PRESERVING_CASE = Regex("[^a-zA-Z']")
        private val PUNCTUATION = ";:,.!?—…\"“”"
        private val NON_QUOTE_PUNCTUATION = ";:,.!?—…"
        private val SUBTOKEN_JUNKS = "',-._‘’/"
        private val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ"
        private val CONSONANTS = "bdfhjklmnpstvwzðŋɡɹɾʃʒʤʧθ"
        private val DIPHTHONGS = "AIOQWYʤʧ"
        private val STRESSES = "ˌˈ"
        private val CURRENCIES = setOf("$", "£", "€")
        private val SYMBOLS = setOf("%", "&", "+", "@")
        private val ADD_SYMBOLS = setOf(".", "/")
    }
}

/**
 * Offline letter-rule fallback inspired by `LetterPhonemeRules` in the linked Kokoro Kotlin README.
 * Penultimate-syllable stress is only a default; silent-letter exceptions, regional accents, and
 * arbitrary proper names remain approximate. This replaces the optional eSpeak backend.
 */
internal class RuleBasedEnglishG2p {
    /** Greedily applies the README's letter-rule strategy without its model or network dependencies. */
    fun phonemize(input: String): String {
        val word =
            Normalizer
                .normalize(input.lowercase(Locale.ROOT), Normalizer.Form.NFKC)
                .filter { it in 'a'..'z' || it in "'’" }
        if (word.isBlank()) return ""
        val irregular = IRREGULAR[word]
        if (irregular != null) return irregular
        val source = word.replace('’', '\'')
        val output = StringBuilder()
        var index = 0
        while (index < source.length) {
            val match = matchPattern(source, index)
            if (match != null) {
                output.append(match.first)
                index += match.second
            } else {
                val char = source[index]
                output.append(singleLetter(source, index, char))
                index++
            }
        }
        return applyPenultimateStress(output.toString())
    }

    private fun matchPattern(
        word: String,
        index: Int,
    ): Pair<String, Int>? {
        val remaining = word.substring(index)
        WORD_END_RULES[remaining]?.let { return it to remaining.length }
        if (remaining.startsWith("th")) {
            val next = word.getOrNull(index + 2)
            return (if (index == 0 && next in VOWEL_LETTERS) "ð" else "θ") to 2
        }
        if (remaining.startsWith("gh")) return (if (index == 0) "ɡ" else "") to 2
        if (remaining.startsWith("gn") && index == 0) return "n" to 2
        if (remaining.startsWith("ow")) {
            val next = word.getOrNull(index + 2)
            return (if (next == null || next in OW_DIPHTHONG_NEXT) "W" else "O") to 2
        }
        silentE(word, index)?.let { return it }
        RULES.firstOrNull { (pattern, _) -> remaining.startsWith(pattern) }?.let { return it.second to it.first.length }
        return null
    }

    private fun silentE(
        word: String,
        index: Int,
    ): Pair<String, Int>? {
        if (index + 3 != word.length || word[index + 2] != 'e') return null
        val vowel = word[index]
        val consonant = word[index + 1]
        if (vowel !in VOWEL_LETTERS || consonant in VOWEL_LETTERS || consonant == 'r') return null
        val longVowel = LONG_VOWELS[vowel] ?: return null
        return longVowel + consonantLetter(consonant) to 3
    }

    private fun singleLetter(
        word: String,
        index: Int,
        char: Char,
    ): String =
        when (char) {
            'a' -> {
                when {
                    word.getOrNull(index + 1) == 'r' -> "ɑ"
                    word.getOrNull(index + 1) == 'l' && word.getOrNull(index + 2) !in VOWEL_LETTERS -> "ɔ"
                    word.getOrNull(index + 1) == 'w' -> ""
                    index == word.lastIndex -> "ə"
                    else -> "æ"
                }
            }

            'e' -> {
                if (index == word.lastIndex && word.length > 2) {
                    ""
                } else if (index == word.lastIndex) {
                    "i"
                } else {
                    "ɛ"
                }
            }

            'i' -> {
                when {
                    word.getOrNull(index + 1) == 'r' -> "ɜ"
                    index == word.lastIndex -> "i"
                    else -> "ɪ"
                }
            }

            'o' -> {
                when {
                    word.getOrNull(index + 1) == 'r' -> "ɔ"
                    index == word.lastIndex -> "O"
                    else -> "ɑ"
                }
            }

            'u' -> {
                if (word.getOrNull(index + 1) == 'r') "ɜ" else "ʌ"
            }

            'y' -> {
                when {
                    index == 0 -> "j"
                    index == word.lastIndex -> "i"
                    word.getOrNull(index + 1) !in VOWEL_LETTERS -> "ɪ"
                    else -> "j"
                }
            }

            'c' -> {
                if (word.getOrNull(index + 1) in SOFT_VOWELS) "s" else "k"
            }

            'g' -> {
                if (word.getOrNull(index + 1) in SOFT_VOWELS) "ʤ" else "ɡ"
            }

            'h' -> {
                if (word.getOrNull(index - 1) in SILENT_H_PREV) "" else "h"
            }

            's' -> {
                if (index == word.lastIndex && word.getOrNull(index - 1) in VOWEL_LETTERS) "z" else "s"
            }

            'j' -> {
                "ʤ"
            }

            'q' -> {
                "k"
            }

            'r' -> {
                "ɹ"
            }

            'x' -> {
                if (index == 0) "z" else "ks"
            }

            'w' -> {
                "w"
            }

            '\'' -> {
                ""
            }

            else -> {
                consonantLetter(char)
            }
        }

    private fun consonantLetter(char: Char): String = CONSONANTS[char] ?: char.toString()

    private fun applyPenultimateStress(phonemes: String): String {
        val syllableStarts = mutableListOf<Int>()
        var inVowel = false
        phonemes.forEachIndexed { index, char ->
            if (char in PHONEME_VOWELS) {
                if (!inVowel) syllableStarts += index
                inVowel = true
            } else {
                inVowel = false
            }
        }
        if (syllableStarts.isEmpty()) return phonemes
        val stressIndex = syllableStarts[if (syllableStarts.size > 1) syllableStarts.lastIndex - 1 else 0]
        return phonemes.substring(0, stressIndex) + "ˈ" + phonemes.substring(stressIndex)
    }

    companion object {
        private val VOWEL_LETTERS = setOf('a', 'e', 'i', 'o', 'u', 'y')
        private val SOFT_VOWELS = setOf('e', 'i', 'y')
        private val SILENT_H_PREV = setOf('t', 'c', 's', 'g', 'r', 'w')
        private val OW_DIPHTHONG_NEXT = setOf('n', 'l', 'e')
        private val PHONEME_VOWELS = "AIOWYaiuæɑɒɔəɛɜɪʊʌᵻ"
        private val LONG_VOWELS = mapOf('a' to "A", 'e' to "i", 'i' to "I", 'o' to "O", 'u' to "ju")
        private val CONSONANTS =
            mapOf(
                'b' to "b",
                'd' to "d",
                'f' to "f",
                'j' to "ʤ",
                'k' to "k",
                'l' to "l",
                'm' to "m",
                'n' to "n",
                'p' to "p",
                'r' to "ɹ",
                't' to "t",
                'v' to "v",
                'w' to "w",
                'z' to "z",
            )
        private val RULES =
            listOf(
                "ation" to "Aʃən",
                "ought" to "ɔt",
                "ture" to "ʧəɹ",
                "sure" to "ʒəɹ",
                "tion" to "ʃən",
                "sion" to "ʒən",
                "ious" to "iəs",
                "eous" to "iəs",
                "ight" to "It",
                "ough" to "O",
                "tial" to "ʃəl",
                "cial" to "ʃəl",
                "tch" to "ʧ",
                "dge" to "ʤ",
                "sch" to "sk",
                "scr" to "skɹ",
                "shr" to "ʃɹ",
                "str" to "stɹ",
                "thr" to "θɹ",
                "chr" to "kɹ",
                "air" to "ɛɹ",
                "ear" to "iɹ",
                "eer" to "iɹ",
                "our" to "Wɹ",
                "oor" to "ɔɹ",
                "ore" to "ɔɹ",
                "ire" to "Iɹ",
                "ure" to "jʊɹ",
                "ous" to "əs",
                "ess" to "ɛs",
                "ing" to "ɪŋ",
                "ble" to "bəl",
                "ple" to "pəl",
                "tle" to "təl",
                "dle" to "dəl",
                "gle" to "ɡəl",
                "kle" to "kəl",
                "ful" to "fəl",
                "all" to "ɔl",
                "alk" to "ɔk",
                "ism" to "ɪzəm",
                "ist" to "ɪst",
                "ity" to "ɪti",
                "ily" to "ɪli",
                "ize" to "Iz",
                "ise" to "Iz",
                "ary" to "ɛɹi",
                "ery" to "ɛɹi",
                "ory" to "ɔɹi",
                "eigh" to "A",
                "igh" to "I",
                "sh" to "ʃ",
                "ch" to "ʧ",
                "th" to "θ",
                "ph" to "f",
                "ng" to "ŋ",
                "ck" to "k",
                "qu" to "kw",
                "wh" to "w",
                "wr" to "ɹ",
                "kn" to "n",
                "nk" to "ŋk",
                "ee" to "i",
                "ea" to "i",
                "ie" to "i",
                "oo" to "u",
                "ou" to "W",
                "oi" to "Y",
                "oy" to "Y",
                "ai" to "A",
                "ay" to "A",
                "oa" to "O",
                "oe" to "O",
                "aw" to "ɔ",
                "au" to "ɔ",
                "ew" to "ju",
                "ar" to "ɑɹ",
                "er" to "ɜɹ",
                "ir" to "ɜɹ",
                "ur" to "ɜɹ",
                "or" to "ɔɹ",
                "ss" to "s",
                "tt" to "t",
                "ll" to "l",
            ).sortedByDescending { it.first.length }
        private val WORD_END_RULES =
            mapOf(
                "ages" to "ᵻʤᵻz",
                "ares" to "ɛɹz",
                "ives" to "ɪvz",
                "ence" to "əns",
                "ance" to "əns",
            )
        private val IRREGULAR =
            mapOf(
                "the" to "ðə",
                "a" to "ə",
                "an" to "ən",
                "of" to "əv",
                "and" to "ænd",
                "to" to "tu",
                "be" to "bˈi",
                "he" to "hˈi",
                "me" to "mˈi",
                "one" to "wˈʌn",
                "two" to "tˈu",
                "three" to "θɹˈi",
                "you" to "jˈu",
                "i" to "ˌI",
                "we" to "wˈi",
                "they" to "ðˈeɪ",
                "are" to "ɑɹ",
                "was" to "wəz",
                "were" to "wɜɹ",
                "is" to "ɪz",
                "this" to "ðɪs",
                "that" to "ðæt",
                "have" to "hæv",
                "has" to "hæz",
                "said" to "sˈɛd",
                "one's" to "wˈʌnz",
                "hello" to "həlˈO",
                "world" to "wˈɜɹld",
                "yosemite" to "jOsˈɛməTi",
            )
    }
}
