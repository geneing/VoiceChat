package com.voicechat.agent.tts.normalization

/**
 * Spells a date written with a month name: "Feb. 13, 2007", "February 13th,
 * 2007", "Feb 2007". Runs before [NumberNormalizer] so the day reads as an
 * ordinal rather than a cardinal.
 *
 * Full month names and the common abbreviations are both accepted. The month
 * may carry its abbreviation period; [AbbreviationNormalizer] strips it later,
 * which is harmless because the period is the last character.
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class MonthDayNormalizer : TextNormalizationRule {
    override fun apply(text: String): String =
        MONTH_FIRST.replace(text) { match ->
            val month = match.groupValues[1]
            val day = match.groupValues[2]
            val year = match.groupValues[3]
            if (day.isNotEmpty() && day.toIntOrNull() !in 1..31) return@replace match.value
            if (year.isNotEmpty() && year.toIntOrNull() !in 1000..2099) return@replace match.value
            val spoken =
                buildString {
                    append(monthName(month))
                    if (day.isNotEmpty()) append(' ').append(NumberSpeller.ordinal(day.toLong()))
                    if (year.isNotEmpty()) {
                        append(' ').append(NumberSpeller.spellToken(year, NumberSpeller.Reading.YEAR))
                    }
                }
            spoken
        }

    private fun monthName(raw: String): String = MONTHS.getValue(raw.lowercase().removeSuffix("."))

    private companion object {
        private val MONTHS =
            mapOf(
                "january" to "January",
                "jan" to "January",
                "february" to "February",
                "feb" to "February",
                "march" to "March",
                "mar" to "March",
                "april" to "April",
                "apr" to "April",
                "may" to "May",
                "june" to "June",
                "jun" to "June",
                "july" to "July",
                "jul" to "July",
                "august" to "August",
                "aug" to "August",
                "september" to "September",
                "sept" to "September",
                "sep" to "September",
                "october" to "October",
                "oct" to "October",
                "november" to "November",
                "nov" to "November",
                "december" to "December",
                "dec" to "December",
            )

        private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")

        /**
         * Month, optional day, optional year. A comma or whitespace may
         * separate the parts; the day is at most two digits so a four-digit
         * run is always read as the year.
         */
        private val MONTH_FIRST =
            Regex(
                "(?<![\\p{L}\\p{N}])($MONTH)\\.?" +
                    "(?:[\\s,]+(\\d{1,2})(?:st|nd|rd|th)?(?![\\d]))?" +
                    "(?:[\\s,]+(\\d{4}))?" +
                    "(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            )
    }
}
