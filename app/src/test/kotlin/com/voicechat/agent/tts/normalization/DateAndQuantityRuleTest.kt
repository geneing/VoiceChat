package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class DateAndQuantityRuleTest {
    private val monthDay = MonthDayNormalizer()
    private val numericDate = NumericDateNormalizer()
    private val ordinal = OrdinalNormalizer()
    private val decade = DecadeNormalizer()
    private val currency = CurrencyNormalizer()
    private val percentage = PercentageNormalizer()
    private val range = RangeNormalizer()
    private val time = TimeNormalizer()

    @Test
    fun monthNamesDatesReadAsOrdinals() {
        // The day becomes an ordinal and the year uses the year-style reading.
        assertEquals("February thirteenth two thousand seven", monthDay.apply("Feb. 13, 2007"))
        assertEquals("February thirteenth two thousand seven", monthDay.apply("February 13th, 2007"))
        assertEquals("January first", monthDay.apply("Jan. 1"))
        assertEquals("September two thousand one", monthDay.apply("Sept. 2001"))
        // A year outside the supported span is declined; the number rule can
        // still spell it later in the pipeline.
        assertEquals("Sept. 4000", monthDay.apply("Sept. 4000"))
    }

    @Test
    fun numericDatesReadDayFirst() {
        // `02/03` is day 2 of month 3 (day before month).
        assertEquals("March second, two thousand seven", numericDate.apply("02/03/2007"))
        assertEquals("March second, two thousand seven", numericDate.apply("2007-03-02"))
        assertEquals("March second, nineteen ninety-nine", numericDate.apply("02/03/1999"))
        assertEquals("March second, two thousand seven", numericDate.apply("2/3/07"))
        // A month the year cannot have is left to the other rules.
        assertEquals("99/99/2007", numericDate.apply("99/99/2007"))
    }

    @Test
    fun ordinals() {
        assertEquals("twenty-first", ordinal.apply("21st"))
        assertEquals("third", ordinal.apply("3rd"))
        assertEquals("one hundredth", ordinal.apply("100th"))
        assertEquals("twelfth", ordinal.apply("12th"))
    }

    @Test
    fun decades() {
        assertEquals("nineteen nineties", decade.apply("1990s"))
        assertEquals("nineteen eighties", decade.apply("'80s"))
        assertEquals("eighties", decade.apply("80s"))
    }

    @Test
    fun currency() {
        assertEquals("five dollars", currency.apply("$5"))
        assertEquals("nine pounds", currency.apply("£9"))
        assertEquals("twenty euros", currency.apply("€20"))
        assertEquals("one thousand five hundred dollars", currency.apply("$1500"))
        assertEquals("twelve dollars and thirty-four cents", currency.apply("$12.34"))
        assertEquals(
            "one thousand two hundred thirty-four dollars and fifty-six cents",
            currency.apply("$1,234.56"),
        )
        assertEquals("three pounds and fifty pence", currency.apply("£3.50"))
        assertEquals("five dollars and five cents", currency.apply("$5.05"))
        assertEquals("five dollars", currency.apply("5 dollars"))
        assertEquals("three euros", currency.apply("3 euros"))
    }

    @Test
    fun percentagesReadAsQuantities() {
        assertEquals("fifty percent", percentage.apply("50%"))
        assertEquals("three point five percent", percentage.apply("3.5 %"))
        // "one thousand five hundred", never "fifteen hundred".
        assertEquals("one thousand five hundred percent", percentage.apply("1500%"))
    }

    @Test
    fun ranges() {
        assertEquals("three to five", range.apply("3-5"))
        assertEquals("one thousand to two thousand", range.apply("1,000-2,000"))
        assertEquals("five to seven", range.apply("5 \u2013 7"))
    }

    @Test
    fun times() {
        assertEquals("nine thirty", time.apply("9:30"))
        assertEquals("nine oh five", time.apply("09:05"))
        assertEquals("nine thirty in the evening", time.apply("9:30 p.m."))
        assertEquals("seven o'clock in the morning", time.apply("7:00 a.m."))
        assertEquals("twelve o'clock", time.apply("12:00"))
    }
}
