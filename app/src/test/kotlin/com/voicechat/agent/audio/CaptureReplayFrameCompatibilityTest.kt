package com.voicechat.agent.audio

import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.fake.FakeMicrophonePermission
import com.voicechat.agent.fake.FakePcmRecorderEngine
import com.voicechat.agent.fake.FakePcmRecorderFactory
import com.voicechat.agent.replay.ReplayAudioInput
import com.voicechat.agent.replay.ReplayFixtures
import com.voicechat.agent.replay.ReplaySpeechToText
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Proves the M07 acceptance criterion that replay and physical capture use
 * compatible frame contracts.
 *
 * Both a [ReplayAudioInput] over a frozen fixture and a [MicrophoneAudioInput]
 * over a fake `AudioRecord` are fed through the *same* consumers: a plain frame
 * collector, and the real [ReplaySpeechToText] adapter. If the capture path
 * produced a different format, frame shape, or ordering, these assertions would
 * fail.
 */
class CaptureReplayFrameCompatibilityTest {
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
    fun aSharedConsumerSeesIdenticalFramesFromReplayAndCapture() =
        runBlocking {
            val fixture = ReplayFixtures.syntheticPauseResume(frameSizeSamples = FRAME_SIZE)
            val replayed = unify(ReplayAudioInput(fixture))

            val engine = FakePcmRecorderEngine(format = AudioFormat.MONO_16_KHZ, samples = fixture.samples)
            val captured = unify(captureInput(engine), limit = replayed.frames.size)

            assertEquals(AudioFormat.MONO_16_KHZ, replayed.format)
            assertEquals(AudioFormat.MONO_16_KHZ, captured.format)
            assertEquals(replayed.sampleCounts, captured.sampleCounts)
            assertEquals(replayed.samples, captured.samples)
            assertTrue(replayed.frames.size > 1)
        }

    @Test
    fun theReplaySttAdapterConsumesCapturedFramesIdentically() =
        runBlocking {
            val fixture = ReplayFixtures.syntheticPauseResume(frameSizeSamples = FRAME_SIZE)
            val frameCount = fixture.sliceFrames(FRAME_SIZE).size

            val expected =
                ReplaySpeechToText(fixture)
                    .transcribe(ReplayAudioInput(fixture).frames())
                    .toList()

            val engine = FakePcmRecorderEngine(format = AudioFormat.MONO_16_KHZ, samples = fixture.samples)
            val captureInput = captureInput(engine)
            val actual =
                withTimeout(TIMEOUT_MILLIS) {
                    ReplaySpeechToText(fixture)
                        .transcribe(captureInput.frames().take(frameCount))
                        .toList()
                }
            captureInput.close()

            assertTrue(expected.isNotEmpty())
            assertEquals(expected, actual)
        }

    /** The single consumer both sources are fed through. */
    private suspend fun unify(
        input: AudioInput,
        limit: Int? = null,
    ): UnifiedFrames {
        val frames: List<AudioFrame> =
            withTimeout(TIMEOUT_MILLIS) {
                if (limit == null) input.frames().toList() else input.frames().take(limit).toList()
            }
        return UnifiedFrames(frames)
    }

    private fun captureInput(engine: FakePcmRecorderEngine): MicrophoneAudioInput =
        MicrophoneAudioInput(
            permission = FakeMicrophonePermission(),
            recorderFactory = FakePcmRecorderFactory(engine),
            config = MicrophoneCaptureConfig(frameSizeSamples = FRAME_SIZE),
            dispatcher = dispatcher,
        )

    private class UnifiedFrames(
        val frames: List<AudioFrame>,
    ) {
        val format: AudioFormat get() = frames.first().format

        val sampleCounts: List<Int> get() = frames.map { it.sampleCount }

        val samples: List<Short> get() = frames.flatMap { it.samples.toList() }
    }

    private companion object {
        const val FRAME_SIZE = 320
        const val CAPTURE_THREAD = "compat-capture-thread"
        const val TIMEOUT_MILLIS = 10_000L
    }
}
