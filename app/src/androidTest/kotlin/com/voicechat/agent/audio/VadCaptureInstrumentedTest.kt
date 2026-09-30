package com.voicechat.agent.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.voicechat.agent.contracts.VadEvent
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.vad.BoundedTurnEndpointPolicy
import com.voicechat.agent.vad.EnergyVoiceActivityDetector
import com.voicechat.agent.vad.TurnDetectionEvent
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke test for M09 (see Tests.md): the real M07 live capture path
 * feeds the real measured-audio detector and bounded endpoint policy.
 *
 * It executes only with `:app:connectedDebugAndroidTest` on a physical device;
 * it is compiled on the host but never run there. It uses the real permission
 * and real `AudioRecord`, does not fake device availability, and needs no human
 * speaker: a bounded window of ambient audio still drives the pipeline, and the
 * endpoint policy always emits at least one typed endpoint when that window
 * completes.
 *
 * This is deliberately a *runs and produces events without crashing* check, not
 * a quality measurement. Onset latency, false endpoints/holds, and the exact
 * silence cap are measured manually per Tests.md, never claimed from here.
 */
@RunWith(AndroidJUnit4::class)
class VadCaptureInstrumentedTest {
    @get:Rule
    val grantMicrophone: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test
    fun liveCaptureFeedsTheDetectorAndTheEndpointPolicyTerminates() {
        val capture = MicrophoneAudioCapture.create(context)
        val frames =
            try {
                runBlocking {
                    withTimeoutOrNull(CAPTURE_TIMEOUT_MILLIS) {
                        capture.frames().take(FRAMES_TO_CAPTURE).toList()
                    }
                }
            } finally {
                runBlocking { capture.close() }
            }

        assertNotNull("capture produced no frames within the timeout", frames)
        assertTrue("capture produced no frames", frames!!.isNotEmpty())
        assertTrue("capture must emit 16 kHz mono frames", frames.all { it.format == AudioFormat.MONO_16_KHZ })

        // The fast onset path runs over the captured audio and never crashes.
        val activity =
            runBlocking {
                EnergyVoiceActivityDetector(configuration()).observe(frames.asFlow()).toList()
            }
        assertTrue(
            "VAD events must not fail on live audio: $activity",
            activity.none { it is VadEvent.Failed },
        )

        // The bounded endpoint policy always terminates the finite window with a
        // typed endpoint (EMPTY_NO_SPEECH for silence, SILENCE_CAP or
        // CAPTURE_ENDED once speech has been detected).
        val events =
            runBlocking {
                BoundedTurnEndpointPolicy(configuration()).observe(frames.asFlow()).toList()
            }
        val endpoints = events.filterIsInstance<TurnDetectionEvent.Endpointed>()
        assertTrue("the endpoint policy must emit at least one endpoint", endpoints.isNotEmpty())
        assertTrue(
            "endpoints must carry a non-negative capture offset",
            endpoints.all { it.atOffsetMillis >= 0L },
        )
        assertTrue(
            "detection must not fail on live mono audio",
            events.none { it is TurnDetectionEvent.Failed },
        )
    }

    private fun configuration() = CaptureTurnDetection.configFor(AudioRoute.UNKNOWN)

    private companion object {
        const val CAPTURE_TIMEOUT_MILLIS = 15_000L

        /** ~2 s at 20 ms per frame; enough for the state machine to see silence or ambient speech. */
        const val FRAMES_TO_CAPTURE = 100
    }
}
