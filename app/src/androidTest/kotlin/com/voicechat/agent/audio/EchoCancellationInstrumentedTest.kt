package com.voicechat.agent.audio

import android.Manifest
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device check for the echo canceller (M07 / R-0254).
 *
 * It attaches the real [PlatformEchoCanceller] to a real `AudioRecord` session
 * and asserts the platform actually enables it. This is the check that was
 * missing: a created `AcousticEchoCanceler` is disabled until it is enabled, so a
 * "created but not enabled" canceller looked like `FAILED` while nothing was
 * suppressing the assistant's own audio.
 *
 * Runs only with `:app:connectedDebugAndroidTest`; a device whose platform
 * reports no canceller is skipped (`Assume`), never faked.
 */
@RunWith(AndroidJUnit4::class)
class EchoCancellationInstrumentedTest {
    @get:Rule
    val grantMicrophone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thePlatformEchoCancellerEnablesForARealCaptureSession() {
        assumeTrue("the platform reports no echo canceller on this device", AcousticEchoCanceler.isAvailable())

        val engine = AndroidPcmRecorderFactory(context).create()
        val canceller = PlatformEchoCanceller()
        try {
            engine.start()
            val sessionId = engine.audioSessionId
            val echo = canceller.attach(sessionId)
            Log.i(TAG, "AEC session=$sessionId state=${echo.state} detail=${echo.detail}")

            assertTrue("the recorder must expose an audio session id", sessionId != null && sessionId != 0)
            assertEquals(
                "the canceller must be enabled for a real capture session (detail=${echo.detail})",
                EchoCancellationState.ENABLED,
                echo.state,
            )
        } finally {
            canceller.release()
            runCatching { engine.stop() }
            runCatching { engine.release() }
        }
    }

    private companion object {
        const val TAG = "EchoCancellation"
    }
}
