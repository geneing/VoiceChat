package com.voicechat.agent.audio

import android.media.AudioRecord
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.fake.FakeAudioFocusController
import com.voicechat.agent.fake.FakeAudioRouteMonitor
import com.voicechat.agent.fake.FakeMicrophonePermission
import com.voicechat.agent.fake.FakePcmRecorderEngine
import com.voicechat.agent.fake.FakePcmRecorderFactory
import com.voicechat.agent.fake.RecordingDiagnosticsSink
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Fake-source tests for [MicrophoneAudioInput]: start/stop, typed failures,
 * permission denial and revocation, focus and route handling, off-main-thread
 * I/O, and recorder cleanup. The capture loop runs on a real named executor so
 * the assertions observe the same threading the app uses, not a test scheduler.
 */
class MicrophoneAudioInputTest {
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
    fun emitsConfiguredMonoFramesAndReleasesTheRecorder() =
        runBlocking {
            val engine = FakePcmRecorderEngine(samples = shortArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
            val input = input(engine)

            val frames = withTimeout(TIMEOUT_MILLIS) { input.frames().take(2).toList() }

            assertEquals(2, frames.size)
            assertEquals(listOf<Short>(1, 2, 3, 4), frames[0].samples.toList())
            assertEquals(listOf<Short>(5, 6, 7, 8), frames[1].samples.toList())
            assertTrue(frames.all { it.format == AudioFormat.MONO_16_KHZ })
            assertTrue(frames.all { it.capturedAtNanos > 0L })
            awaitUntil { engine.releaseCount == 1 }
            assertEquals(1, engine.startCount)
            assertTrue(engine.stopCount >= 1)
        }

    @Test
    fun aStartFailureSurfacesATypedErrorAndReleasesTheRecorder() =
        runBlocking {
            val engine =
                FakePcmRecorderEngine(
                    startFailure = VoiceAgentError(ErrorCode.AUDIO_CAPTURE_FAILED, "start failed"),
                )
            val input = input(engine)

            val thrown = captureFailure(input)

            assertEquals(ErrorCode.AUDIO_CAPTURE_FAILED, thrown.error.code)
            awaitUntil { engine.releaseCount == 1 }
        }

    @Test
    fun aReadErrorSurfaceTheTypedDeviceError() =
        runBlocking {
            val engine =
                FakePcmRecorderEngine(
                    samples = shortArrayOf(1, 2, 3, 4),
                    readFailureAfterFrames = 1,
                    readFailureCode = AudioRecord.ERROR_DEAD_OBJECT,
                )
            val input = input(engine)

            val thrown = captureFailure(input)

            assertEquals(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, thrown.error.code)
            awaitUntil { engine.releaseCount == 1 }
        }

    @Test
    fun aCreateFailureIsReportedWithoutStartingAnything() =
        runBlocking {
            val engine = FakePcmRecorderEngine()
            val factory =
                FakePcmRecorderFactory(
                    engine,
                    createFailure = VoiceAgentException(VoiceAgentError(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, "no device")),
                )
            val input = input(engine, factory = factory)

            val thrown = captureFailure(input)

            assertEquals(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, thrown.error.code)
            assertEquals(1, factory.createCount)
            assertEquals(0, engine.startCount)
        }

    @Test
    fun deniedPermissionFailsBeforeTheRecorderIsOpened() =
        runBlocking {
            val engine = FakePcmRecorderEngine(samples = shortArrayOf(1, 2, 3, 4))
            val factory = FakePcmRecorderFactory(engine)
            val input =
                input(
                    engine,
                    factory = factory,
                    permission = FakeMicrophonePermission(granted = false),
                )

            val thrown = captureFailure(input)

            assertEquals(ErrorCode.AUDIO_PERMISSION_DENIED, thrown.error.code)
            assertEquals(0, factory.createCount)
            assertEquals(0, engine.startCount)
        }

    @Test
    fun revocationDuringCaptureFailsWithPermissionDenied() =
        runBlocking {
            val permission = FakeMicrophonePermission(revokeAfterChecks = 1)
            val engine = FakePcmRecorderEngine(samples = ShortArray(10_000) { 1 })
            val input =
                input(
                    engine,
                    permission = permission,
                    config = config(permissionRecheckIntervalFrames = 1),
                )

            val thrown = captureFailure(input)

            assertEquals(ErrorCode.AUDIO_PERMISSION_DENIED, thrown.error.code)
            assertTrue(permission.checkCount >= 2)
            awaitUntil { engine.releaseCount == 1 }
        }

    @Test
    fun acquiresAndAbandonsAudioFocusAroundTheSession() =
        runBlocking {
            val focus = FakeAudioFocusController()
            val engine = FakePcmRecorderEngine(samples = shortArrayOf(1, 2, 3, 4))
            val input = input(engine, focus = focus)

            input.frames().take(1).toList()

            awaitUntil { focus.abandonCount == 1 }
            assertEquals(1, focus.acquireCount)
        }

    @Test
    fun recordsTheRouteAtStartAndWhenItChanges() =
        runBlocking {
            val sink = RecordingDiagnosticsSink()
            val routeMonitor = FakeAudioRouteMonitor(AudioRoute(AudioRouteType.BUILTIN_MIC))
            val engine = FakePcmRecorderEngine(samples = ShortArray(10_000) { 500 })
            val input = input(engine, routeMonitor = routeMonitor, diagnostics = sink)

            val job =
                launch(dispatcher) {
                    input.frames().collect { }
                }
            awaitUntil { sink.events.any { it.outcome == DiagnosticOutcome.STARTED } }
            assertEquals(
                "BUILTIN_MIC",
                sink.events
                    .first { it.outcome == DiagnosticOutcome.STARTED }
                    .attributes[DiagnosticAttribute.AUDIO_ROUTE],
            )

            routeMonitor.setRoute(AudioRoute(AudioRouteType.BLUETOOTH_SCO))
            awaitUntil {
                sink.events.any {
                    it.outcome == DiagnosticOutcome.PROGRESS &&
                        it.attributes[DiagnosticAttribute.AUDIO_ROUTE] == "BLUETOOTH_SCO"
                }
            }

            input.close()
            withTimeout(TIMEOUT_MILLIS) { job.join() }
        }

    @Test
    fun reportsLevelsAndClippingWithoutSampleContent() =
        runBlocking {
            val sink = RecordingDiagnosticsSink()
            val engine = FakePcmRecorderEngine(samples = ShortArray(400) { Short.MAX_VALUE })
            val input =
                input(
                    engine,
                    diagnostics = sink,
                    config = config(diagnosticIntervalFrames = 1),
                )

            input.frames().take(1).toList()
            awaitUntil {
                sink.events.any { event ->
                    event.attributes[DiagnosticAttribute.AUDIO_CLIPPED_SAMPLES]?.toLongOrNull()?.let { it > 0 } == true
                }
            }

            val progress = sink.events.first { it.attributes[DiagnosticAttribute.AUDIO_PEAK_LEVEL] != null }
            assertTrue(progress.attributes[DiagnosticAttribute.AUDIO_PEAK_LEVEL]!!.toFloat() > 0.99f)
            assertEquals(DiagnosticStage.AUDIO_INPUT, progress.stage)
            assertEquals(
                "capture level events must carry only identifiers, levels, and counts",
                setOf(
                    DiagnosticAttribute.AUDIO_PEAK_LEVEL,
                    DiagnosticAttribute.AUDIO_RMS_LEVEL,
                    DiagnosticAttribute.AUDIO_CLIPPED_SAMPLES,
                    DiagnosticAttribute.AUDIO_DROPPED_FRAMES,
                    DiagnosticAttribute.FRAME_COUNT,
                ),
                progress.attributes.keys,
            )
        }

    @Test
    fun countsDroppedFramesWhenTheConsumerLags() =
        runBlocking {
            val sink = RecordingDiagnosticsSink()
            val engine = FakePcmRecorderEngine(samples = ShortArray(1_000_000) { 100 })
            val input =
                input(
                    engine,
                    diagnostics = sink,
                    config = config(bufferFrameCapacity = 1, diagnosticIntervalFrames = 5),
                )

            val job =
                launch(dispatcher) {
                    input.frames().collect { delay(25) }
                }
            awaitUntil {
                sink.events.any {
                    (it.attributes[DiagnosticAttribute.AUDIO_DROPPED_FRAMES]?.toLongOrNull() ?: 0L) > 0L
                }
            }

            input.close()
            withTimeout(TIMEOUT_MILLIS) { job.join() }
        }

    @Test
    fun closeStopsAnActiveSessionAndReleasesTheRecorder() =
        runBlocking {
            val engine = FakePcmRecorderEngine(samples = ShortArray(0))
            val input = input(engine)

            val job =
                launch(dispatcher) {
                    input.frames().collect { }
                }
            awaitUntil { engine.startCount == 1 }

            input.close()
            input.close()

            withTimeout(TIMEOUT_MILLIS) { job.join() }
            awaitUntil { engine.releaseCount == 1 }
            assertTrue(engine.stopCount >= 1)
        }

    @Test
    fun collectingAfterCloseFailsExplicitly() =
        runBlocking {
            val input = input(FakePcmRecorderEngine())
            input.close()

            val thrown = captureFailure(input)

            assertEquals(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, thrown.error.code)
        }

    @Test
    fun allRecorderIoRunsOffTheMainThread() =
        runBlocking {
            val engine = FakePcmRecorderEngine(samples = shortArrayOf(1, 2, 3, 4))
            val input = input(engine)

            input.frames().take(1).toList()
            awaitUntil { engine.releaseCount == 1 }

            assertTrue(engine.readThreadNames.isNotEmpty())
            assertTrue(
                "reads must run on the capture dispatcher but were ${engine.readThreadNames}",
                engine.readThreadNames.all { it.startsWith(CAPTURE_THREAD) },
            )
            assertTrue(
                "platform lifecycle calls must not run on main but were ${engine.lifecycleThreadNames}",
                engine.lifecycleThreadNames.none { it.contains("main") },
            )
        }

    private fun input(
        engine: FakePcmRecorderEngine,
        factory: FakePcmRecorderFactory = FakePcmRecorderFactory(engine),
        permission: MicrophonePermission = FakeMicrophonePermission(),
        routeMonitor: AudioRouteMonitor? = null,
        focus: AudioFocusController? = null,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        config: MicrophoneCaptureConfig = config(),
    ): MicrophoneAudioInput =
        MicrophoneAudioInput(
            permission = permission,
            recorderFactory = factory,
            config = config,
            routeMonitor = routeMonitor,
            focusController = focus,
            diagnostics = diagnostics,
            dispatcher = dispatcher,
        )

    private fun config(
        frameSizeSamples: Int = 4,
        bufferFrameCapacity: Int = 64,
        diagnosticIntervalFrames: Int = 25,
        permissionRecheckIntervalFrames: Int = 50,
    ): MicrophoneCaptureConfig =
        MicrophoneCaptureConfig(
            frameSizeSamples = frameSizeSamples,
            bufferFrameCapacity = bufferFrameCapacity,
            diagnosticIntervalFrames = diagnosticIntervalFrames,
            permissionRecheckIntervalFrames = permissionRecheckIntervalFrames,
        )

    private suspend fun captureFailure(input: AudioInput): VoiceAgentException {
        val thrown = withTimeout(TIMEOUT_MILLIS) { runCatching { input.frames().toList() }.exceptionOrNull() }
        assertTrue("expected a typed VoiceAgentException but was $thrown", thrown is VoiceAgentException)
        return thrown as VoiceAgentException
    }

    private suspend fun awaitUntil(
        timeoutMillis: Long = TIMEOUT_MILLIS,
        condition: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("condition was not met within ${timeoutMillis}ms")
            delay(10)
        }
    }

    private companion object {
        const val CAPTURE_THREAD = "capture-test-thread"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
