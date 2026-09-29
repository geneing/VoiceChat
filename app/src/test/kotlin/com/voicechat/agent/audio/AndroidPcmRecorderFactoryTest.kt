package com.voicechat.agent.audio

import android.Manifest
import android.app.Application
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Robolectric coverage for the device boundary: even if a caller reached the
 * factory without the capture loop's check, opening `AudioRecord` must fail with
 * a typed permission error rather than crashing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidPcmRecorderFactoryTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun createFailsWithPermissionDeniedWhenPermissionIsMissing() {
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)
        val factory = AndroidPcmRecorderFactory(application)

        val thrown = assertThrows(VoiceAgentException::class.java) { factory.create() }

        assertEquals(ErrorCode.AUDIO_PERMISSION_DENIED, thrown.error.code)
    }
}
