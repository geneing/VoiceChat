package com.voicechat.agent.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device checks for the M07 microphone capture path (Tests.md).
 *
 * These execute only with `:app:connectedDebugAndroidTest` on a physical device;
 * they are compiled on the host but never run there. They use the real permission
 * and the real `AudioRecord` — availability is never faked and no human speaker
 * is required (silence still produces frames).
 *
 * [GrantPermissionRule] grants `RECORD_AUDIO` before each test. The denial test
 * injects a denied `MicrophonePermission` instead of calling
 * `revokeRuntimePermission`, which on Android 17 kills the app process mid-test.
 * Genuinely manual checks (route changes, level calibration, `dumpsys` leak
 * inspection) stay in Tests.md and are not claimed by these tests.
 */
@RunWith(AndroidJUnit4::class)
class MicrophoneCaptureInstrumentedTest {
    @get:Rule
    val grantMicrophone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val packageName get() = context.packageName

    @Test
    fun captureRefusesToStartWithoutTheMicrophonePermission() {
        // A real revokeRuntimePermission kills the app process on Android 17
        // ("permissions revoked"), so the assertion could never run. The capture
        // path reads the permission through the injectable `MicrophonePermission`
        // seam, so a denied permission is exercised directly without touching the
        // real grant. `captureStartsStopsAndRestartsWithThePermissionGranted`
        // covers the genuine granted state against the real platform permission.
        val capture =
            MicrophoneAudioCapture.create(
                context,
                permission = MicrophonePermission { false },
            )
        try {
            val error = runCatching { runBlocking { capture.frames().first() } }.exceptionOrNull()
            assertTrue("expected a typed capture failure, got $error", error is VoiceAgentException)
            assertEquals(ErrorCode.AUDIO_PERMISSION_DENIED, (error as VoiceAgentException).error.code)
        } finally {
            runBlocking { capture.close() }
        }
    }

    @Test
    fun theRealPlatformPermissionReportsGrantedUnderTheGrantRule() {
        // Proves the real permission state is observed (the GrantPermissionRule
        // grants RECORD_AUDIO before the test), not just the injected fake above.
        assertTrue(
            "RECORD_AUDIO should be granted by the GrantPermissionRule",
            instrumentation.targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED,
        )
    }

    @Test
    fun aClosedCaptureInputRefusesToStartAgain() {
        val capture = MicrophoneAudioCapture.create(context)
        runBlocking { capture.close() }

        val error = runCatching { runBlocking { capture.frames().first() } }.exceptionOrNull()

        assertTrue("expected a typed capture failure, got $error", error is VoiceAgentException)
        assertEquals(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, (error as VoiceAgentException).error.code)
    }

    @Test
    fun captureStartsStopsAndRestartsWithThePermissionGranted() {
        val first = MicrophoneAudioCapture.create(context)
        try {
            val frames =
                runBlocking {
                    withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) {
                        first.frames().take(FRAMES_PER_SESSION).toList()
                    }
                }
            assertNotNull("capture produced no frames within the timeout", frames)
            assertTrue("capture produced no frames", frames!!.isNotEmpty())
            assertTrue("capture must emit 16 kHz mono frames", frames.all { it.format == AudioFormat.MONO_16_KHZ })
        } finally {
            runBlocking { first.close() }
        }

        // Starting a fresh session proves the first released its recorder and focus.
        val second = MicrophoneAudioCapture.create(context)
        try {
            val secondFrames =
                runBlocking {
                    withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) { second.frames().take(1).toList() }
                }
            assertNotNull("a second capture session could not start after the first stopped", secondFrames)
        } finally {
            runBlocking { second.close() }
        }
    }

    private companion object {
        const val CAPTURE_TIMEOUT_MILLIS = 10_000L
        const val FRAMES_PER_SESSION = 3
    }
}
