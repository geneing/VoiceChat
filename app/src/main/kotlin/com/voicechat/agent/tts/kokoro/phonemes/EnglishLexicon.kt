package com.voicechat.agent.tts.kokoro.phonemes

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/** The context Misaki carries while resolving tokens from right to left. */
internal data class TokenContext(
    val futureVowel: Boolean? = null,
    val futureTo: Boolean = false,
)

/** Mutable token metadata corresponding to Misaki's `MToken` and `MToken.Underscore`. */
internal data class EnglishToken(
    var text: String,
    var tag: String,
    var whitespace: String = "",
    var phonemes: String? = null,
    var alias: String? = null,
    var stress: Double? = null,
    var currency: String? = null,
    var numFlags: String = "",
    var isHead: Boolean = true,
    var prespace: Boolean = false,
    var rating: Int? = null,
)

/** US lexicon and English number path from Misaki `en.py` 0.9.4, driven by OpenNLP Penn tags. */
internal class EnglishLexicon(
    private val golds: Map<String, Lexeme>,
    private val silvers: Map<String, Lexeme>,
) {
    private val capStresses = listOf(0.5, 2.0)

    /** A lexicon item is a direct transcription or a tag-indexed transcription map. */
    internal sealed interface Lexeme {
        data class Phones(
            val value: String?,
        ) : Lexeme

        data class Tagged(
            val values: Map<String, String?>,
        ) : Lexeme
    }

    fun transcribe(
        token: EnglishToken,
        ctx: TokenContext,
    ): Pair<String?, Int?> {
        val raw = (token.alias ?: token.text).replace('‘', '\'').replace('’', '\'')
        val normalized = raw.normalizeNfkc().map(::numericIfNeeded).joinToString("")
        val capitalizationStress =
            if (normalized ==
                normalized.lowercase()
            ) {
                null
            } else {
                capStresses[if (normalized == normalized.uppercase()) 1 else 0]
            }
        var (phonemes, rating) = getWord(normalized, token.tag, capitalizationStress, ctx)
        if (phonemes != null) {
            phonemes = appendCurrency(phonemes, token.currency)
            return applyStress(phonemes, token.stress) to rating
        }
        if (isNumber(normalized, token.isHead)) {
            val number = getNumber(normalized, token.currency, token.isHead, token.numFlags)
            return applyStress(number.first, token.stress) to number.second
        }
        return null to null
    }

    fun lookup(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: TokenContext?,
    ): Pair<String?, Int?> {
        var key = word
        var isNnp: Boolean? = null
        if (key == key.uppercase() && key !in golds) {
            key = key.lowercase()
            isNnp = tag == "NNP"
        }
        var lexeme = golds[key]
        var rating = 4
        if (lexeme == null && isNnp != true) {
            lexeme = silvers[key]
            rating = 3
        }
        var phones =
            when (lexeme) {
                is Lexeme.Phones -> {
                    lexeme.value
                }

                is Lexeme.Tagged -> {
                    // Preserve Misaki's sentence-final None key before exact or parent POS lookup.
                    val selected =
                        if (ctx != null && ctx.futureVowel == null && "None" in lexeme.values) {
                            "None"
                        } else {
                            listOfNotNull(tag, parentTag(tag)).firstOrNull { it in lexeme.values } ?: "DEFAULT"
                        }
                    lexeme.values[selected] ?: lexeme.values["DEFAULT"]
                }

                null -> {
                    null
                }
            }
        if (phones == null || (isNnp == true && PRIMARY_STRESS !in phones)) {
            val spelled = getNnp(key)
            if (spelled.first != null) return spelled
        }
        return applyStress(phones, stress) to rating
    }

    fun getWord(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: TokenContext,
    ): Pair<String?, Int?> {
        getSpecialCase(word, tag, stress, ctx)?.let { if (it.first != null) return it }
        var key = word
        val lower = word.lowercase()
        if (word.length > 1 && word.replace("'", "").all(Char::isLetter) && word != lower &&
            (tag != "NNP" || word.length > 7) && word !in golds && word !in silvers &&
            (word == word.uppercase() || word.drop(1) == word.drop(1).lowercase()) &&
            (
                lower in golds || lower in silvers ||
                    listOf(::stemS, ::stemEd, ::stemIng).any {
                        it(lower, tag, stress, ctx).first != null
                    }
            )
        ) {
            key = lower
        }
        if (isKnown(key, tag)) return lookup(key, tag, stress, ctx)
        if (key.endsWith("s'") && isKnown(key.dropLast(2) + "'s", tag)) {
            return lookup(key.dropLast(2) + "'s", tag, stress, ctx)
        }
        if (key.endsWith("'") && isKnown(key.dropLast(1), tag)) {
            return lookup(key.dropLast(1), tag, stress, ctx)
        }
        stemS(key, tag, stress, ctx).takeIf { it.first != null }?.let { return it }
        stemEd(key, tag, stress, ctx).takeIf { it.first != null }?.let { return it }
        return stemIng(key, tag, stress ?: 0.5, ctx)
    }

    fun isKnown(
        word: String,
        @Suppress("UNUSED_PARAMETER") tag: String?,
    ): Boolean {
        if (word in golds || word in SYMBOLS || word in silvers) return true
        if (word.isEmpty() || !word.all { it in 'A'..'Z' || it in 'a'..'z' }) return false
        if (word.length == 1) return true
        if (word == word.uppercase() && word.lowercase() in golds) return true
        return word.drop(1) == word.drop(1).uppercase()
    }

    fun getNnp(word: String): Pair<String?, Int?> {
        val phones = word.filter(Char::isLetter).map { golds[it.uppercase()]?.asPhones() }
        if (phones.any { it == null }) return null to null
        val unstressed = applyStress(phones.joinToString(""), 0.0) ?: return null to null
        val lastStress = unstressed.lastIndexOf(SECONDARY_STRESS)
        val spelled =
            if (lastStress < 0) {
                unstressed
            } else {
                unstressed.replaceRange(lastStress, lastStress + 1, PRIMARY_STRESS.toString())
            }
        return spelled to 3
    }

    fun stemS(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: TokenContext,
    ): Pair<String?, Int?> {
        if (word.length < 3 || !word.endsWith('s')) return null to null
        val stem =
            when {
                !word.endsWith("ss") && isKnown(word.dropLast(1), tag) -> word.dropLast(1)

                (word.endsWith("'s") || (word.length > 4 && word.endsWith("es") && !word.endsWith("ies"))) &&
                    isKnown(word.dropLast(2), tag) -> word.dropLast(2)

                word.length > 4 && word.endsWith("ies") && isKnown(word.dropLast(3) + "y", tag) -> word.dropLast(3) + "y"

                else -> return null to null
            }
        val (phones, rating) = lookup(stem, tag, stress, ctx)
        return addS(phones) to rating
    }

    fun stemEd(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: TokenContext,
    ): Pair<String?, Int?> {
        if (word.length < 4 || !word.endsWith('d')) return null to null
        val stem =
            when {
                !word.endsWith("dd") && isKnown(word.dropLast(1), tag) -> {
                    word.dropLast(1)
                }

                word.length > 4 && word.endsWith("ed") && !word.endsWith("eed") && isKnown(word.dropLast(2), tag) -> {
                    word.dropLast(2)
                }

                else -> {
                    return null to null
                }
            }
        val (phones, rating) = lookup(stem, tag, stress, ctx)
        return addEd(phones) to rating
    }

    fun stemIng(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: TokenContext,
    ): Pair<String?, Int?> {
        if (word.length < 5 || !word.endsWith("ing")) return null to null
        val stem =
            when {
                word.length > 5 && isKnown(word.dropLast(3), tag) -> word.dropLast(3)
                isKnown(word.dropLast(3) + "e", tag) -> word.dropLast(3) + "e"
                word.length > 5 && DOUBLE_ENDING.containsMatchIn(word) && isKnown(word.dropLast(4), tag) -> word.dropLast(4)
                else -> return null to null
            }
        val (phones, rating) = lookup(stem, tag, stress, ctx)
        return addIng(phones) to rating
    }

    fun getNumber(
        wordWithSuffix: String,
        currency: String?,
        isHead: Boolean,
        numFlags: String,
    ): Pair<String, Int> {
        val suffixMatch = SUFFIX.find(wordWithSuffix)?.value
        var word = if (suffixMatch == null) wordWithSuffix else wordWithSuffix.dropLast(suffixMatch.length)
        val result = mutableListOf<Pair<String, Int>>()
        if (word.startsWith('-')) {
            lookup("minus", null, null, null).toNumberResult()?.let(result::add)
            word = word.drop(1)
        }

        fun extend(
            num: String,
            first: Boolean = true,
            escaped: Boolean = false,
        ) {
            val rendered = if (escaped) num else cardinal(num.toLongOrNull() ?: 0L)
            val words = rendered.split(Regex("[^a-z]+"))
            words.forEachIndexed { index, spoken ->
                if (spoken == "and" && '&' !in numFlags) {
                    if ('n' in numFlags && result.isNotEmpty()) {
                        val previous = result.removeAt(result.lastIndex)
                        result += (previous.first + "ən") to previous.second
                    }
                } else if (spoken.isNotEmpty()) {
                    val useSchwa = first && index == 0 && words.size > 1 && spoken == "one" && 'a' in numFlags
                    val item = if (useSchwa) "ə" to 4 else lookup(spoken, null, if (spoken == "point") -2.0 else null, null)
                    item.toNumberResult()?.let(result::add)
                }
            }
        }
        when {
            isDigit(word) && suffixMatch in ORDINALS -> {
                extend(ordinal(word.toLong()), escaped = true)
            }

            result.isEmpty() && word.length == 4 && currency !in CURRENCIES && isDigit(word) -> {
                extend(year(word.toInt()), escaped = true)
            }

            !isHead && '.' !in word -> {
                val number = word.replace(",", "")
                if (number.startsWith('0') || number.length > 3) {
                    number.forEach { extend(it.toString(), first = false) }
                } else if (number.length == 3 && !number.endsWith("00")) {
                    extend(number.take(1))
                    if (number[1] == '0') {
                        lookup("O", null, -2.0, null).toNumberResult()?.let(result::add)
                        extend(number.takeLast(1), first = false)
                    } else {
                        extend(number.substring(1), first = false)
                    }
                } else {
                    extend(number)
                }
            }

            word.count { it == '.' } > 1 || !isHead -> {
                var first = true
                word.replace(",", "").split('.').forEach { part ->
                    if (part.isNotEmpty()) {
                        if (part.startsWith('0') || (part.length != 2 && part.drop(1).any { it != '0' })) {
                            part.forEach { extend(it.toString(), first = false) }
                        } else {
                            extend(part, first)
                        }
                    }
                    first = false
                }
            }

            currency in CURRENCIES && isCurrency(word) -> {
                val units = CURRENCIES.getValue(currency!!)
                val parts = word.replace(",", "").split('.').map { it.toLongOrNull() ?: 0L }
                val values = parts.take(2).zip(listOf(units.first, units.second))
                val filtered =
                    when {
                        values.size > 1 && values[1].first == 0L -> values.take(1)
                        values.size > 1 && values[0].first == 0L -> values.drop(1)
                        else -> values
                    }
                filtered.forEachIndexed { index, (amount, unit) ->
                    if (index > 0) lookup("and", null, null, null).toNumberResult()?.let(result::add)
                    extend(amount.toString(), first = index == 0)
                    val unitPhones =
                        if (kotlin.math.abs(amount) != 1L && unit != "pence") {
                            stemS("${unit}s", null, null, TokenContext()).first
                        } else {
                            lookup(unit, null, null, null).first
                        }
                    if (unitPhones != null) result += unitPhones to 4
                }
            }

            else -> {
                val expanded =
                    when {
                        isDigit(word) -> cardinal(word.toLongOrNull() ?: 0L)
                        '.' !in word -> ordinalOrCardinal(word, suffixMatch)
                        else -> decimal(word)
                    }
                extend(expanded, escaped = true)
            }
        }
        if (result.isEmpty()) return "" to 0
        var phones = result.joinToString(" ") { it.first }
        val rating = result.minOf { it.second }
        phones =
            when (suffixMatch) {
                "s", "'s" -> addS(phones) ?: phones
                "ed", "'d" -> addEd(phones) ?: phones
                "ing" -> addIng(phones) ?: phones
                else -> phones
            }
        return phones to rating
    }

    fun appendCurrency(
        phones: String,
        currency: String?,
    ): String {
        val units = currency?.let(CURRENCIES::get) ?: return phones
        val plural = stemS("${units.first}s", null, null, TokenContext()).first ?: return phones
        return "$phones $plural"
    }

    /**
     * OpenNLP Penn tags replace Misaki's spaCy tags. `a`/`an` use determiner tags, `I` a pronoun
     * tag, `am` its all-caps/name tag and next-sound context, `by` its adverb tag, `in` its
     * phrase-initial stress rule, `the`/`to` the next resolved sound, and `used` the VBD tag before
     * `to`. Symbol spellings and NNP letter names use the original tables and lexicon data.
     */
    private fun getSpecialCase(
        word: String,
        tag: String?,
        stress: Double?,
        ctx: TokenContext,
    ): Pair<String?, Int?>? {
        val lower = word.lowercase()
        if (word in ADD_SYMBOLS) return lookup(ADD_SYMBOLS.getValue(word), null, -0.5, ctx)
        if (word in SYMBOLS) return lookup(SYMBOLS.getValue(word), null, null, ctx)
        if (word.contains('.') && word.replace(".", "").let { it.isNotEmpty() && it.all(Char::isLetter) } &&
            word.split('.').maxOfOrNull(String::length).orZero() < 3
        ) {
            return getNnp(word)
        }
        return when (lower) {
            "a" -> {
                if (tag == "DT") "ɐ" to 4 else "ˈA" to 4
            }

            "am" -> {
                when {
                    tag?.startsWith("NN") == true -> getNnp(word)
                    ctx.futureVowel == null || word != "am" || (stress ?: 0.0) > 0 -> lookup("am", null, null, ctx).let { it.first to 4 }
                    else -> "ɐm" to 4
                }
            }

            "an" -> {
                if (word == "AN" && tag?.startsWith("NN") == true) getNnp(word) else "ɐn" to 4
            }

            "i" -> {
                if (word == "I") "ˌI" to 4 else null
            }

            "by" -> {
                if (tag == "ADV" || tag?.startsWith("RB") == true) "bˈI" to 4 else null
            }

            "to" -> {
                if (word.equals("TO", true) && tag !in setOf("TO", "IN")) {
                    null
                } else {
                    (
                        if (ctx.futureVowel == null) {
                            lookup("to", null, null, ctx).first
                        } else if (ctx.futureVowel) {
                            "tʊ"
                        } else {
                            "tə"
                        }
                    ) to 4
                }
            }

            "in" -> {
                if (word.equals("IN", true) && tag == "NNP") {
                    null
                } else {
                    (if (ctx.futureVowel == null || tag != "IN") "ˈɪn" else "ɪn") to 4
                }
            }

            "the" -> {
                if (word.equals("THE", true) && tag != "DT") {
                    null
                } else {
                    (if (ctx.futureVowel == true) "ði" else "ðə") to 4
                }
            }

            "used" -> {
                val entry = golds["used"] as? Lexeme.Tagged
                (
                    if (ctx.futureTo &&
                        tag in setOf("VBD", "JJ")
                    ) {
                        entry?.values?.get("VBD")
                    } else {
                        entry?.values?.get("DEFAULT")
                    }
                )?.let { it to 4 }
            }

            else -> {
                null
            }
        }
    }

    private fun addS(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        return when (stem.last()) {
            in "ptkfθ" -> stem + "s"
            in "szʃʒʧʤ" -> stem + "ᵻz"
            else -> stem + "z"
        }
    }

    private fun addEd(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        return when {
            stem.last() in "pkfθʃsʧ" -> stem + "t"
            stem.last() == 'd' -> stem + "ᵻd"
            stem.last() != 't' -> stem + "d"
            stem.length < 2 -> stem + "ɪd"
            stem[stem.lastIndex - 1] in US_TAUS -> stem.dropLast(1) + "ɾᵻd"
            else -> stem + "ᵻd"
        }
    }

    private fun addIng(stem: String?): String? {
        if (stem.isNullOrEmpty()) return null
        return if (stem.length > 1 && stem.last() == 't' && stem[stem.lastIndex - 1] in US_TAUS) {
            stem.dropLast(1) + "ɾɪŋ"
        } else {
            stem + "ɪŋ"
        }
    }

    private fun isCurrency(word: String): Boolean =
        when {
            '.' !in word -> true
            word.count { it == '.' } > 1 -> false
            else -> word.substringAfter('.').length < 3
        }

    private fun isNumber(
        word: String,
        isHead: Boolean,
    ): Boolean {
        if (word.none(Char::isDigit)) return false
        val suffix = listOf("ing", "'d", "ed", "'s", *ORDINALS.toTypedArray(), "s").firstOrNull(word::endsWith)
        val core = if (suffix == null) word else word.dropLast(suffix.length)
        return core.withIndex().all { (index, char) ->
            char.isDigit() || char in ",." || (isHead && index == 0 && char == '-')
        }
    }

    private fun isDigit(word: String): Boolean = word.isNotEmpty() && word.all(Char::isDigit)

    private fun ordinalOrCardinal(
        word: String,
        suffix: String?,
    ): String = if (isDigit(word) && suffix in ORDINALS) ordinal(word.toLongOrNull() ?: 0L) else cardinal(word.toLongOrNull() ?: 0L)

    private fun decimal(word: String): String {
        val normalized = word.replace(",", "")
        if (normalized.startsWith('.')) {
            return "point " + normalized.drop(1).map { cardinal(it.digitToInt().toLong()) }.joinToString(" ")
        }
        val rounded =
            normalized.toDoubleOrNull()?.toString()?.let {
                java.math
                    .BigDecimal(it)
                    .stripTrailingZeros()
                    .toPlainString()
            } ?: normalized
        val (whole, fractional) =
            rounded
                .split('.', limit = 2)
                .let { it.first() to it.getOrElse(1) { "0" } }
        return cardinal(whole.toLongOrNull() ?: 0L) + " point " +
            fractional
                .map {
                    cardinal(it.digitToInt().toLong())
                }.joinToString(" ")
    }

    private fun year(value: Int): String {
        val high = value / 100
        val low = value % 100
        if (high == 0 || (high % 10 == 0 && low < 10) || high >= 100) return cardinal(value.toLong())
        val lower =
            when {
                low == 0 -> "hundred"
                low < 10 -> "oh-${cardinal(low.toLong())}"
                else -> cardinal(low.toLong())
            }
        return "${cardinal(high.toLong())} $lower"
    }

    private fun ordinal(value: Long): String {
        if (value == 0L) return "zeroth"
        val words = cardinal(value).split(Regex("[ -]+")).toMutableList()
        val last = words.last()
        words[words.lastIndex] =
            when (last) {
                "one" -> "first"
                "two" -> "second"
                "three" -> "third"
                "five" -> "fifth"
                "eight" -> "eighth"
                "nine" -> "ninth"
                "twelve" -> "twelfth"
                "twenty" -> "twentieth"
                "thirty" -> "thirtieth"
                "forty" -> "fortieth"
                "fifty" -> "fiftieth"
                "sixty" -> "sixtieth"
                "seventy" -> "seventieth"
                "eighty" -> "eightieth"
                "ninety" -> "ninetieth"
                "hundred" -> "hundredth"
                "thousand" -> "thousandth"
                "million" -> "millionth"
                "billion" -> "billionth"
                else -> if (last.endsWith("y")) last.dropLast(1) + "ieth" else last + "th"
            }
        return words.joinToString(" ")
    }

    private fun cardinal(value: Long): String {
        if (value < 0) return "minus ${cardinal(-value)}"
        if (value < 20) return SMALL[value.toInt()]
        if (value < 100) return TENS[(value / 10).toInt()] + if (value % 10 == 0L) "" else "-${SMALL[(value % 10).toInt()] }"
        if (value < 1000) {
            val rest = value % 100
            return SMALL[(value / 100).toInt()] + " hundred" +
                if (rest == 0L) "" else " and " + cardinal(rest)
        }
        val scale =
            when {
                value >= 1_000_000_000_000_000_000L -> 1_000_000_000_000_000_000L to "quintillion"
                value >= 1_000_000_000_000_000L -> 1_000_000_000_000_000L to "quadrillion"
                value >= 1_000_000_000_000L -> 1_000_000_000_000L to "trillion"
                value >= 1_000_000_000L -> 1_000_000_000L to "billion"
                value >= 1_000_000L -> 1_000_000L to "million"
                else -> 1_000L to "thousand"
            }
        val major = value / scale.first
        val rest = value % scale.first
        return cardinal(major) + " " + scale.second +
            if (rest == 0L) {
                ""
            } else {
                if (rest < 100) " and " else " "
            } + if (rest == 0L) "" else cardinal(rest)
    }

    private fun numericIfNeeded(char: Char): Char {
        if (!char.isDigit()) return char
        return Character.getNumericValue(char).takeIf { it in 0..9 }?.digitToChar() ?: char
    }

    private fun String.normalizeNfkc(): String = java.text.Normalizer.normalize(this, java.text.Normalizer.Form.NFKC)

    private fun Lexeme?.asPhones(): String? =
        when (this) {
            is Lexeme.Phones -> value
            else -> null
        }

    private fun parentTag(tag: String?): String? =
        when {
            tag == null -> null
            tag.startsWith("VB") -> "VERB"
            tag.startsWith("NN") -> "NOUN"
            tag.startsWith("JJ") -> "ADJ"
            tag.startsWith("RB") -> "ADV"
            tag.startsWith("ADV") -> "ADV"
            tag.startsWith("ADJ") -> "ADJ"
            else -> tag
        }

    private fun Pair<String?, Int?>.toNumberResult(): Pair<String, Int>? = first?.let { it to (second ?: 4) }

    private fun Int?.orZero(): Int = this ?: 0

    private fun load(input: InputStream): Map<String, Lexeme> =
        GZIPInputStream(input).bufferedReader(Charsets.UTF_8).use { reader ->
            PhonemeJson.parse(reader.readText()).asObject().mapValues { (_, value) ->
                when (value) {
                    is PhonemeJsonValue.StringValue -> {
                        Lexeme.Phones(value.value)
                    }

                    is PhonemeJsonValue.ObjectValue -> {
                        Lexeme.Tagged(
                            value.values.mapValues { (_, phones) ->
                                when (phones) {
                                    is PhonemeJsonValue.StringValue -> phones.value
                                    PhonemeJsonValue.NullValue -> null
                                    else -> error("Expected a string or null in a tagged lexicon entry")
                                }
                            },
                        )
                    }

                    PhonemeJsonValue.NullValue -> {
                        Lexeme.Phones(null)
                    }

                    else -> {
                        error("Expected a string, null, or tagged lexicon object")
                    }
                }
            }
        }

    companion object {
        private const val PRIMARY_STRESS = 'ˈ'
        private const val SECONDARY_STRESS = 'ˌ'
        private val STRESSES = setOf(PRIMARY_STRESS, SECONDARY_STRESS)
        private val VOWELS = "AIOQWYaiuæɑɒɔəɛɜɪʊʌᵻ"
        private val US_TAUS = "AIOWYiuæɑəɛɪɹʊʌ"
        private val CURRENCIES = mapOf("$" to ("dollar" to "cent"), "£" to ("pound" to "pence"), "€" to ("euro" to "cent"))
        private val SYMBOLS = mapOf("%" to "percent", "&" to "and", "+" to "plus", "@" to "at")
        private val ADD_SYMBOLS = mapOf("." to "dot", "/" to "slash")
        private val ORDINALS = setOf("st", "nd", "rd", "th")
        private val SUFFIX = Regex("[a-z']+$")
        private val DOUBLE_ENDING = Regex("([bcdgklmnprstvxz])\\1ing$|cking$")
        private val SMALL =
            listOf(
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
            )
        private val TENS = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")

        private fun applyStress(
            phones: String?,
            stress: Double?,
        ): String? {
            if (phones == null || stress == null) return phones

            fun restress(input: String): String {
                val chars = input.withIndex().toList()
                val adjusted =
                    chars.mapNotNull { (index, char) ->
                        if (char !in STRESSES) {
                            index.toDouble() to char
                        } else {
                            val vowel = chars.drop(index).firstOrNull { it.value in VOWELS }?.index ?: return@mapNotNull null
                            (vowel - 0.5) to char
                        }
                    }
                return adjusted.sortedBy { it.first }.joinToString("") { it.second.toString() }
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

        /** Load the committed US lexicons from compressed streams. */
        fun fromGzip(
            gold: InputStream,
            silver: InputStream,
        ): EnglishLexicon {
            val temporary = EnglishLexicon(emptyMap(), emptyMap())
            return EnglishLexicon(
                temporary.load(gold).growDictionary().withReadFixes(),
                temporary.load(silver).growDictionary(),
            )
        }

        /** JVM-friendly seam for tests and tools that keep assets as files. */
        fun fromFiles(
            gold: File,
            silver: File,
        ): EnglishLexicon = fromGzip(gold.inputStream(), silver.inputStream())

        private fun Map<String, Lexeme>.growDictionary(): Map<String, Lexeme> {
            val grown = mutableMapOf<String, Lexeme>()
            forEach { (key, value) ->
                if (key.length >= 2) {
                    if (key == key.lowercase() && key != key.replaceFirstChar(Char::uppercase)) {
                        grown[key.replaceFirstChar(Char::uppercase)] = value
                    } else if (key == key.lowercase().replaceFirstChar(Char::uppercase)) {
                        grown[key.lowercase()] = value
                    }
                }
            }
            grown.putAll(this)
            return grown
        }

        /** Applies the linked README's three VBP fixes to lower/title-case variants. */
        private fun Map<String, Lexeme>.withReadFixes(): Map<String, Lexeme> {
            val corrected = toMutableMap()
            MISAKI_VBP_FIXES.forEach { (word, fixes) ->
                listOf(word, word.replaceFirstChar(Char::uppercase)).forEach keyLoop@{ key ->
                    val entry = corrected[key] as? Lexeme.Tagged ?: return@keyLoop
                    corrected[key] = Lexeme.Tagged(entry.values + fixes)
                }
            }
            return corrected
        }

        private val MISAKI_VBP_FIXES =
            mapOf(
                "read" to mapOf("VBP" to "ɹˈid"),
                "reread" to mapOf("VBP" to "ɹˌiɹˈid"),
                "wound" to mapOf("VBP" to "wˈund"),
            )
    }
}
