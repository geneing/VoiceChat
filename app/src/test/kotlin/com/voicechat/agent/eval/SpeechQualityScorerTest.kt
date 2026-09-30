package com.voicechat.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M25 speech-quality scoring: the aggregate is exact for the normalized strings,
 * and normalization is stable so a WER recorded today is comparable later.
 */
class SpeechQualityScorerTest {
    @Test
    fun anExactMatchHasNoErrors() {
        val result = SpeechQualityScorer.score("call mom tomorrow", "call mom tomorrow")

        assertEquals(0, result.wordErrors)
        assertEquals(0, result.characterErrors)
        assertEquals(0.0, result.wer, 0.0)
        assertEquals(0.0, result.cer, 0.0)
        assertTrue(result.categories.isEmpty())
    }

    @Test
    fun normalizationIgnoresCaseAndPunctuationOnly() {
        val result = SpeechQualityScorer.score("Call Mom, tomorrow!", "call mom tomorrow")

        assertEquals(0, result.wordErrors)
        assertEquals(0.0, result.wer, 0.0)
    }

    @Test
    fun aSubstitutionCountsOnceOverTheReferenceLength() {
        val result = SpeechQualityScorer.score("book a flight to Rome", "book a flight to roam")

        assertEquals(1, result.substitutions)
        assertEquals(5, result.referenceWordCount)
        assertEquals(0.2, result.wer, 1e-9)
    }

    @Test
    fun deletionsAndInsertionsAreCountedSeparately() {
        val result = SpeechQualityScorer.score("turn off the kitchen lights", "turn off kitchen lights please")

        assertEquals(1, result.deletions)
        assertEquals(1, result.insertions)
        assertEquals(0, result.substitutions)
        assertEquals(0.4, result.wer, 1e-9)
    }

    @Test
    fun numbersNegationsAndNamesAreTagged() {
        val result =
            SpeechQualityScorer.score(
                reference = "Book room 42 for Sarah, don't cancel it, okay?",
                hypothesis = "Book room for Sandra cancel it okay",
            )

        assertTrue("number mismatch must be tagged", SpeechErrorCategory.NUMBER in result.categories)
        assertTrue("negation mismatch must be tagged", SpeechErrorCategory.NEGATION in result.categories)
        assertTrue("proper noun mismatch must be tagged", SpeechErrorCategory.NAME in result.categories)
    }

    @Test
    fun anEmptyReferenceWithAnyHypothesisIsFullyWrong() {
        val result = SpeechQualityScorer.score("", "unexpected words")

        assertEquals(2, result.insertions)
        assertEquals(1.0, result.wer, 0.0)
    }

    @Test
    fun tokenizeStripsPunctuationAndKeepsIntraWordApostrophes() {
        assertEquals(listOf("don't", "stop"), SpeechQualityScorer.tokenize("Don't, stop!"))
    }
}
