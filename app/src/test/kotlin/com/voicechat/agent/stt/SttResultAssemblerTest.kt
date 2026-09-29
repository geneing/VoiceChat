package com.voicechat.agent.stt

import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.Transcript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assembler is the pure, JVM-testable core of the ML Kit adapter: it turns
 * vendor responses into the M02 [SttEvent] contract, assigns revisions, merges
 * final segments, and makes empty and failed results explicit. The real engine
 * needs a device, so these tests exercise every branch without one and never
 * claim the engine is available.
 */
class SttResultAssemblerTest {
    private fun newAssembler(languageTag: String? = "en-US") = SttResultAssembler(languageTag)

    private fun List<SttEvent>.transcripts(): List<Transcript> = map { (it as SttEvent.Result).transcript }

    private fun List<SttEvent>.onlyFailure() = (single() as SttEvent.Failed).error

    @Test
    fun partialRevisionsComeBeforeTheSingleFinalTranscript() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Partial("hel")))
                addAll(assembler.onResponse(SttEngineResponse.Partial("hello")))
                addAll(assembler.onResponse(SttEngineResponse.Final("hello world")))
                addAll(assembler.finish())
            }

        assertEquals(listOf(false, false, false, true), events.transcripts().map { it.isFinal })
        assertEquals(listOf("hel", "hello", "hello world", "hello world"), events.transcripts().map { it.text })
        assertEquals(listOf(0, 1, 2, 3), events.transcripts().map { it.revision.value })
        assertTrue(assembler.isFinished)
    }

    @Test
    fun aLaterHypothesisCorrectsAnEarlierOneAndOnlyTheFinalCommits() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Partial("ice scream")))
                addAll(assembler.onResponse(SttEngineResponse.Partial("ice cream")))
                addAll(assembler.finish())
            }

        val transcripts = events.transcripts()
        assertEquals(listOf("ice scream", "ice cream", "ice cream"), transcripts.map { it.text })
        assertEquals(listOf(false, false, true), transcripts.map { it.isFinal })
        // Revisions are monotonic so a consumer can drop the stale guess.
        assertEquals(listOf(0, 1, 2), transcripts.map { it.revision.value })
    }

    @Test
    fun finalSegmentsAreMergedIntoOneFinalTranscript() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Final("hello")))
                addAll(assembler.onResponse(SttEngineResponse.Final("world")))
                addAll(assembler.onResponse(SttEngineResponse.Completed))
            }

        val transcripts = events.transcripts()
        assertEquals(listOf("hello", "hello world", "hello world"), transcripts.map { it.text })
        assertEquals(1, transcripts.count { it.isFinal })
    }

    @Test
    fun emptyInputYieldsOneExplicitEmptyFinalRatherThanSilence() {
        val assembler = newAssembler()

        val events = assembler.finish()

        val final = events.single().let { it as SttEvent.Result }.transcript
        assertTrue(final.isFinal)
        assertEquals("", final.text)
    }

    @Test
    fun completedWithoutAnyFinalStillProducesOneEmptyFinal() {
        val assembler = newAssembler()

        val events = assembler.onResponse(SttEngineResponse.Completed)

        assertEquals(1, events.size)
        assertEquals("", (events.single() as SttEvent.Result).transcript.text)
    }

    @Test
    fun blankPartialsAreIgnoredAndDoNotAdvanceRevisions() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Partial("   ")))
                addAll(assembler.onResponse(SttEngineResponse.Partial("")))
                addAll(assembler.finish())
            }

        val final = (events.single() as SttEvent.Result).transcript
        assertEquals("", final.text)
        assertEquals(0, final.revision.value)
    }

    @Test
    fun unavailableFailureIsATypedTerminalError() {
        val assembler = newAssembler()

        val events = assembler.onResponse(SttEngineResponse.Failure(SttFailureKind.UNAVAILABLE, "no model"))

        val error = events.onlyFailure()
        assertEquals(ErrorCode.STT_UNAVAILABLE, error.code)
        assertEquals("no model", error.detail)
        assertTrue(assembler.isFinished)
    }

    @Test
    fun notReadyFailureIsRetryableAndMappedToModelNotReady() {
        val assembler = newAssembler()

        val error = assembler.onResponse(SttEngineResponse.Failure(SttFailureKind.NOT_READY)).onlyFailure()

        assertEquals(ErrorCode.STT_MODEL_NOT_READY, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun recognitionFailureIsMappedToRecognitionFailed() {
        val error =
            newAssembler()
                .onResponse(SttEngineResponse.Failure(SttFailureKind.RECOGNITION_FAILED, "decode error"))
                .onlyFailure()

        assertEquals(ErrorCode.STT_RECOGNITION_FAILED, error.code)
        assertEquals("decode error", error.detail)
    }

    @Test
    fun noEventsAreEmittedAfterTheTerminalResultOrFailure() {
        val afterFinal = newAssembler()
        afterFinal.onResponse(SttEngineResponse.Partial("done"))
        assertEquals(1, afterFinal.finish().size)
        assertTrue(afterFinal.onResponse(SttEngineResponse.Partial("more")).isEmpty())
        assertTrue(afterFinal.finish().isEmpty())

        val afterFailure = newAssembler()
        afterFailure.onResponse(SttEngineResponse.Failure(SttFailureKind.RECOGNITION_FAILED))
        assertTrue(afterFailure.onResponse(SttEngineResponse.Partial("more")).isEmpty())
        assertTrue(afterFailure.onResponse(SttEngineResponse.Completed).isEmpty())
        assertTrue(afterFailure.finish().isEmpty())
    }

    @Test
    fun aSessionThatOnlyProducedPartialsStillFinalizesTheLastHypothesis() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Partial("first guess")))
                addAll(assembler.onResponse(SttEngineResponse.Partial("final guess")))
                addAll(assembler.finish())
            }

        val transcripts = events.transcripts()
        assertEquals(listOf("first guess", "final guess", "final guess"), transcripts.map { it.text })
        assertEquals(listOf(false, false, true), transcripts.map { it.isFinal })
    }

    @Test
    fun lowConfidenceIsPreservedNotDropped() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Final("maybe", confidence = 0.2f)))
                addAll(assembler.finish())
            }

        val confidence = events.transcripts().last().confidence
        assertEquals(0.2f, confidence)
        assertTrue(events.transcripts().last().isLowConfidence())
    }

    @Test
    fun highOrMissingConfidenceIsNotFlaggedLow() {
        assertFalse(Transcript.final("clear").isLowConfidence())
        assertTrue((Transcript.final("clear").confidence) == null)

        val assembler = newAssembler()
        assembler.onResponse(SttEngineResponse.Final("clear", confidence = 0.95f))
        assertFalse(
            assembler
                .finish()
                .transcripts()
                .last()
                .isLowConfidence(),
        )
    }

    @Test
    fun numbersNamesNegationAndDisfluencyArePreservedVerbatim() {
        val spoken =
            listOf(
                "call me at 5:30 pm on 2026-09-28",
                "schedule with Priya Nair and O'Brien",
                "don't send the report to anyone",
                "um, I mean, uh, send it",
            )

        spoken.forEach { text ->
            val assembler = newAssembler()
            assembler.onResponse(SttEngineResponse.Partial(text.take(4)))
            assembler.onResponse(SttEngineResponse.Final(text))

            val final = assembler.finish().transcripts().last()
            assertTrue(final.isFinal)
            assertEquals(text, final.text)
        }
    }

    @Test
    fun languageMetadataIsStampedOnEveryTranscriptWhenAvailable() {
        val assembler = SttResultAssembler(languageTag = "de-DE")

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Partial("hallo")))
                addAll(assembler.finish())
            }

        assertTrue(events.transcripts().all { it.languageTag == "de-DE" })
    }

    @Test
    fun aMissingLanguageTagStaysNull() {
        val assembler = SttResultAssembler(languageTag = null)

        val final = assembler.finish().transcripts().single()

        assertNull(final.languageTag)
    }

    // --- M08 finalization fix: stop-induced termination -----------------------

    @Test
    fun aStopInducedStoppedResponseDoesNotMaskASuccessfulTranscript() {
        // Mirrors the fixed adapter order observed on device: partials and a
        // final segment, then a stop-induced ErrorResponse(errorCode = 0) mapped
        // to Stopped immediately before the terminal CompletedResponse.
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Partial("hello wor")))
                addAll(assembler.onResponse(SttEngineResponse.Final("hello world")))
                assertTrue("input end must ask the adapter to stop", assembler.onInputEnded())
                addAll(assembler.onResponse(SttEngineResponse.Stopped))
                addAll(assembler.onResponse(SttEngineResponse.Completed))
                addAll(assembler.finish())
            }

        val transcripts = events.transcripts()
        assertEquals("hello world", transcripts.last().text)
        assertTrue(transcripts.last().isFinal)
        assertEquals(1, transcripts.count { it.isFinal })
        assertTrue("a stop-induced response must never produce a failure", events.none { it is SttEvent.Failed })
    }

    @Test
    fun stoppedAfterInputEndIsIgnoredEvenWithoutATerminalCompleted() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Final("keep me")))
                assembler.onInputEnded()
                addAll(assembler.onResponse(SttEngineResponse.Stopped))
                // The engine ended its flow without Completed; the adapter's
                // post-collect finish() still finalizes the transcript.
                addAll(assembler.finish())
            }

        assertEquals("keep me", events.transcripts().last().text)
        assertTrue(events.none { it is SttEvent.Failed })
    }

    @Test
    fun stoppedIsIgnoredWhenTextWasAlreadyAssembledEvenWithoutAnExplicitStop() {
        val assembler = newAssembler()

        val events =
            buildList {
                addAll(assembler.onResponse(SttEngineResponse.Partial("guess")))
                addAll(assembler.onResponse(SttEngineResponse.Stopped))
                addAll(assembler.finish())
            }

        assertEquals("guess", events.transcripts().last().text)
        assertTrue(events.none { it is SttEvent.Failed })
    }

    @Test
    fun stoppedWithNoTextAndNoStopRequestIsStillReportedAsAFailure() {
        val assembler = newAssembler()

        val error = assembler.onResponse(SttEngineResponse.Stopped).onlyFailure()

        assertEquals(ErrorCode.STT_RECOGNITION_FAILED, error.code)
        assertTrue(assembler.isFinished)
    }

    @Test
    fun onInputEndedReturnsTrueOnceAndFalseAfterTheSessionEnds() {
        val assembler = newAssembler()
        assembler.onResponse(SttEngineResponse.Partial("hi"))

        assertTrue(assembler.onInputEnded())
        assertFalse("a second input end must not stop twice", assembler.onInputEnded())
        assembler.finish()
        assertFalse("an ended session must not request a stop", assembler.onInputEnded())
    }

    @Test
    fun stoppedAfterAFailureEmitsNothing() {
        val assembler = newAssembler()
        assembler.onResponse(SttEngineResponse.Failure(SttFailureKind.RECOGNITION_FAILED))

        assertTrue(assembler.onResponse(SttEngineResponse.Stopped).isEmpty())
    }
}
