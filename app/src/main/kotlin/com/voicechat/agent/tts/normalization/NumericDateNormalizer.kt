package com.voicechat.agent.tts.normalization

/**
 * Spells numeric dates: `02/03/2007` (ambiguous `d/m/y`) and the unambiguous
 * ISO form `2007-02-03`.
 *
 * Written slashed dates are read with the day first, matching the
 * day-before-month rule used by Ossian's British variant. A month name is used
 * so the result can never be mistaken for a fraction, and a bare two-digit
 * year is split into digits ("two thousand seven" for `07` would be wrong).
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class NumericDateNormalizer : TextNormalizationRule {
    override fun apply(text: String): String {
        var result =
            ISO_DATE.replace(text) { match ->
                val (year, month, day) = match.destructured
                if (!validDate(year, month, day)) return@replace match.value
                "${monthName(month)} ${NumberSpeller.ordinal(day.toLong())}, " +
                    NumberSpeller.spellToken(year, NumberSpeller.Reading.YEAR)
            }
        result =
            SLASHED_DATE.replace(result) { match ->
                val (day, month, year) = match.destructured
                if (!validDate(year, month, day)) return@replace match.value
                val expandedYear = if (year.length == 2) 2000 + year.toInt() else year.toInt()
                "${monthName(month)} ${NumberSpeller.ordinal(day.toLong())}, " +
                    NumberSpeller.year(expandedYear)
            }
        return result
    }

    private fun validDate(
        year: String,
        month: String,
        day: String,
    ): Boolean {
        val monthNumber = month.toIntOrNull() ?: return false
        val dayNumber = day.toIntOrNull() ?: return false
        val expandedYear = if (year.length == 2) 2000 + year.toInt() else year.toInt()
        return monthNumber in 1..12 && dayNumber in 1..31 && expandedYear in 1000..2099
    }

    private fun monthName(number: String): String? = MONTH_NAMES.getOrNull((number.toIntOrNull() ?: return null) - 1)

    private companion object {
        private val MONTH_NAMES =
            listOf(
                "January",
                "February",
                "March",
                "April",
                "May",
                "June",
                "July",
                "August",
                "September",
                "October",
                "November",
                "December",
            )

        /** `02/03/2007` and `2-3-07`, with a day and month that exist. */
        private val SLASHED_DATE =
            Regex(
                "(?<![\\p{L}\\p{N}])(\\d{1,2})/(\\d{1,2})/(\\d{2,4})(?![\\p{L}\\p{N}\\d/])",
            )

        /** `2007-02-03`. */
        private val ISO_DATE =
            Regex(
                "(?<![\\p{L}\\p{N}])(\\d{4})-(\\d{2})-(\\d{2})(?![\\p{L}\\p{N}])",
            )
    }
}
