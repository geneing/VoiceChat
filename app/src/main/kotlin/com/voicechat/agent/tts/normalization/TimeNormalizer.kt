package com.voicechat.agent.tts.normalization

/**
 * Spells clock times: `9:30`, `09:05`, `9:30 a.m.`, `12:00`.
 *
 * A time is always "hours minutes", unlike a year, so the digit groups are
 * read as their own numbers ("nine thirty") and never combined. Runs before
 * [AbbreviationNormalizer] turns `p.m.` into "P M"; the meridiem word is
 * emitted here so the result is "nine thirty in the evening".
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class TimeNormalizer : TextNormalizationRule {
    override fun apply(text: String): String =
        clock.replace(text) { match ->
            val hour = match.groupValues[1].toInt()
            val minute = match.groupValues[2]
            val meridiem = match.groupValues[3]
            val spokenMinute =
                when {
                    minute == "00" -> "o'clock"
                    minute.startsWith("0") -> "oh ${NumberSpeller.cardinal(minute.drop(1).toInt().toLong())}"
                    else -> NumberSpeller.cardinal(minute.toInt().toLong())
                }
            val suffix =
                when {
                    meridiem.startsWith("a", ignoreCase = true) -> " in the morning"
                    meridiem.startsWith("p", ignoreCase = true) -> " in the evening"
                    else -> ""
                }
            "${NumberSpeller.cardinal(hour.toLong())} $spokenMinute$suffix"
        }

    private companion object {
        private val clock =
            Regex(
                "(?<![\\p{L}\\p{N}:])" +
                    "([01]?\\d|2[0-3]):([0-5]\\d)" +
                    "(?:\\s?([ap]\\.?m\\.?))?" +
                    "(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            )
    }
}
