package com.voicechat.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInfoTest {
    @Test
    fun minSupportedApiMatchesThePinnedMinSdkDecision() {
        assertEquals(31, AppInfo.MIN_SUPPORTED_API)
    }

    @Test
    fun onDeviceSpeechIsRejectedBelowMinSdk() {
        assertFalse(AppInfo.supportsOnDeviceSpeech(AppInfo.MIN_SUPPORTED_API - 1))
    }

    @Test
    fun onDeviceSpeechIsAcceptedAtMinSdk() {
        assertTrue(AppInfo.supportsOnDeviceSpeech(AppInfo.MIN_SUPPORTED_API))
    }
}
