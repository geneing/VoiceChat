package com.voicechat.agent.eval

import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.vad.VadConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M25 endpoint sweep: it runs the real bounded policy over a labeled timeline and
 * classifies the endpoint as accurate, a false commit, or a false hold relative
 * to the labeled true end.
 *
 * Frame timeline is 20 ms at 16 kHz: 20 speech frames (400 ms) then silence. The
 * default VAD (3 onset frames, 14 pause frames) ends at 680 ms, so a zero silence
 * cap gives a +280 ms delay from the true end and the full 2000 ms cap gives
 * +2260 ms.
 */
class EndpointSweepTest {
    private val speechFrame = AudioFrame(AudioFormat.MONO_16_KHZ, ShortArray(320) { 8_000 })
    private val silenceFrame = AudioFrame(AudioFormat.MONO_16_KHZ, ShortArray(320) { 0 })

    private fun timeline(
        speechFrames: Int = 20,
        silenceFrames: Int = 200,
    ): List<AudioFrame> = List(speechFrames) { speechFrame } + List(silenceFrames) { silenceFrame }

    @Test
    fun theEndpointOffsetIsTheBoundedPolicyResult() =
        runTest {
            val case = LabeledEndpointCase("short-pause", timeline(), trueEndOffsetMillis = 400)

            assertEquals(680L, EndpointSweep().endpointOffsetMillis(case, VadConfig(maxSilenceMillis = 0)))
        }

    @Test
    fun aZeroSilenceCapEndsNearTheTrueEndAndIsAccurate() =
        runTest {
            val case = LabeledEndpointCase("short-pause", timeline(), trueEndOffsetMillis = 400)

            val accuracy = EndpointSweep().evaluate(listOf(case), VadConfig(maxSilenceMillis = 0))

            assertEquals(1, accuracy.accurate)
            assertEquals(0, accuracy.falseCommits)
            assertEquals(0, accuracy.falseHolds)
            assertEquals(280.0, accuracy.meanSignedDelayMillis, 1e-9)
        }

    @Test
    fun aLongSilenceCapIsAFalseHoldUnderATightWindow() =
        runTest {
            val case = LabeledEndpointCase("short-pause", timeline(), trueEndOffsetMillis = 400)

            val accuracy =
                EndpointSweep(falseHoldToleranceMillis = 100)
                    .evaluate(listOf(case), VadConfig(maxSilenceMillis = 2_000))

            assertEquals(0, accuracy.accurate)
            assertEquals(1, accuracy.falseHolds)
            assertEquals(1.0, accuracy.falseHoldRate, 1e-9)
        }

    @Test
    fun anEndpointLongBeforeTheLabeledEndIsAFalseCommit() =
        runTest {
            val case = LabeledEndpointCase("long-turn", timeline(), trueEndOffsetMillis = 2_000)

            val accuracy = EndpointSweep().evaluate(listOf(case), VadConfig(maxSilenceMillis = 0))

            assertEquals(1, accuracy.falseCommits)
            assertEquals(1.0, accuracy.falseCommitRate, 1e-9)
        }

    @Test
    fun theSweepKeepsConfigurationOrderAndRendersMarkdown() =
        runTest {
            val case = LabeledEndpointCase("short-pause", timeline(), trueEndOffsetMillis = 400)
            val configs = listOf(VadConfig(maxSilenceMillis = 0), VadConfig(maxSilenceMillis = 2_000))

            val rows = EndpointSweep().sweep(listOf(case), configs)

            assertEquals(configs, rows.map { it.config })
            assertEquals("accurate", if (rows[0].accuracy.accurate == 1) "accurate" else "other")
            assertTrue(rows.toMarkdown().contains("| onsetRms |"))
        }
}
