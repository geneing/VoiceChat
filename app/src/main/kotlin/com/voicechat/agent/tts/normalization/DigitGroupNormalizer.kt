package com.voicechat.agent.tts.normalization

/**
 * Converts grouped digit runs that carry a number's internal structure —
 * phone numbers, long digit strings and version numbers — so they are read
 * digit by digit instead of as one enormous quantity.
 *
 * Runs after the date and range rules (so `555-1234` has already been claimed
 * as a phone number) and before [UnitAndSymbolNormalizer]/[NumberNormalizer].
 * A short year-like range such as `1990-2000` deliberately falls through to the
 * range rule rather than being read as "one nine nine zero…".
 * Ported from EpubReader's `playback/normalization` package with behavior retained.
 */
internal class DigitGroupNormalizer : TextNormalizationRule {
    private val phone =
        Regex(
            "(?<![\\p{L}\\p{N}])" +
                "(?:\\(\\d{3}\\)\\s?|\\d{3}[-.\\s])" + // (415) or 415-
                "\\d{3}[-.\\s]\\d{4}" +
                "(?:\\s*(?:x|ext\\.?|extension)\\s*\\d{1,6})?" +
                "(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    private val version =
        Regex(
            "(?<![\\p{L}\\p{N}])v?\\d+(?:\\.\\d+){2,}(?![\\p{L}\\p{N}])",
            RegexOption.IGNORE_CASE,
        )

    private val longDigits = Regex("(?<![\\p{L}\\p{N}])\\d{5,}(?![\\p{L}\\p{N}])")

    override fun apply(text: String): String {
        val ranges = YEAR_RANGE.replace(text) { it.value.replace(PHONE_SEPARATOR, "-") }
        val spokenPhone = phone.replace(ranges) { match -> spellPhone(match.value) }
        val spokenVersion =
            version.replace(spokenPhone) { match ->
                match.value.trimStart('v', 'V').split('.').joinToString(" point ") {
                    NumberSpeller.digits(it)
                }
            }
        return longDigits.replace(spokenVersion) { match -> NumberSpeller.digits(match.value) }
    }

    private fun spellPhone(raw: String): String {
        val match = PHONE_PARTS.matchEntire(raw) ?: return raw
        val matchGroups = match.groupValues
        val area = matchGroups[1].ifEmpty { matchGroups[2] }
        val prefix = matchGroups[3]
        val line = matchGroups[4]
        val extension = matchGroups[5]
        val head = if (area.isNotEmpty()) "area code ${digitWord(area)} " else ""
        val spoken = "$head${digitWord(prefix)} ${digitWord(line)}"
        return if (extension.isNotEmpty()) {
            "$spoken extension ${digitWord(extension)}"
        } else {
            spoken
        }
    }

    /** Spells a digit group with the usual "five five five" pacing. */
    private fun digitWord(digits: String): String = NumberSpeller.digits(digits)

    private companion object {
        /** Matches the exact text [phone] accepted, with the area code optional. */
        private val PHONE_PARTS =
            Regex(
                "(?:\\((\\d{3})\\)\\s?|(\\d{3})[-.\\s])?" +
                    "(\\d{3})[-.\\s](\\d{4})" +
                    "(?:\\s*(?:x|ext\\.?|extension)\\s*(\\d{1,6}))?",
                RegexOption.IGNORE_CASE,
            )

        /** Two four-digit years joined by a dash are a range, not a digit run. */
        private val YEAR_RANGE =
            Regex(
                "\\d{4}[-\\u2013]\\d{4}",
            )

        /** Dash characters that must become a hyphen so RangeNormalizer sees them. */
        private val PHONE_SEPARATOR = Regex("[-\\u2013]")
    }
}
