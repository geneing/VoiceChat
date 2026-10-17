package com.voicechat.agent.audio

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.fake.FakeMicrophonePermission
import com.voicechat.agent.fake.FakePcmRecorderEngine
import com.voicechat.agent.fake.FakePcmRecorderFactory
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Echo-cancellation wiring on the capture path.
 *
 * The platform canceller needs a device, so what is asserted here is the
 * **decision** (what each platform answer means) and the **wiring** (a capture
 * session attaches it once with the recorder's session id, reports the outcome,
 * and releases it). Whether Pixel 10 actually cancels the assistant's own audio
 * stays a device question (R-0254).
 */
class EchoCancellationTest {
    private lateinit var executor: ExecutorService
    private lateinit var dispatcher: CoroutineDispatcher

    @Before
    fun setUp() {
        executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, CAPTURE_THREAD) }
        dispatcher = executor.asCoroutineDispatcher()
    }

    @After
    fun tearDown() {
        executor.shutdownNow()
    }

    @Test
    fun thePlatformAnswerDecidesTheReportedState() {
        assertEquals(EchoCancellationState.UNAVAILABLE, echoCancellationState(available = false, reportedEnabled = false))
        assertEquals(EchoCancellationState.ENABLED, echoCancellationState(available = true, reportedEnabled = true))
        assertEquals(EchoCancellationState.FAILED, echoCancellationState(available = true, reportedEnabled = false))
    }

    @Test
    fun theDefaultCancellerClaimsNothing() {
        val cancellation = EchoCanceller.NONE.attach(audioSessionId = 42)

        assertFalse(cancellation.enabled)
        assertEquals(EchoCancellationState.UNAVAILABLE, cancellation.state)
    }

    @Test
    fun aCaptureSessionAttachesTheCancellerOnceAndReleasesIt() =
        runBlocking {
            val engine = FakePcmRecorderEngine(samples = ShortArray(64) { 1 })
            val canceller = RecordingEchoCanceller()
            val diagnostics = RecordingDiagnosticsSink()
            val input = input(engine, canceller, diagnostics)

            withTimeout(SESSION_TIMEOUT_MILLIS) { input.frames().take(2).toList() }

            assertEquals("the canceller must be attached once", 1, canceller.attachCount)
            assertTrue("release must run when the session ends", canceller.released)
            val echoEvent =
                diagnostics.events.lastOrNull {
                    it.stage == DiagnosticStage.AUDIO_INPUT &&
                        it.attributes[DiagnosticAttribute.STREAM_STATE]?.startsWith("echo-canceller") == true
                }
            assertEquals(
                "the capture trace must say what the canceller did",
                "echo-canceller-enabled",
                echoEvent?.attributes?.get(DiagnosticAttribute.STREAM_STATE),
            )
        }

    private fun input(
        engine: FakePcmRecorderEngine,
        canceller: EchoCanceller,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    ): MicrophoneAudioInput =
        MicrophoneAudioInput(
            permission = FakeMicrophonePermission(),
            recorderFactory = FakePcmRecorderFactory(engine),
            config = MicrophoneCaptureConfig(frameSizeSamples = 4),
            echoCanceller = canceller,
            diagnostics = diagnostics,
            dispatcher = dispatcher,
        )

    private companion object {
        const val CAPTURE_THREAD = "echo-capture-test"
        const val SESSION_TIMEOUT_MILLIS = 5_000L
    }
}

/** Records attach/release so the capture wiring can be asserted without a device. */
private class RecordingEchoCanceller : EchoCanceller {
    var attachCount: Int = 0
        private set

    var released: Boolean = false
        private set

    override fun attach(audioSessionId: Int?): EchoCancellation {
        attachCount++
        return EchoCancellation(EchoCancellationState.ENABLED)
    }

    override fun release() {
        released = true
    }
}
