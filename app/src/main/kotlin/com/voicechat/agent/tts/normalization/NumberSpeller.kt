package com.voicechat.agent.tts.normalization

/**
 * Spells out English numerals in words.
 *
 * Cardinal output follows the CLDR `spellout-numbering` convention (no "and",
 * hyphenated tens, e.g. `1234` -> "one thousand two hundred thirty-four") and
 * years follow `spellout-numbering-year` (`1999` -> "nineteen ninety-nine",
 * `2005` -> "two thousand five", `2013` -> "twenty thirteen"). Ordinals and
 * decades build on the cardinal form. Floating point is read digit by digit
 * after "point" (`3.14` -> "three point one four").
 *
 * The public functions expect normalized input: callers that may see ranges or
 * negative values ([NumberNormalizer], [RangeNormalizer], [UnitAndSymbolNormalizer],
 * [CurrencyNormalizer]) screen those forms or check for them first.
 *
 * Pure Kotlin and locale-independent so the rules are testable on the host
 * JVM and do not depend on `android.icu`.
 * Tables follow Unicode CLDR RBNF (`common/rbnf/en.xml`).
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal object NumberSpeller {
    /** Whether a bare token is spoken as a year or as a cardinal quantity. */
    enum class Reading {
        YEAR,
        QUANTITY,
    }

    /** Largest value [ordinal] and the scale-word path support. */
    private const val MAX_ORDINAL = 999_999_999_999L

    private val ONES =
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
        )

    private val TEENS =
        listOf(
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

    private val TENS =
        listOf(
            "",
            "",
            "twenty",
            "thirty",
            "forty",
            "fifty",
            "sixty",
            "seventy",
            "eighty",
            "ninety",
        )

    /** Scale words indexed by thousands group (10^0, 10^3, ... 10^18). */
    private val SCALES =
        listOf(
            "",
            "thousand",
            "million",
            "billion",
            "trillion",
            "quadrillion",
            "quintillion",
        )

    private val ORDINAL_IRREGULAR =
        mapOf(
            "one" to "first",
            "two" to "second",
            "three" to "third",
            "four" to "fourth",
            "five" to "fifth",
            "six" to "sixth",
            "seven" to "seventh",
            "eight" to "eighth",
            "nine" to "ninth",
            "ten" to "tenth",
            "eleven" to "eleventh",
            "twelve" to "twelfth",
            "hundred" to "hundredth",
            "thousand" to "thousandth",
            "million" to "millionth",
            "billion" to "billionth",
            "trillion" to "trillionth",
            "quadrillion" to "quadrillionth",
            "quintillion" to "quintillionth",
        )

    /** Spells any [value] the way it would be read as a quantity. */
    fun cardinal(value: Long): String {
        if (value == 0L) return ONES[0]
        require(value > 0) { "cardinal() expects a non-negative value: $value" }

        val groups = ArrayList<Int>()
        var remaining = value
        while (remaining > 0) {
            groups.add((remaining % 1000).toInt())
            remaining /= 1000
        }

        val parts = ArrayList<String>()
        for (index in groups.indices.reversed()) {
            val group = groups[index]
            if (group == 0) continue
            val words = under1000(group)
            val scale = SCALES[index]
            parts.add(if (scale.isEmpty()) words else "$words $scale")
        }
        return parts.joinToString(" ")
    }

    /**
     * Spells a four-digit year the way it is normally spoken. Values outside
     * the 1000..2099 range fall back to [cardinal].
     */
    fun year(value: Int): String =
        when {
            value == 1000 -> {
                "one thousand"
            }

            value in 1001..1999 -> {
                val first = value / 100
                val last = value % 100
                when {
                    last == 0 -> "${cardinal(first.toLong())} hundred"
                    last < 10 -> "${cardinal(first.toLong())} oh ${under1000(last)}"
                    else -> "${cardinal(first.toLong())} ${under1000(last)}"
                }
            }

            value in 2000..2009 -> {
                val last = value % 100
                if (last == 0) "two thousand" else "two thousand ${under1000(last)}"
            }

            value in 2010..2099 -> {
                "twenty ${under1000(value % 100)}"
            }

            else -> {
                cardinal(value.toLong())
            }
        }

    /** Spells a bare numeral (with optional comma grouping / decimal) as a quantity. */
    fun quantity(raw: String): String {
        val text = if (raw.startsWith('-')) raw.substring(1) else raw
        val minus = if (raw.startsWith('-')) "minus " else ""
        return minus + spellToken(text, Reading.QUANTITY)
    }

    /**
     * Spells a bare numeral according to [reading]. Callers must explicitly
     * choose year style for unmarked prose/date years and quantity style for
     * measurements, amounts, percentages, and other quantities. A leading
     * minus is not expected here; [quantity] handles it and [NumberNormalizer]
     * strips it before calling this method.
     */
    fun spellToken(
        raw: String,
        reading: Reading,
    ): String {
        val dot = raw.indexOf('.')
        val integerText = (if (dot >= 0) raw.substring(0, dot) else raw).replace(",", "")

        if (reading == Reading.YEAR && dot < 0 && ',' !in raw && integerText.length == 4) {
            val year = integerText.toIntOrNull()
            if (year != null && year in 1000..2099) {
                return year(year)
            }
        }

        val integer = integerText.toLongOrNull() ?: return raw
        return if (dot < 0) {
            cardinal(integer)
        } else {
            decimal(integer, raw.substring(dot + 1))
        }
    }

    /**
     * Spells an ordinal, e.g. `21` -> "twenty-first". Numbers after the
     * millions are not supported (the result would name no real position).
     */
    fun ordinal(value: Long): String {
        if (value == 0L) return "zeroth"
        if (value < 0) return "minus " + ordinal(-value)
        if (value > MAX_ORDINAL) return cardinal(value) + "th"

        val ordinalValue = value % 1_000_000_000_000_000_000L
        if (ordinalValue == 0L && value <= MAX_ORDINAL) {
            // A pure scale word, e.g. 1,000,000 -> "millionth". Values past the
            // quintillions are unsupported by the scale table.
            val power = value.toString().length - 1
            val scaleWord = SCALES.getOrNull(power / 3) ?: return cardinal(value) + "th"
            return ORDINAL_IRREGULAR[scaleWord] ?: (scaleWord + "th")
        }

        val words = cardinal(value)
        val lastSpace = words.lastIndexOf(' ')
        val prefix = if (lastSpace >= 0) words.substring(0, lastSpace + 1) else ""
        val tail = if (lastSpace >= 0) words.substring(lastSpace + 1) else words
        val hyphen = tail.lastIndexOf('-')
        val ordinalTail =
            if (hyphen >= 0) {
                tail.substring(0, hyphen + 1) + ordinalWord(tail.substring(hyphen + 1))
            } else {
                ordinalWord(tail)
            }
        return prefix + ordinalTail
    }

    /**
     * Spells a decade, e.g. `1990` -> "nineteen nineties", `80` -> "eighties".
     * A hundred-year boundary reads as its own word ("two thousands").
     */
    fun decade(value: Int): String =
        when {
            value % 100 == 0 && value in 1000..2099 -> {
                cardinal(value.toLong()) + "s"
            }

            value in 1000..2099 -> {
                val words = year(value)
                val firstSpace = words.indexOf(' ')
                val head = if (firstSpace < 0) "" else words.substring(0, firstSpace + 1)
                head + pluralizeLast(words.substringAfter(' '))
            }

            value % 10 != 0 -> {
                cardinal(value.toLong()) + "s"
            }

            value in 20..90 -> {
                pluralizeLast(TENS[value / 10])
            }

            else -> {
                cardinal(value.toLong()) + "s"
            }
        }

    /** Spells a decimal number, reading [fractionDigits] one digit at a time. */
    fun decimal(
        integerPart: Long,
        fractionDigits: String,
    ): String = "${cardinal(integerPart)} point ${digits(fractionDigits)}"

    /** Spells each character of [digits] independently, e.g. `"14"` -> "one four". */
    fun digits(digits: String): String =
        digits
            .map { character ->
                if (character.isDigit()) ONES[character - '0'] else character.toString()
            }.joinToString(" ")

    private fun ordinalWord(word: String): String =
        if (word.endsWith("y")) {
            word.dropLast(1) + "ieth"
        } else {
            ORDINAL_IRREGULAR[word] ?: (word + "th")
        }

    private fun pluralizeLast(words: String): String {
        val lastSpace = words.lastIndexOf(' ')
        return if (lastSpace < 0) {
            pluralWord(words)
        } else {
            words.substring(0, lastSpace + 1) + pluralWord(words.substring(lastSpace + 1))
        }
    }

    private fun pluralWord(word: String): String = if (word.endsWith("y")) word.dropLast(1) + "ies" else word + "s"

    private fun under1000(value: Int): String =
        when {
            value < 10 -> {
                ONES[value]
            }

            value < 20 -> {
                TEENS[value - 10]
            }

            value < 100 -> {
                val tens = TENS[value / 10]
                val ones = value % 10
                if (ones == 0) tens else "$tens-${ONES[ones]}"
            }

            else -> {
                val hundreds = "${ONES[value / 100]} hundred"
                val rest = value % 100
                if (rest == 0) hundreds else "$hundreds ${under1000(rest)}"
            }
        }
}
