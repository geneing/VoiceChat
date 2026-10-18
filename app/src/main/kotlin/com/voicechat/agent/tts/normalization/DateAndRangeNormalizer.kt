package com.voicechat.agent.tts.normalization

/**
 * Rules for the numerals that already carry structure: currency, percentages,
 * decades, ordinals, dates and number ranges.
 *
 * Rules are listed most-specific first so a value is claimed once: a date
 * before a plain range, an ordinal before a bare integer, and so on. All of
 * them run before [NumberNormalizer], which handles whatever is left.
 *
 * The list contains no standalone integer rule, so the `1990-2000` range match
 * is never pre-empted by each year spelling itself out first.
 * Ported from EpubReader's `playback/normalization` package with order retained.
 */
internal val DATE_TIME_AND_QUANTITY_RULES: List<TextNormalizationRule> =
    listOf(
        MonthDayNormalizer(),
        NumericDateNormalizer(),
        DecadeNormalizer(),
        OrdinalNormalizer(),
        CurrencyNormalizer(),
        PercentageNormalizer(),
        RangeNormalizer(),
        TimeNormalizer(),
    )
