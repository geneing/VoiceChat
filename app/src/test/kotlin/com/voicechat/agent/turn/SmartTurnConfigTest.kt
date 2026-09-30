package com.voicechat.agent.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * M10 config validation: the window length, threshold bounds, and the pinned
 * graph's 16 kHz assumption are all validated in the constructor, so an invalid
 * decision parameter cannot silently reach the model.
 */
class SmartTurnConfigTest {
    @Test
    fun theDefaultsMatchThePinnedGraphContract() {
        val config = SmartTurnConfig.default()

        assertEquals(16_000, config.sampleRateHz)
        assertEquals(128_000, config.windowSamples)
        assertEquals(0.5f, config.completionThreshold, 0f)
    }

    @Test
    fun aSampleRateOtherThanSixteenKilohertzIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SmartTurnConfig(sampleRateHz = 8_000)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SmartTurnConfig(sampleRateHz = 44_100)
        }
    }

    @Test
    fun aNonPositiveWindowLengthIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { SmartTurnConfig(windowSamples = 0) }
        assertThrows(IllegalArgumentException::class.java) { SmartTurnConfig(windowSamples = -1) }
    }

    @Test
    fun aThresholdOutsideTheOpenUnitIntervalIsRejected() {
        listOf(0f, 1f, -0.01f, 1.01f, Float.NaN, Float.POSITIVE_INFINITY).forEach { threshold ->
            assertThrows("threshold $threshold must be rejected", IllegalArgumentException::class.java) {
                SmartTurnConfig(completionThreshold = threshold)
            }
        }
    }

    @Test
    fun aConfiguredWindowAndThresholdInsideTheBoundsAreAccepted() {
        val config = SmartTurnConfig(windowSamples = 64_000, completionThreshold = 0.7f)

        assertEquals(64_000, config.windowSamples)
        assertEquals(0.7f, config.completionThreshold, 0f)
    }
}
