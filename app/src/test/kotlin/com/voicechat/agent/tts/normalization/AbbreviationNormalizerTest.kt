package com.voicechat.agent.tts.normalization

import org.junit.Assert.assertEquals
import org.junit.Test

class AbbreviationNormalizerTest {
    private val normalizer = AbbreviationNormalizer()

    @Test
    fun expandsHonorificsAndTitles() {
        assertEquals(
            "Mister Smith met Doctor Jones.",
            normalizer.apply("Mr. Smith met Dr. Jones."),
        )
        assertEquals("Miz Lee and Missus Doe.", normalizer.apply("Ms. Lee and Mrs. Doe."))
        assertEquals(
            "Professor Plum, Reverend Green.",
            normalizer.apply("Prof. Plum, Rev. Green."),
        )
        assertEquals("Doctors Smith and Junior Doe.", normalizer.apply("Drs. Smith and Jr. Doe."))
    }

    @Test
    fun expandsLatinAndCommonAbbreviations() {
        // The abbreviation's own period is consumed by the expansion; the
        // engine re-adds terminal punctuation when it ends an utterance.
        assertEquals("Books, pens, et cetera", normalizer.apply("Books, pens, etc."))
        assertEquals("for example apples", normalizer.apply("e.g. apples"))
        assertEquals("that is fruit", normalizer.apply("i.e. fruit"))
        assertEquals("and others agree", normalizer.apply("et al. agree"))
    }

    @Test
    fun expandsOrganisationsAndMonths() {
        assertEquals("The Company was late.", normalizer.apply("The Co. was late."))
        assertEquals("January 1 and Monday", normalizer.apply("Jan. 1 and Mon."))
    }

    @Test
    fun expandsSaintByContext() {
        assertEquals("Saint John", normalizer.apply("St. John"))
        assertEquals("Saint Patrick's Day", normalizer.apply("St. Patrick's Day"))
        assertEquals("Main Street", normalizer.apply("Main St."))
        assertEquals(
            "123 Main Street and 4th Ave.",
            normalizer.apply("123 Main St. and 4th Ave."),
        )
    }

    @Test
    fun matchesCaseInsensitively() {
        assertEquals("Doctor WHO", normalizer.apply("DR. WHO"))
        assertEquals("Mister SMITH", normalizer.apply("MR. SMITH"))
    }

    @Test
    fun doesNotMatchWithoutAPeriod() {
        assertEquals("drum and MS and Mt", normalizer.apply("drum and MS and Mt"))
    }
}
