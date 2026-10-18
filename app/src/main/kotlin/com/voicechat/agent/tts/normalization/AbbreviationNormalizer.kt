package com.voicechat.agent.tts.normalization

/**
 * Expands common English abbreviations to the words a reader would say.
 *
 * The list is deliberately conservative ("do no harm"): every entry is a
 * near-unambiguous title, Latin abbreviation, month, day or organisation that
 * a TTS engine would otherwise mangle. Ambiguous short forms (`no.`, `in.`,
 * `ft.`, `vol.`) are intentionally left out unless a safe context rule can be
 * added later.
 *
 * Sources distilled for the initial list: the Ossian text normalisation
 * abbreviation map (CSTR Edinburgh), MaryTTS `preprocess/abbrev.dat`, the
 * Oxford-derived MUSE abbreviation dictionary, the Google Kestrel/
 * WeTextProcessing whitespace whitelist, and Tortoise-TTS `_abbreviations`.
 * The conservative policy follows Sproat et al., "Hippocratic Abbreviation Expansion".
 * Ported from EpubReader's `playback/normalization` package with its table retained.
 */
internal class AbbreviationNormalizer : TextNormalizationRule {
    override fun apply(text: String): String {
        val withPlaces =
            STREET_OR_SAINT.replace(text) { match ->
                if (isSaintContext(text, match.range)) SAINT else STREET
            }
        return ABBREVIATIONS.replace(withPlaces) { match ->
            EXPANSIONS.getValue(match.value.lowercase())
        }
    }

    /**
     * "St." is the one entry whose expansion depends on context: a following
     * capitalised word is a name ("St. John" -> Saint), while a preceding word
     * ending the token is a street ("Main St." -> Street).
     */
    private fun isSaintContext(
        text: String,
        range: IntRange,
    ): Boolean {
        var ahead = range.last + 1
        while (ahead < text.length && text[ahead] == ' ') ahead++
        if (ahead < text.length && text[ahead].isUpperCase()) return true

        var behind = range.first - 1
        while (behind >= 0 && text[behind] == ' ') behind--
        return behind < 0 || !text[behind].isLetterOrDigit()
    }

    private companion object {
        private const val SAINT = "Saint"
        private const val STREET = "Street"

        /** Word-boundary "St." handled separately from the static map. */
        private val STREET_OR_SAINT =
            Regex(
                "(?<![\\p{L}\\p{N}])St\\.(?![\\p{L}\\p{N}])",
                RegexOption.IGNORE_CASE,
            )

        private val EXPANSIONS: Map<String, String> =
            linkedMapOf(
                // Honorifics, titles and ranks.
                "mr." to "Mister",
                "mrs." to "Missus",
                "ms." to "Miz",
                "dr." to "Doctor",
                "drs." to "Doctors",
                "prof." to "Professor",
                "rev." to "Reverend",
                "fr." to "Father",
                "hon." to "Honorable",
                "gov." to "Governor",
                "sen." to "Senator",
                "rep." to "Representative",
                "pres." to "President",
                "gen." to "General",
                "col." to "Colonel",
                "lt." to "Lieutenant",
                "maj." to "Major",
                "capt." to "Captain",
                "cmdr." to "Commander",
                "adm." to "Admiral",
                "sgt." to "Sergeant",
                "cpl." to "Corporal",
                "pvt." to "Private",
                "jr." to "Junior",
                "sr." to "Senior",
                "esq." to "Esquire",
                "messrs." to "Messieurs",
                "msgr." to "Monsignor",
                "mt." to "Mount",
                // Latin and common.
                "etc." to "et cetera",
                "vs." to "versus",
                "e.g." to "for example",
                "i.e." to "that is",
                "et al." to "and others",
                "cf." to "compare",
                "viz." to "namely",
                "ibid." to "in the same place",
                "n.b." to "note well",
                "p.s." to "postscript",
                "r.s.v.p." to "please reply",
                "a.m." to "A M",
                "p.m." to "P M",
                // Business and organisations.
                "inc." to "Incorporated",
                "corp." to "Corporation",
                "ltd." to "Limited",
                "co." to "Company",
                "bros." to "Brothers",
                // Months.
                "jan." to "January",
                "feb." to "February",
                "mar." to "March",
                "apr." to "April",
                "jun." to "June",
                "jul." to "July",
                "aug." to "August",
                "sep." to "September",
                "sept." to "September",
                "oct." to "October",
                "nov." to "November",
                "dec." to "December",
                // Days.
                "mon." to "Monday",
                "tue." to "Tuesday",
                "tues." to "Tuesday",
                "wed." to "Wednesday",
                "thu." to "Thursday",
                "thur." to "Thursday",
                "thurs." to "Thursday",
                "fri." to "Friday",
                "sat." to "Saturday",
                "sun." to "Sunday",
            )

        /**
         * Longest-first alternation so "Mrs." wins over "Mr." and "Sept."
         * over "Sep.", with word boundaries so "drum" or "MS" never match.
         */
        private val ABBREVIATIONS =
            Regex(
                EXPANSIONS.keys
                    .sortedByDescending { it.length }
                    .joinToString("|") { Regex.escape(it) }
                    .let { "(?<![\\p{L}\\p{N}])(?:$it)(?![\\p{L}\\p{N}])" },
                RegexOption.IGNORE_CASE,
            )
    }
}
