package com.voicechat.agent.audio

import android.Manifest
import android.app.Application
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Android-level permission checks under Robolectric: granting and revoking
 * `RECORD_AUDIO` changes what the capture path sees without a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidMicrophonePermissionTest {
    private lateinit var application: Application

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
    }

    @Test
    fun reportsGrantedWhenThePermissionIsHeld() {
        shadowOf(application).grantPermissions(Manifest.permission.RECORD_AUDIO)

        assertTrue(AndroidMicrophonePermission(application).isGranted())
    }

    @Test
    fun reportsDeniedWhenThePermissionIsRevoked() {
        shadowOf(application).denyPermissions(Manifest.permission.RECORD_AUDIO)

        assertFalse(AndroidMicrophonePermission(application).isGranted())
    }
}
