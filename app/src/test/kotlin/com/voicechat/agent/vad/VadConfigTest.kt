package com.voicechat.agent.vad

import com.voicechat.agent.audio.AudioRouteType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [VadConfig] is validated before any audio is processed, so a bad threshold or
 * an invalid silence cap can never silently disable endpointing.
 */
class VadConfigTest {
    @Test
    fun defaultsAreValidAndNonNegative() {
        val config = VadConfig.default()

        assertTrue(config.onsetRmsThreshold > 0f)
        assertTrue(config.hangoverRmsThreshold > 0f)
        assertTrue(config.hangoverRmsThreshold <= config.onsetRmsThreshold)
        assertTrue(config.maxZeroCrossingRate in 0f..1f)
        assertTrue(config.onsetFrames >= 1)
        assertTrue(config.pauseFrames >= 1)
        assertTrue(config.maxSilenceMillis >= 0L)
        assertTrue(config.semanticWindowMillis >= 0L)
    }

    @Test
    fun routeAwareTuningIsValidForEveryRouteKind() {
        AudioRouteType.entries.forEach { route ->
            val config = VadConfig.forRoute(route)
            assertTrue("invalid config for $route", config.maxSilenceMillis >= 0L)
            assertTrue("invalid config for $route", config.onsetRmsThreshold > 0f)
        }
    }

    @Test
    fun bluetoothAndWiredRoutesUseHigherThresholdsThanTheBuiltInMic() {
        val builtIn = VadConfig.forRoute(AudioRouteType.BUILTIN_MIC)
        val bluetooth = VadConfig.forRoute(AudioRouteType.BLUETOOTH_SCO)
        val wired = VadConfig.forRoute(AudioRouteType.WIRED_HEADSET)

        assertTrue(bluetooth.onsetRmsThreshold > builtIn.onsetRmsThreshold)
        assertTrue(bluetooth.hangoverRmsThreshold > builtIn.hangoverRmsThreshold)
        assertTrue(wired.onsetRmsThreshold > builtIn.onsetRmsThreshold)
        assertEquals(builtIn, VadConfig.forRoute(AudioRouteType.UNKNOWN))
    }

    @Test
    fun nonPositiveOnsetThresholdIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(onsetRmsThreshold = 0f) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(onsetRmsThreshold = -0.1f) }
    }

    @Test
    fun nonFiniteThresholdsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(onsetRmsThreshold = Float.NaN) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(hangoverRmsThreshold = Float.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) {
            VadConfig(maxZeroCrossingRate = Float.NaN)
        }
    }

    @Test
    fun hangoverAboveOnsetIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            VadConfig(onsetRmsThreshold = 0.01f, hangoverRmsThreshold = 0.02f)
        }
    }

    @Test
    fun zeroCrossingRateOutsideZeroToOneIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(maxZeroCrossingRate = -0.1f) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(maxZeroCrossingRate = 1.1f) }
    }

    @Test
    fun frameWindowsMustBePositive() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(onsetFrames = 0) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(pauseFrames = 0) }
    }

    @Test
    fun negativeSilenceCapAndSemanticWindowAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { VadConfig(maxSilenceMillis = -1L) }
        assertThrows(IllegalArgumentException::class.java) { VadConfig(semanticWindowMillis = -1L) }
    }
}
