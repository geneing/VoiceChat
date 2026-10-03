package com.voicechat.agent.stt.parakeet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behavioral tests for [ParakeetStreamingDecoder] driven by a deterministic
 * scripted [ParakeetEouRuntime]. No ONNX, no audio fixtures: the fake returns a
 * fixed token for each joint call and one encoded frame per frame, so every
 * assertion pins decode-loop semantics rather than model accuracy.
 */
class ParakeetStreamingDecoderTest {
    private val config = ParakeetEouConfig.pinned()

    private val vocab =
        ParakeetVocabulary.of(
            mapOf(
                1 to "\u2581a",
                2 to "\u2581b",
                3 to "\u2581hello",
                4 to "\u2581world",
                5 to "\u2581again",
                1024 to "<EOU>",
                1025 to "<EOB>",
            ),
        )

    private val blank = 1026
    private val eou = 1024
    private val eob = 1025

    private fun chunk(): FloatArray = FloatArray(config.chunkSamples)

    /**
     * A follow-up feed of exactly [ParakeetEouConfig.shiftSamples] samples. After
     * an initial full [chunk] the buffer holds 5,120 samples, so this fills exactly
     * one more window per accept — unlike another full chunk, which would leave
     * enough overlap to decode two windows.
     */
    private fun nextChunk(): FloatArray = FloatArray(config.shiftSamples)

    private fun decoder(
        runtime: ScriptedRuntime,
        debounceMillis: Int = 0,
    ): ParakeetStreamingDecoder = ParakeetStreamingDecoder(vocab, config, runtime, debounceMillis)

    @Test
    fun encodeIsNotCalledUntilAFullChunkIsBuffered() {
        val runtime = ScriptedRuntime()
        val decoder = decoder(runtime)

        decoder.accept(FloatArray(config.chunkSamples - 1))
        assertEquals(0, runtime.encodeCalls)

        decoder.accept(FloatArray(1))
        assertEquals(1, runtime.encodeCalls)
    }

    @Test
    fun eachChunkAdvancesByShiftSamples() {
        val runtime = ScriptedRuntime()
        val decoder = decoder(runtime)

        decoder.accept(chunk())
        assertEquals(1, runtime.encodeCalls)

        // If the advance were the full chunk (10,240) another 5,120 samples would
        // not be enough; the host advances by 5,120, so this fills a second window.
        decoder.accept(FloatArray(config.shiftSamples))
        assertEquals(2, runtime.encodeCalls)

        decoder.accept(FloatArray(0))
        assertEquals(2, runtime.encodeCalls)
    }

    @Test
    fun blankAdvancesFramesWithoutEmittingText() {
        val runtime = ScriptedRuntime()
        // frame 0: token 1 then blank; frame 1: token 2 then blank; frames 2-3 blank.
        runtime.jointQueue.addAll(listOf(1, blank, 2, blank, blank, blank))
        val decoder = decoder(runtime)

        val result = decoder.accept(chunk())

        assertEquals("a b", result.text)
        assertFalse(result.isFinal)
        assertEquals(4, runtime.decodeFrameCount)
    }

    @Test
    fun atMostTenNonBlankSymbolsAreEmittedPerFrame() {
        val runtime = ScriptedRuntime()
        // Eleven consecutive symbols: the 11th must be produced on the *next*
        // encoder frame because a frame emits at most ten.
        for (token in 1..11) runtime.jointQueue.add(token)
        val decoder = decoder(runtime)

        decoder.accept(chunk())

        val ordinalOfEleventh = runtime.jointCalls[10].first
        assertEquals(1, runtime.jointCalls[9].first)
        assertEquals(2, ordinalOfEleventh)
    }

    @Test
    fun scriptedMultiWordStreamProducesTheExpectedString() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(3, blank, 4, blank, blank, blank))
        val decoder = decoder(runtime)

        val first = decoder.accept(chunk())
        assertEquals("hello world", first.text)
        assertFalse(first.isFinal)

        runtime.jointQueue.addAll(listOf(5, blank, blank, blank, blank, blank))
        val second = decoder.accept(nextChunk())

        // Partial text is scoped to the current utterance, so the new partial
        // accumulates across chunks rather than resetting.
        assertEquals("hello world again", second.text)
        assertFalse(second.isFinal)
    }

    @Test
    fun eouCommitsTheSegmentAndStartsANewOne() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(1, blank, blank, blank, blank, blank))
        val decoder = decoder(runtime, debounceMillis = 0)

        decoder.accept(chunk())

        // A token-bearing chunk resets the debounce window; the next chunk is pure
        // EOU, which commits the accumulated text on this accept.
        runtime.jointQueue.addAll(listOf(eou, blank, blank, blank))
        val committed = decoder.accept(nextChunk())

        assertEquals("a", committed.text)
        assertTrue(committed.isFinal)
        assertTrue(committed.eouDetected)
        assertEquals(0, committed.segmentIndex)

        runtime.jointQueue.addAll(listOf(2, blank, blank, blank, blank, blank))
        val next = decoder.accept(nextChunk())

        assertEquals("b", next.text)
        assertFalse(next.isFinal)
        assertEquals(1, next.segmentIndex)
    }

    @Test
    fun eouIsDebouncedUntilItIsSustainedForTheConfiguredWindow() {
        val runtime = ScriptedRuntime()
        val decoder = decoder(runtime, debounceMillis = ParakeetStreamingDecoder.DEFAULT_EOU_DEBOUNCE_MILLIS)

        // Each accept() runs one 640 ms window: config.chunkSamples (10,240) is the
        // debounce clock's step, matching the host's `processed += chunk.size`. The
        // first pure-EOU window only starts the clock, so 1,280 ms is reached on the
        // third window.
        runtime.jointQueue.addAll(listOf(eou, blank, blank, blank))
        val first = decoder.accept(FloatArray(config.chunkSamples))
        assertFalse(first.isFinal)

        runtime.jointQueue.addAll(listOf(eou, blank, blank, blank))
        val second = decoder.accept(FloatArray(config.shiftSamples))
        assertFalse("640 ms of EOU is below the 1,280 ms debounce", second.isFinal)

        runtime.jointQueue.addAll(listOf(eou, blank, blank, blank))
        val third = decoder.accept(FloatArray(config.shiftSamples))
        assertTrue(third.isFinal)
        assertTrue(third.eouDetected)
    }

    @Test
    fun eobIsConsumedButNeverEmitted() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(eob, 1, blank, blank, blank, blank))
        val decoder = decoder(runtime)

        val result = decoder.accept(chunk())

        assertEquals("a", result.text)
        assertFalse(result.text.contains("EOB"))
    }

    @Test
    fun forceEndOfUtteranceCommitsTextAndKeepsTheRuntimeAlive() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(1, blank, blank, blank, blank, blank))
        val decoder = decoder(runtime)

        decoder.accept(chunk())
        val forced = decoder.forceEndOfUtterance()

        assertEquals("a", forced.text)
        assertTrue(forced.isFinal)
        assertFalse(forced.eouDetected)
        assertEquals(0, forced.segmentIndex)
        // The runtime must not be re-primed: the session keeps streaming.
        assertEquals(1, runtime.primeCount)

        runtime.jointQueue.addAll(listOf(2, blank, blank, blank, blank, blank))
        val next = decoder.accept(nextChunk())

        assertEquals("b", next.text)
        assertEquals(1, next.segmentIndex)
        // Caches were handed off (0 -> 1 -> 2), never reset to zero by the force.
        assertEquals(listOf(0L, 1L), runtime.cacheLengthsSeen)
    }

    @Test
    fun finalizeCommitsExistingTokensWithoutDecodingPartialTailAndIsIdempotent() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(1, blank, blank, blank, blank, blank))
        val decoder = decoder(runtime)

        decoder.accept(FloatArray(config.chunkSamples / 2))
        assertEquals(0, runtime.encodeCalls)

        val first = decoder.finalize()
        // The host drops a sub-window tail instead of making a padded decode.
        assertEquals(0, runtime.encodeCalls)
        assertEquals("", first.text)
        assertTrue(first.isFinal)

        val second = decoder.finalize()
        assertEquals(0, runtime.encodeCalls)
        assertEquals(first, second)
    }

    @Test
    fun finalizeDoesNotEncodePartialOverlapAfterWholeWindows() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(1, blank, blank, blank, blank, blank))
        val decoder = decoder(runtime)
        val shiftWindows = 4

        // A feed of N shifts contains N-1 host-eligible full windows and a
        // remaining half-window. finalize must commit only those decoded tokens.
        decoder.accept(FloatArray(shiftWindows * config.shiftSamples))
        assertEquals(shiftWindows - 1, runtime.encodeCalls)

        val result = decoder.finalize()

        assertEquals(shiftWindows - 1, runtime.encodeCalls)
        assertEquals("a", result.text)
        assertTrue(result.isFinal)
    }

    @Test
    fun resetClearsTheSegmentAndReprimesTheRuntime() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(1, blank, blank, blank, blank, blank))
        val decoder = decoder(runtime)

        decoder.accept(chunk())
        assertFalse(decoder.accept(FloatArray(0)).text.isEmpty())

        decoder.reset()
        assertEquals(2, runtime.primeCount)

        val result = decoder.accept(FloatArray(0))
        assertTrue(result.text.isEmpty())
        assertEquals(0, result.segmentIndex)
        assertFalse(result.isFinal)
    }

    @Test
    fun confidenceStaysWithinUnitInterval() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(1, blank, 2, blank, blank, blank))
        val decoder = decoder(runtime)

        val result = decoder.accept(chunk())

        assertTrue(result.confidence in 0f..1f)
        assertTrue(result.confidence > 0f)
    }

    @Test
    fun emptySegmentHasNeutralConfidence() {
        val runtime = ScriptedRuntime()
        runtime.jointQueue.addAll(listOf(blank, blank, blank, blank))
        val decoder = decoder(runtime)

        val result = decoder.accept(chunk())

        assertEquals(1f, result.confidence, 0f)
    }

    /**
     * A deterministic [ParakeetEouRuntime]: [encode] returns one frame per frame
     * index (values filled with the 1-based frame ordinal so the fake can report
     * which frame a joint call belongs to), and [joint] returns a fixed token for
     * each call, defaulting to blank once the script is exhausted.
     */
    private class ScriptedRuntime(
        override val hidden: Int = 512,
        override val decoderHidden: Int = 640,
        override val logits: Int = 1027,
    ) : ParakeetEouRuntime {
        val jointQueue = ArrayDeque<Int>()

        /** `(frame ordinal, token)` for every joint call, in order. */
        val jointCalls = mutableListOf<Pair<Int, Int>>()

        /** Cache lengths handed to [encode], in call order. */
        val cacheLengthsSeen = mutableListOf<Long>()

        var encodeCalls = 0
        var decodeFrameCount = 0

        /** Number of blank-primed [decode] calls; [reset] performs exactly one. */
        var primeCount = 0

        override fun encode(
            mel: FloatArray,
            melFrames: Int,
            state: EncoderState,
        ): EncoderOutput {
            encodeCalls++
            cacheLengthsSeen += state.cacheLastChannelLen
            val frames = 4
            val values = FloatArray(frames * hidden)
            for (frame in 0 until frames) {
                for (j in 0 until hidden) values[frame * hidden + j] = (frame + 1).toFloat()
            }
            val next = state.copy(cacheLastChannelLen = state.cacheLastChannelLen + 1)
            return EncoderOutput(values, frames, next)
        }

        override fun decode(
            token: Int,
            h: FloatArray,
            c: FloatArray,
        ): DecoderOutput {
            if (token == 1026 && h.all { it == 0f } && c.all { it == 0f }) primeCount++
            return DecoderOutput(FloatArray(decoderHidden) { token.toFloat() }, h.copyOf(), c.copyOf())
        }

        override fun joint(
            encoderFrame: FloatArray,
            decoderOutput: FloatArray,
        ): FloatArray {
            val ordinal = encoderFrame[0].toInt()
            val token = if (jointQueue.isEmpty()) 1026 else jointQueue.removeFirst()
            jointCalls += ordinal to token
            if (token == 1026) decodeFrameCount++
            val logits = FloatArray(1027)
            logits[token] = 10f
            return logits
        }

        override fun close() = Unit
    }
}
